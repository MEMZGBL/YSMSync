package moe.meowrealms.ysmsync.web

import com.google.gson.GsonBuilder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import moe.meowrealms.ysmsync.YSMSyncMain
import moe.meowrealms.ysmsync.data.PlayerDataStorage.getYSMSyncData
import moe.meowrealms.ysmsync.model.ModelManager
import moe.meowrealms.ysmsync.modernysm.ModernYsmManager
import moe.meowrealms.ysmsync.network.ClientConnectionManager
import moe.meowrealms.ysmsync.network.ClientConnectionManager.getYsmConnection
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Server-side portal; stock YSM still receives models over YSMSync's protocol. */
object WebPortalManager {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val random = SecureRandom(); private val sessions = ConcurrentHashMap<String, String>(); private val codes = ConcurrentHashMap<String, String>()
    private lateinit var plugin: YSMSyncMain; private lateinit var file: java.nio.file.Path; private var data = Store(); private var server: HttpServer? = null; private var timer = -1; private var lastModelSignature = ""; private var maxSize = 16 * 1024 * 1024
    data class Store(val accounts: MutableMap<String, Account> = linkedMapOf(), val models: MutableMap<String, PortalModel> = linkedMapOf())
    data class Account(var password: String = "", var salt: String = "", var uuid: String? = null)
    data class PortalModel(var id: String = "", var name: String = "", var owner: String = "", var private: Boolean = false, val grants: MutableSet<String> = linkedSetOf())

    fun start(instance: YSMSyncMain) {
        plugin = instance
        if (!plugin.config.getBoolean("web-portal.enabled", true)) {
            plugin.slF4JLogger.info("YSM web portal disabled by config (web-portal.enabled=false).")
            // 门户关闭时仍然要跑原生同步复查，否则客户端模型同步会缺少定时纠偏
            startSyncRecheckTimer()
            return
        }

        file = plugin.dataFolder.toPath().resolve("web_portal.json")
        maxSize = plugin.config.getInt("web-portal.max-upload-bytes", maxSize)
        load()

        val host = plugin.config.getString("web-portal.host", "0.0.0.0")!!
        val port = plugin.config.getInt("web-portal.port", 25584)

        // 端口被占用属于常见的部署事故（重复启动实例、端口被其他程序抢占）。
        // 这里必须降级处理：Web 门户是可选的附属功能，绝不能让它把
        // 模型同步这个主功能一起拖垮——onEnable 抛出异常会导致整个插件被服务端禁用。
        try {
            server = HttpServer.create(InetSocketAddress(host, port), 0).also {
                it.executor = Executors.newFixedThreadPool(4)
                it.createContext("/") { e -> route(e) }
                it.start()
            }
        } catch (throwable: Throwable) {
            server = null
            plugin.slF4JLogger.error(
                "无法在 {}:{} 启动 YSM Web 门户（{}）。模型同步功能不受影响，" +
                    "如需网页管理请修改 config.yml 的 web-portal.port，或设置 web-portal.enabled=false 关闭该提示。",
                host, port, throwable.message
            )
            startSyncRecheckTimer()
            return
        }

        startSyncRecheckTimer()
        plugin.slF4JLogger.info(
            "YSM web portal: http://{}:{}, native sync recheck={}s",
            host, port, syncRecheckSeconds()
        )
    }

    private fun syncRecheckSeconds(): Long =
        plugin.config.getLong("web-portal.sync-check-interval-seconds", 30).coerceAtLeast(10)

    /**
     * 定期检查模型集合有没有变化，**只有变化了才重新同步**。
     *
     * 早期实现是无条件每 N 秒对全部在线玩家重推一次模型，导致玩家在客户端里
     * 平白无故看到一次同步过程（点开模型分组时尤其明显），而模型其实一个字节都没变。
     * 现在改为比对模型指纹：上传 / 删除 / 重载模型后指纹改变，才会触发重新同步。
     */
    private fun startSyncRecheckTimer() {
        val seconds = syncRecheckSeconds()
        lastModelSignature = ModelManager.modelSignature()

        timer = Bukkit.getScheduler().scheduleSyncRepeatingTask(
            plugin,
            Runnable {
                val current = ModelManager.modelSignature()
                if (current == lastModelSignature) {
                    return@Runnable
                }
                lastModelSignature = current
                plugin.slF4JLogger.info("Model catalog changed; re-synchronizing online players.")
                ClientConnectionManager.restartModelSynchronizationForConnectedPlayers()
                ModernYsmManager.broadcastCatalogChanged()
            },
            seconds * 20,
            seconds * 20
        )
    }
    fun stop() { if (timer != -1) Bukkit.getScheduler().cancelTask(timer); timer = -1; server?.stop(1); server = null; if (::file.isInitialized) save() }
    fun bindPlayer(player: Player, code: String): String { val user = codes.remove(code) ?: return "绑定码无效或已过期。"; synchronized(this) { data.accounts[user]?.uuid = player.uniqueId.toString(); save() }; return "已绑定网页账户 $user。" }
    fun canUse(player: UUID, id: String): Boolean { val m = synchronized(this) { data.models[id] } ?: return true; return !m.private || m.grants.contains(player.toString()) }
    fun isVisibleTo(player: UUID, id: String) = canUse(player, id)
    /** Administrators are deliberately configured server-side, never supplied by the browser. */
    private fun isAdmin(username: String): Boolean = plugin.config.getStringList("web-portal.admin-users").any { it.equals(username, true) }

