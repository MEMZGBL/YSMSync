# YSMSync — Paper 26.1.2 移植说明

本项目是 [memzgbl/ysmsync](https://github.com/memzgbl/ysmsync)（原面向 Paper/Purpur 1.19.4）向 **Paper 26.1.2** 的移植版本。

- 目标服务端：**Paper 26.1.2**（`26.1.2.build.74-stable`，Java 25，协议 775）
- 客户端模组：**Yes Steve Model 26.1.2**（协议通道 `yes_steve_model:2_6_0`，未变）
- 成品：`build/libs/YSMSync-1.0.2-paper26.1.2.jar`（构建产物，不进仓库）

---

## 为什么是 26.1.2 而不是 26.2

YSM 客户端模组目前最高只支持到 **Minecraft 26.1.2**（模组元数据声明 `versionRange="[26.1,26.2)"`），
**26.2 没有任何可用的客户端版本**。服务端插件再新也无法与不存在的客户端握手，
因此本次移植目标定为 26.1.2 —— 这是当前能真正跑通 YSM 的最高版本。

---

## 主要改造点

### 1. 剥离 YSM 模组的服务端逻辑，做成独立依赖

原项目直接依赖 YSM 的 Forge 模组 jar（`libs/ysm-2.6.5-forge+mc1.20.1-all.jar`），
但这套代码：

- 1.21.1 起被**完全混淆**（只剩 `O0OoOoO0oo...` 这类类名）；
- 与 Forge / Minecraft 内部 API 强耦合，在纯 Paper 服务端无法加载。

做法是：对 **1.20.1 未混淆版本**做依赖闭包分析，把其中**不引用任何 Minecraft / Forge / Bukkit 类**
的 107 个类原样抽出，打包为 `libs/ysm-core-pure.jar`（0 个 MC 引用，已逐类校验）。
其中包含模型二进制解析、`YsmCrypt` 加密、zstd、AES/Deflate、POJO 与序列化逻辑。

原项目里有两个"看起来能用、实则不可用"的类，已按同样语义本地重写：

| 原引用 | 处理 |
| --- | --- |
| `ServerModelManager.ServerPackData` | → `model/ServerPackData.kt`（字段逐一复刻，纯数据） |
| `YSMClientMapper.buildModelInfo` | → `model/YsmModelInfoBuilder.kt`（按 CFR 反编译逐行移植，不碰 GeckoLib/Molang） |
| `YesModelUtils.getYsmCryptoVersion` / `input` | → `model/YsmLegacyReader.kt`（v1/v2 旧格式解密 + md5 校验） |

`ServerModelManager` 本身（163 处 MC 引用）与 `YSMClientMapper` 整体（渲染/GUI）不可复用，只用其数据映射部分。

### 2. 网络层：去掉 ProtocolLib，改用原生 Netty

1.19.4 时代 Forge 的 `SimpleChannel` 负载不会稳定转发给 Bukkit 的 `PluginMessageListener`，
所以原作者用了 ProtocolLib 兜底。26.x 下这个前提已经不成立：

- NeoForge 客户端改用标准 `RegisterPayloadHandlersEvent` 注册 payload；
- 服务端侧一切自定义负载都是 `CustomPacketPayload`。

因此删掉 ProtocolLib，改为 `network/PaperYsmPayloadBridge.java`：

- 在管线 **`decoder` 之后**插入 handler，直接收发 `ServerboundCustomPayloadPacket` / `ClientboundCustomPayloadPacket`；
- 未注册通道的负载由 `DiscardedPayload` 承载，用 `Identifier("yes_steve_model:2_6_0")` 匹配；
- 命中 YSM 通道即**消费该包**（不再下传），避免与 Bukkit 插件消息重复派发；
- 其余任何负载原样放行，不影响原版客户端与其他插件；
- 获取通道走 `CraftPlayer#getHandle()` → `ServerPlayer.connection.connection.channel`。

Bukkit 的 `PluginMessageListener` 作为兜底保留（`isBridged()` 为真时跳过，避免重复处理）。

### 3. 调度器：去掉 MorePaperLib

Paper 26.x 已把区域化调度器并入官方 API，因此：

- `morePaperLib.scheduling().entitySpecificScheduler(player)` → `Scheduling.entitySpecificScheduler(player)`
- `morePaperLib.scheduling().asyncScheduler()` → `Scheduling.asyncScheduler()`

`utils/Scheduling.kt` 是对 `io.papermc.paper.threadedregions.scheduler` 的薄封装，
保留了原有调用形状，并且**依然 Folia 兼容**（`folia-supported: true` 保留）。

### 4. 26.x API 适配

- `net.minecraft.resources.ResourceLocation` → **`Identifier`**（`Identifier.parse` / `fromNamespaceAndPath`）
- 插件描述文件修正：
  - `main` 原为 `moe.ysmsync.YSMSyncMain`（**类名错误，原项目即如此**）→ `moe.meowrealms.ysmsync.YSMSyncMain`
  - `api-version` → `'26.1'`
  - 移除 `depend: [ ProtocolLib ]`
- 编译目标提到 **Java 25**（paper-api 26.1.2 是 major 69 的字节码，无法用 Java 21 目标解析）

---

## 构建

前置：**JDK 25**、Gradle 9.x。paper-api 26.1.2 的字节码是 Java 25，JDK 21 无法解析。

```bash
# 1) 准备本地依赖（从你的服务端目录复制 NMS jar 与同版本 Netty）
#    注意：若文件放在 Android 存储（/sdcard）上，没有执行权限位，请用 bash 调起
bash prepare-libs.sh /path/to/paper-26.1.2.jar

# 2) 构建
JAVA_HOME=/path/to/jdk-25 gradle build

# 产物
build/libs/YSMSync-1.0.2-paper26.1.2.jar
```

`libs/` 说明：

| 文件 | 用途 | 是否打包进插件 |
| --- | --- | --- |
| `ysm-core-pure.jar` | YSM 纯数据逻辑（已内置） | ✅ 会被 shadow 合并 |
| `paper-server-26.1.2.jar` | 编译期访问 NMS | ❌ `compileOnly` |
| `netty/*.jar` | 编译期 Netty 4.2.7.Final | ❌ `compileOnly` |

---

## 实测验证记录

在真实 Paper 26.1.2（`26.1.2.build.74-stable`，Java 25）上验证通过：

| 项目 | 结果 |
| --- | --- |
| 插件加载 | ✅ 无异常，`Done` 正常 |
| 模型解析（crypto v2 旧格式） | ✅ `MT_Jake.ysm`、`PMO_Cuxu_v2.ysm` |
| 模型解析（crypto v3 新格式） | ✅ `Chinese-math-textbook_RuiRui_1.3.ysm` |
| 缓存生成 | ✅ 3 个 `.ysm` 全部生成缓存 |
| `/ysmsync reload` | ✅ `Reloaded models by command. Currently has 3 models loaded.` |
| Web 管理门户 | ✅ `http://0.0.0.0:25584` 返回 200 |
| **S2C 自定义负载** | ✅ 客户端收到 `yes_steve_model:2_6_0` 包 |
| **C2S 自定义负载** | ✅ 服务端日志 `Received handshake confirmed packet … Begin model synchronization` |
| 模型同步数据流 | ✅ 握手后客户端持续收到 7–22 个同步包 |

验证方式：自写的最小 Minecraft 协议客户端（protocol 775），走完
Handshake → Login → Configuration → Play，然后在 play 阶段收发自定义负载。

---

## 已修复的缺陷（v1.0.2 修复版）

### Web 门户端口占用导致整个插件被禁用

**现象**：服务端启动日志出现

```
java.net.BindException: 地址已在使用
    at ...moe.meowrealms.ysmsync.web.WebPortalManager.start(WebPortalManager.kt:40)
    at ...moe.meowrealms.ysmsync.YSMSyncMain.onEnable(YSMSyncMain.kt:139)
[YSMSync] Disabling YSMSync
```

随后玩家进服完全没有 YSM 握手，客户端提示"服务端 YSM 模组版本与客户端不同"、
模型不同步；`/ysmsync reload` 报 `IllegalPluginAccessException: Plugin attempted to register task while disabled`。

**根因**：`WebPortalManager.start()` 里的 `HttpServer.create()` 在端口 25584 被占用时抛
`BindException`，异常一路冒到 `onEnable`，Paper 因此把**整个插件禁用**——
模型同步、握手、命令全部失效。Web 门户只是可选附属功能，却拖垮了主功能。

**修复**：

- `WebPortalManager.start()` 内部捕获端口绑定失败，记录一条明确提示后继续运行；
- 即使 Web 门户启动失败，也照常注册"模型同步定时复查"任务；
- `YSMSyncMain.onEnable()` 再包一层 try/catch 兜底，确保任何门户异常都不会禁用插件；
- `web-portal.enabled=false` 时也会启动同步复查任务（此前会直接 return，跳过复查）。

**验证**：人为占用 25584 后启动服务端，日志只有一条降级提示，
`Error occurred while enabling` 计数为 0，模型正常加载、握手与同步均可用。

---

## 已知限制

- **26.1.2 是 YSM 客户端支持的最高版本**，26.2 / 26.3 均无客户端可用。
- 26.x 的 Netty 管线结构、包 ID 都随版本变化。本项目按 26.1.2 的
  `GameProtocols` 注册表定位（play 阶段 C2S `CustomPayload` = `0x16`），
  跨小版本升级时需要重新核对。
- YSM 客户端 jar 从 1.21.1 起完全混淆。若未来协议版本号（当前 `2.6.0`）变化，
  `ysm-core-pure.jar` 里的解析逻辑需要重新对齐。

---

## 目录结构

```
src/main/java/moe/meowrealms/ysmsync/network/
  PaperYsmPayloadBridge.java      # 新增：原生 Netty 负载桥
src/main/kotlin/moe/meowrealms/ysmsync/
  model/ServerPackData.kt         # 新增：替代 ServerModelManager.ServerPackData
  model/YsmModelInfoBuilder.kt    # 新增：替代 YSMClientMapper.buildModelInfo
  model/YsmLegacyReader.kt        # 新增：替代 YesModelUtils
  utils/Scheduling.kt             # 新增：替代 MorePaperLib
libs/ysm-core-pure.jar            # 新增：从 YSM 模组剥离的 107 个纯逻辑类
```

其余文件保持原项目结构，仅做了依赖替换与 API 适配。

---

## 本次移植的实际验证环境

- Ubuntu 24.04 aarch64，GMK 容器
- JDK 25（`openjdk 25.0.4`）、Gradle 9.8.1、Kotlin 2.4.21
- Paper `26.1.2.build.74-stable`（真实 paperclip 打过补丁的服务端）
- 交付包已在一份干净副本上用 `bash prepare-libs.sh` + `gradle build` 完整复现构建
