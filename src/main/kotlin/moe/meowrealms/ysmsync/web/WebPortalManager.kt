package moe.meowrealms.ysmsync.web

import com.google.gson.GsonBuilder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import moe.meowrealms.ysmsync.YSMSyncMain
import moe.meowrealms.ysmsync.data.PlayerDataStorage.getYSMSyncData
import moe.meowrealms.ysmsync.model.ModelManager
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
    private lateinit var plugin: YSMSyncMain; private lateinit var file: java.nio.file.Path; private var data = Store(); private var server: HttpServer? = null; private var timer = -1; private var maxSize = 16 * 1024 * 1024
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

    private fun startSyncRecheckTimer() {
        val seconds = syncRecheckSeconds()
        timer = Bukkit.getScheduler().scheduleSyncRepeatingTask(
            plugin,
            Runnable { ClientConnectionManager.restartModelSynchronizationForConnectedPlayers() },
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
    private fun page(e: HttpExchange) { val b = PAGE_V2.toByteArray(StandardCharsets.UTF_8); e.responseHeaders.set("Content-Type", "text/html; charset=utf-8"); e.sendResponseHeaders(200, b.size.toLong()); e.responseBody.write(b) }
    private fun json(e: HttpExchange, code: Int, value: Any) { val b = gson.toJson(value).toByteArray(StandardCharsets.UTF_8); e.responseHeaders.set("Content-Type", "application/json; charset=utf-8"); e.sendResponseHeaders(code, b.size.toLong()); e.responseBody.write(b) }
    private fun load() { if (Files.exists(file)) data = gson.fromJson(Files.readString(file), Store::class.java) ?: Store() }; private fun save() { Files.createDirectories(file.parent); Files.writeString(file, gson.toJson(data)) }
    private fun username(s: String): String { val u = s.lowercase().trim(); require(u.matches(Regex("[a-z0-9_]{3,24}"))) { "用户名仅限3-24位英文数字下划线" }; return u }; private fun hash(p: String, salt: ByteArray): String { val k: KeySpec = PBEKeySpec(p.toCharArray(), salt, 210000, 256); return b64(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(k).encoded) }; private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private const val PAGE_V2 = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>星辰聚落 YSM 模型中心</title>
<style>
:root{color-scheme:dark;--bg:#101522;--panel:#19233c;--card:#202d4a;--line:#40557f;--blue:#5a8cff;--good:#45c993;--bad:#ff7474;--muted:#aebbd3}*{box-sizing:border-box}body{margin:0;min-height:100vh;background:linear-gradient(135deg,#101522,#16233c);color:#f4f7ff;font:15px system-ui,-apple-system,"Microsoft YaHei",sans-serif;display:flex}aside{width:220px;min-height:100vh;padding:26px 18px;background:rgba(18,27,48,.9);border-right:1px solid var(--line)}aside h1{font-size:20px;margin:0 0 25px}main{width:min(920px,100%);padding:30px;margin:0 auto}.card{background:rgba(32,45,74,.96);border:1px solid #344b76;border-radius:14px;padding:21px;margin-bottom:16px;box-shadow:0 12px 30px #0002}.hide{display:none!important}.muted{color:var(--muted);line-height:1.6}.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center}input,select{color:#fff;background:#101a30;border:1px solid var(--line);border-radius:8px;padding:10px;min-height:39px}input:focus,select:focus{border-color:#83aaff;outline:2px solid #5a8cff44}button{cursor:pointer;color:#fff;background:linear-gradient(180deg,#6295ff,#416ed1);border:1px solid #87aaff;border-radius:8px;padding:10px 14px;min-height:39px;font-weight:650;transition:transform .12s ease,filter .12s ease,box-shadow .12s ease}button:hover{filter:brightness(1.12);box-shadow:0 5px 15px #356ee066}button:active{transform:scale(.94) translateY(1px);filter:brightness(.9);box-shadow:none}button:disabled{cursor:wait;opacity:.65;transform:none}.side{width:100%;text-align:left;margin:5px 0;background:#27385e;border-color:#40557f}.secondary{background:#2c3d60;border-color:#526a99}.danger{background:#803e50;border-color:#a55b6d}.progress-wrap{margin-top:14px}.progress{height:13px;overflow:hidden;border-radius:20px;background:#101827;border:1px solid #3c5079}.progress>i{display:block;width:0;height:100%;background:linear-gradient(90deg,#52c7a0,#5a8cff);transition:width .15s ease}.status{margin-top:10px;padding:11px 13px;border-left:4px solid #6f98ff;border-radius:6px;background:#14203a;line-height:1.55}.status.ok,#uploadQueue .ok{border-color:var(--good);color:var(--good)}.status.err,#uploadQueue .err{border-color:var(--bad);color:var(--bad)}#models{list-style:none;padding:0;margin:10px 0 0}#models li{display:flex;align-items:center;gap:9px;flex-wrap:wrap;padding:12px 0;border-top:1px solid #354a70}.tag{font-size:12px;padding:3px 7px;border-radius:99px;background:#334b78;color:#d8e5ff}.tag.private{background:#694777}.file-info{margin:9px 0 0;color:var(--muted)}#uploadQueue{margin:10px 0 0;padding-left:20px;max-height:200px;overflow:auto}@media(max-width:680px){body{display:block}aside{width:100%;min-height:0;padding:16px;display:flex;gap:8px;flex-wrap:wrap;align-items:center}aside h1{width:100%;margin:0 0 5px}.side{width:auto;margin:0}main{padding:16px}}
</style></head><body>
<aside><h1>✦ 星辰聚落</h1><button class="side" id="homeBtn">首页</button><button class="side" id="listBtn">模型列表</button><button class="side hide" id="adminBtn">管理后台</button><button class="side danger" id="logoutBtn">退出登录</button></aside>
<main><div id="notice" class="status" role="status">正在检查登录状态…</div>
<section id="login" class="card"><h2>登录 / 注册</h2><p class="muted">注册后需在游戏内完成一次绑定，才可上传或使用模型。</p><div class="row"><input id="username" autocomplete="username" placeholder="用户名：3–24 位英文、数字或下划线"><input id="password" type="password" autocomplete="current-password" placeholder="密码：至少 8 位"><button id="loginBtn">登录</button><button id="registerBtn" class="secondary">注册</button></div></section>
<section id="app" class="hide"><div class="card"><h2 id="who">模型中心</h2><p id="state" class="muted"></p><button id="bindBtn">生成游戏内绑定码</button><p id="bindHelp" class="status hide"></p></div>
<div class="card"><h2>批量上传模型</h2><p class="muted">可一次选择多个 .ysm 文件，单个文件最大 16 MB。系统会逐个上传、校验并建立缓存；某一文件失败不会中断其余文件，且不会暂停游戏服务器。</p><div class="row"><input id="file" type="file" accept=".ysm" multiple><select id="visibility"><option value="public">公共模型：所有玩家可使用</option><option value="private">私人模型：仅授权玩家可使用</option></select><button id="uploadBtn">上传所选模型</button></div><p id="fileInfo" class="file-info">尚未选择模型文件。</p><div id="progressWrap" class="progress-wrap hide"><div class="progress"><i id="bar"></i></div><p id="progressText" class="muted">等待上传…</p><ul id="uploadQueue" class="muted"></ul></div></div>
<div class="card"><h2>模型列表</h2><p class="muted">点击“使用”后，请保持在线等待模型同步完成。</p><ul id="models"></ul></div></section>
<section id="admin" class="card hide"><h2>管理员后台</h2><p class="muted">这里会显示网页模型中心的全部模型，包括其他账户的私人模型。删除不可恢复；在线 YSM 客户端会立即收到重新同步指令，当前选用被删除模型的玩家将自动回退默认模型。</p><div class="row"><button id="syncBtn" class="secondary">同步所有在线客户端</button></div><ul id="adminModels"></ul></section></main>
<script>
const el=id=>document.getElementById(id),API=(location.pathname.endsWith('/')?location.pathname.slice(0,-1):location.pathname)+'/api';let me=null;
function notice(text,type=''){const n=el('notice');n.textContent=text;n.className='status '+type}function size(n){return n<1048576?(n/1024).toFixed(1)+' KB':(n/1048576).toFixed(2)+' MB'}function busy(b,on,text){if(on){b.dataset.text=b.textContent;b.disabled=true;b.textContent=text}else{b.disabled=false;b.textContent=b.dataset.text||b.textContent}}
async function api(path,options={}){let r;try{r=await fetch(API+path,options)}catch(e){throw Error('网络请求失败，请检查宝塔穿透后重试。')}let raw=await r.text(),data;try{data=JSON.parse(raw)}catch(e){throw Error('服务返回了无法识别的数据。')}if(!r.ok)throw Error(data.error||('请求失败（HTTP '+r.status+'）'));return data}
async function home(){try{me=await api('/me');el('login').classList.add('hide');el('app').classList.remove('hide');el('admin').classList.add('hide');el('adminBtn').classList.toggle('hide',!me.admin);el('who').textContent='你好，'+me.username;el('state').textContent=me.bound?'已绑定游戏角色，可上传、使用模型。':'尚未绑定游戏角色：请先生成绑定码并在游戏内执行命令。';notice(me.bound?'已登录，模型中心已就绪。':'已登录，但还未绑定游戏角色。','ok');await list()}catch(e){me=null;el('login').classList.remove('hide');el('app').classList.add('hide');el('admin').classList.add('hide');el('adminBtn').classList.add('hide');notice('请登录或注册网页账户。')}}
async function auth(kind,button){const u=el('username').value.trim(),p=el('password').value;if(!u||!p){notice('请填写用户名和密码。','err');return}busy(button,true,kind==='login'?'正在登录…':'正在注册…');try{await api('/'+kind,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'username='+encodeURIComponent(u)+'&password='+encodeURIComponent(p)});notice(kind==='login'?'登录成功，正在载入模型中心…':'注册成功，请使用同一账号登录。','ok');if(kind==='login')await home()}catch(e){notice(e.message,'err')}finally{busy(button,false)}}
async function bind(){const b=el('bindBtn');busy(b,true,'正在生成…');try{let r=await api('/bind',{method:'POST'});let h=el('bindHelp');h.textContent='请在已进入服务器的角色聊天栏执行：'+r.instruction+'。绑定成功后刷新此页面。';h.classList.remove('hide');notice('绑定码已生成，请在游戏内完成绑定。','ok')}catch(e){notice(e.message,'err')}finally{busy(b,false)}}
function updateProgress(done,total,text){el('progressWrap').classList.remove('hide');el('bar').style.width=(total?Math.min(100,done*100/total):0)+'%';el('progressText').textContent=text}
function queueItem(text,state=''){let li=document.createElement('li');li.textContent=text;if(state)li.className=state;el('uploadQueue').append(li);return li}function selectedFiles(){return Array.from(el('file').files)}function uploadOne(f,visibility,onProgress){return new Promise(resolve=>{let data=new FormData(),x=new XMLHttpRequest();data.append('model',f);data.append('visibility',visibility);x.open('POST',API+'/upload');x.timeout=300000;x.upload.onprogress=e=>{if(e.lengthComputable)onProgress(e.loaded,e.total)};x.upload.onload=()=>onProgress(f.size,f.size);x.onerror=()=>resolve({ok:false,message:'网络中断，文件未能上传完成，请重试。'});x.ontimeout=()=>resolve({ok:false,message:'服务器校验超过 5 分钟仍未完成。'});x.onload=()=>{let d={};try{d=JSON.parse(x.responseText)}catch(e){}resolve(x.status>=200&&x.status<300?{ok:true,message:d.message||'已校验并加入同步队列。'}:{ok:false,message:d.error||('上传失败（HTTP '+x.status+'）')})};x.send(data)})}async function upload(){const files=selectedFiles(),b=el('uploadBtn');if(!files.length){notice('请先选择一个或多个 .ysm 模型文件。','err');return}const invalid=files.filter(f=>!f.name.toLowerCase().endsWith('.ysm')||f.size>16*1024*1024);if(invalid.length){notice('所选文件中有 '+invalid.length+' 个不符合要求：仅支持 .ysm 且单个不得超过 16 MB。','err');return}const total=files.reduce((n,f)=>n+f.size,0),visibility=el('visibility').value;let transferred=0,success=0,failed=0;el('uploadQueue').replaceChildren();busy(b,true,'正在批量上传…');notice('批量任务已开始：将逐个上传并校验，失败文件不会阻止后续文件。');for(let i=0;i<files.length;i++){const f=files[i],base=transferred,li=queueItem('等待：'+f.name+'（'+size(f.size)+'）');updateProgress(base,total,'正在上传第 '+(i+1)+' / '+files.length+' 个：'+f.name+'（0%）');let r=await uploadOne(f,visibility,(done,all)=>{let pct=Math.floor(done*100/all);updateProgress(base+done,total,'正在上传第 '+(i+1)+' / '+files.length+' 个：'+f.name+'（'+pct+'%）')});transferred+=f.size;if(r.ok){success++;li.textContent='完成：'+f.name+' — '+r.message;li.className='ok'}else{failed++;li.textContent='失败：'+f.name+' — '+r.message;li.className='err'}updateProgress(transferred,total,'已处理 '+(i+1)+' / '+files.length+' 个模型。')}busy(b,false);el('progressText').textContent='批量上传完成：成功 '+success+' 个，失败 '+failed+' 个。';notice('批量上传完成：成功 '+success+' 个，失败 '+failed+' 个。',failed?'err':'ok');if(success)await list()}
async function list(){if(!me)return;try{let all=await api('/models'),box=el('models');box.replaceChildren();if(!all.length){let li=document.createElement('li');li.textContent='暂无可用模型。';box.append(li);return}for(const m of all){let li=document.createElement('li'),name=document.createElement('strong'),tag=document.createElement('span'),use=document.createElement('button');name.textContent=m.name;tag.className='tag '+(m.private?'private':'');tag.textContent=m.private?'私人':'公共';use.textContent='使用';use.onclick=()=>useModel(m.id,use);li.append(name,tag,use);if(m.private&&m.owner===me.username){let grant=document.createElement('button');grant.className='secondary';grant.textContent='授权玩家';grant.onclick=()=>grantModel(m.id,grant);li.append(grant)}box.append(li)}}catch(e){notice('加载模型列表失败：'+e.message,'err')}}
async function useModel(id,b){busy(b,true,'正在应用…');try{let r=await api('/model/'+encodeURIComponent(id)+'/use',{method:'POST'});notice(r.message+' 请保持在线等待同步完成。','ok')}catch(e){notice(e.message,'err')}finally{busy(b,false)}}
async function grantModel(id,b){let player=prompt('输入要授权的游戏内玩家名：');if(!player)return;busy(b,true,'正在授权…');try{await api('/model/'+encodeURIComponent(id)+'/grant',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'player='+encodeURIComponent(player.trim())});notice('已授权 '+player+' 使用该私人模型。','ok')}catch(e){notice(e.message,'err')}finally{busy(b,false)}}
async function admin(){if(!me||!me.admin)return;el('app').classList.add('hide');el('admin').classList.remove('hide');try{let all=await api('/admin/models'),box=el('adminModels');box.replaceChildren();if(!all.length){let li=document.createElement('li');li.textContent='网页模型中心暂无模型。';box.append(li);return}for(const m of all){let li=document.createElement('li'),name=document.createElement('strong'),meta=document.createElement('span'),del=document.createElement('button');name.textContent=m.name;meta.className='tag '+(m.private?'private':'');meta.textContent=(m.private?'私人':'公共')+' · 上传者：'+m.owner;del.className='danger';del.textContent='删除模型';del.onclick=()=>deleteModel(m,del);li.append(name,meta,del);box.append(li)}}catch(e){notice('加载管理模型列表失败：'+e.message,'err')}}
async function deleteModel(m,b){if(!confirm('确认永久删除“'+m.name+'”吗？此操作不可恢复，并会立即同步在线 YSM 客户端。'))return;busy(b,true,'正在删除…');try{let r=await api('/model/'+encodeURIComponent(m.id)+'/delete',{method:'POST'});notice(r.message,'ok');await admin();await list()}catch(e){notice(e.message,'err');busy(b,false)}}
async function syncClients(){const b=el('syncBtn');busy(b,true,'正在同步…');try{let r=await api('/admin/sync',{method:'POST'});notice(r.message,'ok')}catch(e){notice(e.message,'err')}finally{busy(b,false)}}
async function logout(){try{await api('/logout',{method:'POST'});notice('已退出登录。','ok')}catch(e){notice(e.message,'err')}finally{home()}}
el('file').onchange=()=>{let files=selectedFiles(),total=files.reduce((n,f)=>n+f.size,0);el('fileInfo').textContent=files.length?'已选择 '+files.length+' 个模型（共 '+size(total)+'）：'+files.slice(0,3).map(f=>f.name).join('、')+(files.length>3?' 等':''):'尚未选择模型文件。'};el('homeBtn').onclick=home;el('listBtn').onclick=()=>{el('admin').classList.add('hide');el('app').classList.remove('hide');list()};el('adminBtn').onclick=admin;el('syncBtn').onclick=syncClients;el('logoutBtn').onclick=logout;el('loginBtn').onclick=()=>auth('login',el('loginBtn'));el('registerBtn').onclick=()=>auth('register',el('registerBtn'));el('bindBtn').onclick=bind;el('uploadBtn').onclick=upload;home();
</script></body></html>"""
}