    private fun route(e: HttpExchange) { try { val p = e.requestURI.path; when {
        e.requestMethod == "GET" && p == "/" -> page(e)
        e.requestMethod == "GET" && p == "/api/me" -> me(e)
        e.requestMethod == "GET" && p == "/api/models" -> list(e)
        e.requestMethod == "GET" && p == "/api/admin/models" -> adminList(e)
        e.requestMethod == "POST" && p == "/api/register" -> register(e)
        e.requestMethod == "POST" && p == "/api/login" -> login(e)
        e.requestMethod == "POST" && p == "/api/logout" -> { session(e)?.let { sessions.remove(it) }; json(e, 200, mapOf("ok" to true)) }
        e.requestMethod == "POST" && p == "/api/bind" -> bind(e)
        e.requestMethod == "POST" && p == "/api/upload" -> upload(e)
        e.requestMethod == "POST" && p == "/api/admin/sync" -> adminSync(e)
        e.requestMethod == "POST" && p.startsWith("/api/model/") && p.endsWith("/delete") -> delete(e, p.removePrefix("/api/model/").removeSuffix("/delete").trimEnd('/'))
        e.requestMethod == "POST" && p.startsWith("/api/model/") && p.endsWith("/use") -> use(e, p.removePrefix("/api/model/").removeSuffix("/use").trimEnd('/'))
        e.requestMethod == "POST" && p.startsWith("/api/model/") && p.endsWith("/grant") -> grant(e, p.removePrefix("/api/model/").removeSuffix("/grant").trimEnd('/'))
        else -> json(e, 404, mapOf("error" to "not found"))
    } } catch (x: Throwable) { plugin.slF4JLogger.warn("Web portal request", x); json(e, 400, mapOf("error" to (x.message ?: "请求失败"))) } finally { e.close() } }
    private fun register(e: HttpExchange) { val f = form(e); val u = username(f["username"] ?: ""); val p = f["password"] ?: ""; require(p.length >= 8) { "密码至少8位" }; synchronized(this) { require(!data.accounts.containsKey(u)) { "用户名已存在" }; val salt = ByteArray(16).also(random::nextBytes); data.accounts[u] = Account(hash(p, salt), b64(salt)); save() }; json(e, 201, mapOf("ok" to true)) }
    private fun login(e: HttpExchange) { val f = form(e); val u = username(f["username"] ?: ""); val a = synchronized(this) { data.accounts[u] } ?: throw IllegalArgumentException("用户名或密码错误"); require(hash(f["password"] ?: "", Base64.getUrlDecoder().decode(a.salt)) == a.password) { "用户名或密码错误" }; val t = ByteArray(32).also(random::nextBytes).let(::b64); sessions[t] = u; e.responseHeaders.add("Set-Cookie", "ysmportal=$t; HttpOnly; SameSite=Lax; Path=/"); json(e, 200, mapOf("ok" to true)) }
    private fun me(e: HttpExchange) { val u = user(e); val a = synchronized(this) { data.accounts[u]!! }; json(e, 200, mapOf("username" to u, "bound" to (a.uuid != null), "uuid" to a.uuid, "admin" to isAdmin(u))) }
    private fun bind(e: HttpExchange) { val u = user(e); val c = (10000000 + random.nextInt(90000000)).toString(); codes[c] = u; json(e, 200, mapOf("instruction" to "/ysmsyncweb bind $c")) }
    private fun list(e: HttpExchange) { val u = user(e); val uuid = synchronized(this) { data.accounts[u]?.uuid }; val result = synchronized(this) { data.models.values.filter { !it.private || it.owner == u || (uuid != null && it.grants.contains(uuid)) } }; json(e, 200, result) }
    private fun adminList(e: HttpExchange) { val u = user(e); require(isAdmin(u)) { "需要管理员权限" }; val result = synchronized(this) { data.models.values.toList() }; json(e, 200, result) }
    private fun upload(e: HttpExchange) { val u = user(e); val a = synchronized(this) { data.accounts[u]!! }; val uuid = a.uuid ?: throw IllegalArgumentException("请先绑定游戏角色"); val part = multipart(e); val private = part.fields["visibility"] == "private"; require(part.name.endsWith(".ysm", true)) { "只允许.ysm文件" }; require(part.bytes.size in 1..maxSize) { "文件大小无效" }; val slug = part.name.removeSuffix(".ysm").replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80); require(slug.isNotBlank()) { "文件名无效" }; val id = if (private) "portal/private/$uuid/$slug.ysm" else "portal/public/$slug.ysm"; val target = plugin.dataFolder.toPath().resolve("models/custom").resolve(id).normalize(); require(target.startsWith(plugin.dataFolder.toPath().resolve("models/custom").normalize())) { "非法路径" }; Files.createDirectories(target.parent); Files.write(target, part.bytes); val model = PortalModel(id, part.name.removeSuffix(".ysm"), u, private).also { it.grants.add(uuid) }; synchronized(this) { data.models[id] = model; save() }; try { reload(); require(ModelManager.getModelData(id) != null) { "YSM无法解析该模型" } } catch (x: Throwable) { Files.deleteIfExists(target); synchronized(this) { data.models.remove(id); save() }; throw x }; json(e, 201, mapOf("ok" to true, "message" to "模型已校验并加入原生YSM同步")) }
    private fun grant(e: HttpExchange, id: String) { val u = user(e); val m = synchronized(this) { data.models[id] } ?: throw IllegalArgumentException("模型不存在"); require(m.private && m.owner == u) { "无权授权" }; val target = Bukkit.getOfflinePlayer(form(e)["player"] ?: ""); require(target.hasPlayedBefore()) { "玩家尚未进过服务器" }; synchronized(this) { m.grants.add(target.uniqueId.toString()); save() }; sync(); json(e, 200, mapOf("ok" to true)) }
    private fun adminSync(e: HttpExchange) { val u = user(e); require(isAdmin(u)) { "需要管理员权限" }; sync(); json(e, 200, mapOf("ok" to true, "message" to "已向所有在线 YSM 客户端发起模型重新同步")) }
    private fun delete(e: HttpExchange, id: String) {
        val u = user(e); require(isAdmin(u)) { "需要管理员权限" }
        val model = synchronized(this) { data.models[id] } ?: throw IllegalArgumentException("模型不存在或不属于网页模型中心")
        // The index is authoritative.  Never resolve an arbitrary browser-supplied path.
        require(model.id == id && id.startsWith("portal/")) { "非法模型标识" }
        val modelsRoot = plugin.dataFolder.toPath().resolve("models/custom").normalize()
        val target = modelsRoot.resolve(id).normalize()
        require(target.startsWith(modelsRoot) && target.fileName.toString().endsWith(".ysm", true)) { "非法模型路径" }
        Files.deleteIfExists(target)
        synchronized(this) { data.models.remove(id); save() }
        reload()
        json(e, 200, mapOf("ok" to true, "message" to "模型已删除，已向所有在线 YSM 客户端发起重新同步；仍引用此模型的玩家会自动回退默认模型。"))
    }
    private fun use(e: HttpExchange, id: String) { val u = user(e); val uuid = synchronized(this) { data.accounts[u]?.uuid } ?: throw IllegalArgumentException("请先绑定游戏角色"); require(canUse(UUID.fromString(uuid), id)) { "未获授权" }; val p = Bukkit.getPlayer(UUID.fromString(uuid)) ?: throw IllegalArgumentException("请先进入服务器" ); main { val texture = ModelManager.defaultTextureOf(id) ?: throw IllegalArgumentException("模型没有材质"); val d = p.getYSMSyncData() ?: throw IllegalArgumentException("YSM数据未就绪"); d.selectedModelId = id; d.selectedModelTexture = texture; d.disabled = false; d.markDirty(); p.getYsmConnection().syncModelSelectionData() }; json(e, 200, mapOf("ok" to true, "message" to "已应用模型")) }
    /**
     * Model parsing/cache generation can take tens of seconds for a large .ysm.
     * Never run it through callSyncMethod: that blocks the Minecraft tick thread
     * and Purpur's watchdog correctly terminates the server.  This method is
     * invoked by the portal's HTTP worker, then only the Bukkit-facing sync is
     * put back onto the server thread.
     */
    private fun reload() {
        ModelManager.loadModels()
        Bukkit.getScheduler().runTask(plugin, Runnable { sync() })
    }
    private fun sync() = ClientConnectionManager.restartModelSynchronizationForConnectedPlayers(); private fun main(block: () -> Unit) { Bukkit.getScheduler().callSyncMethod(plugin) { block() }.get() }
    private fun user(e: HttpExchange): String { val cookie = e.requestHeaders.getFirst("Cookie") ?: throw IllegalArgumentException("请先登录"); val t = cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("ysmportal=") }?.substringAfter('=') ?: throw IllegalArgumentException("请先登录"); return sessions[t] ?: throw IllegalArgumentException("登录已过期") }
    private fun session(e: HttpExchange): String? = try { user(e).let { sessions.entries.firstOrNull { x -> x.value == it }?.key } } catch (_: Exception) { null }
    private fun form(e: HttpExchange): Map<String, String> = e.requestBody.readBytes().toString(StandardCharsets.UTF_8).split('&').filter { it.contains('=') }.associate { val a = it.split('=', limit = 2); URLDecoder.decode(a[0], "UTF-8") to URLDecoder.decode(a[1], "UTF-8") }
    private data class Part(val fields: Map<String, String>, val name: String, val bytes: ByteArray)
    private fun multipart(e: HttpExchange): Part { val boundary = e.requestHeaders.getFirst("Content-Type")?.substringAfter("boundary=", "")?.trim()?.trim('"') ?: ""; require(boundary.isNotEmpty()) { "无效上传请求" }; val bytes = e.requestBody.readNBytes(maxSize + 65536); require(bytes.size <= maxSize + 65536) { "文件过大" }; val text = bytes.toString(StandardCharsets.ISO_8859_1); val fields = linkedMapOf<String, String>(); var name = ""; var value = ByteArray(0); text.split("--$boundary").forEach { s -> val h = s.indexOf("\r\n\r\n"); if (h < 0) return@forEach; val headers = s.substring(0, h); val key = Regex("name=\\\"([^\\\"]+)\\\"").find(headers)?.groupValues?.get(1) ?: return@forEach; val raw = s.substring(h + 4).removeSuffix("\r\n").toByteArray(StandardCharsets.ISO_8859_1); val n = Regex("filename=\\\"([^\\\"]*)\\\"").find(headers)?.groupValues?.get(1); if (n == null) fields[key] = raw.toString(StandardCharsets.UTF_8) else { name = n; value = raw } }; require(name.isNotEmpty()) { "未收到模型文件" }; return Part(fields, name, value) }
    /** 页面模板：首次请求时从 jar 内资源读取并缓存，避免每次请求重复 IO。 */
    @Volatile
    private var pageTemplate: String? = null

    private fun page(e: HttpExchange) {
        val b = renderPage().toByteArray(StandardCharsets.UTF_8)
        e.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        e.responseHeaders.set("Cache-Control", "no-store")
        e.sendResponseHeaders(200, b.size.toLong())
        e.responseBody.write(b)
    }

    /** 载入模板并替换占位符；标题可在 config.yml 的 web-portal.title 修改。 */
    private fun renderPage(): String {
        val template = pageTemplate ?: run {
            val loaded = runCatching {
                plugin.getResource("web/index.html")?.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            }.getOrNull()
            (loaded ?: FALLBACK_PAGE).also { pageTemplate = it }
        }
        val title = plugin.config.getString("web-portal.title", "YSMSync 模型中心")
            ?.takeIf { it.isNotBlank() } ?: "YSMSync 模型中心"
        return template.replace("__PORTAL_TITLE__", escapeHtml(title))
    }

    private fun escapeHtml(value: String): String = buildString(value.length + 16) {
        for (ch in value) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }
    private fun json(e: HttpExchange, code: Int, value: Any) { val b = gson.toJson(value).toByteArray(StandardCharsets.UTF_8); e.responseHeaders.set("Content-Type", "application/json; charset=utf-8"); e.sendResponseHeaders(code, b.size.toLong()); e.responseBody.write(b) }
    private fun load() { if (Files.exists(file)) data = gson.fromJson(Files.readString(file), Store::class.java) ?: Store() }; private fun save() { Files.createDirectories(file.parent); Files.writeString(file, gson.toJson(data)) }
    private fun username(s: String): String { val u = s.lowercase().trim(); require(u.matches(Regex("[a-z0-9_]{3,24}"))) { "用户名仅限3-24位英文数字下划线" }; return u }; private fun hash(p: String, salt: ByteArray): String { val k: KeySpec = PBEKeySpec(p.toCharArray(), salt, 210000, 256); return b64(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(k).encoded) }; private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    /** 资源缺失时的兜底页（正常不会用到）。 */
    private const val FALLBACK_PAGE = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>__PORTAL_TITLE__</title></head><body style=\"font:16px system-ui;padding:40px\"><h1>__PORTAL_TITLE__</h1><p>页面资源缺失，请重新安装插件。</p></body></html>"
}
