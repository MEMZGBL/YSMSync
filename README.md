# YSMSync — ModernYSM 兼容版

Yes Steve Model（YSM）服务端同步插件，**同一份 jar 可在 Minecraft 26.1.2 ~ 26.3** 的服务端上运行，
并让客户端使用 **ModernYSM（OpenYSM 2.6.x）** 模组时也能正常同步模型。

原项目：[memzgbl/ysmsync](https://github.com/memzgbl/ysmsync)（面向 Paper/Purpur 1.19.4）。

> 本分支在保留 YSMSync 原有模型管理、网页门户的基础上，
> 新增了一层 **ModernYSM 协议适配**，让使用开源 YSM 替代品（ModernYSM）的客户端
> 能与服务端完成握手、拉取模型、切换模型、播放额外动画。
> 完整的移植过程、协议细节和踩坑记录见 **[PORTING.md](PORTING.md)**。

---

## 重要：版本对应关系

| 组件 | 版本 |
| --- | --- |
| 服务端 | **Paper / Leaves 26.1.2 ~ 26.3**（实测 26.1.2 与 26.3 均可） |
| 服务端 Java | **Java 25** |
| 客户端 Minecraft | **26.1.2 ~ 26.3** |
| 客户端模组 | **ModernYSM 2.6.x**（复用官方包名 `rip.ysm.*`，`MOD_ID = yes_steve_model`） |
| 协议通道 | `yes_steve_model:2_6_0` |
| 插件版本 | **1.2.0-modernysm** |
| `api-version` | `26.1`（向下兼容，26.1.2 及以上的服务端都能加载） |

> 官方 Yes Steve Model 客户端目前最高只支持到较早的 Minecraft 版本，
> 26.3 客户端请使用开源的 **ModernYSM**。两者协议一致，插件对二者都能工作。

---

## 功能

- **模型同步**：客户端进服后自动完成加密握手、拉取模型目录、分块下载模型（带限速）
- **模型切换**：客户端在选择界面点选后立即生效，并广播给其他玩家
- **额外动画**：客户端按 Z / 动画轮盘触发的动作会在所有玩家客户端上播放
- **网页门户**：在浏览器里上传、授权、使用模型，终末地（Endfield）风格界面，支持深浅配色
- **模型上传**：网页端可批量上传 `.ysm`，服务端解析校验后立即加入同步
- **限速控制**：可按单玩家与全局限制同步带宽

---

## 安装

1. 把 `YSMSync-1.2.0-modernysm.jar` 放进服务端 `plugins/` 目录
2. 启动服务端（JDK 25）
3. 确认日志出现：

```
[YSMSync] ModernYSM protocol support enabled (channel: yes_steve_model:2_6_0).
[YSMSync] All models has been loaded! Currently has 6 models loaded.
[YSMSync] YSM web portal: http://0.0.0.0:25584
```

4. 把 `.ysm` 模型放进 `plugins/YSMSync/models/custom/`，或者直接从网页门户上传

玩家进服后应看到：

```
[YSMSync] Player <uuid> starting ModernYSM crypto handshake.
[YSMSync] Player <uuid> completed crypto handshake; sending catalog.
[YSMSync] Player <uuid> requested 6 model(s); queued 6 for transfer.
```

---

## Web 门户

网页门户默认监听 `25584`，用于在浏览器里上传、授权和使用模型。界面是终末地（Arknights: Endfield）风格：
单强调色 `#FFFA00`、切角按钮、细线分区、宽体英文标签配粗体中文，支持深浅两套配色（右下 `◐` 手动切换，
或跟随系统 `prefers-color-scheme`）。

页面标题可以在配置里改，会同时出现在浏览器标签、顶部命令栏和首屏字标上：

```yaml
web-portal:
  # 留空或删掉该行则回退为 "YSMSync 模型中心"
  title: "YSMSync 模型中心"
```

改完执行 `/ysmsync reload` 即可生效，不必重启服务器（页面模板在内置资源里，标题每次请求时读取配置）。
页面资源位于 `src/main/resources/web/index.html`，纯静态单文件，不依赖任何外部 CDN、字体或网络请求。

效果图见 `docs/screenshots/`：`portal-light.png`、`portal-dark.png`、`portal-admin.png`、`portal-phone.png`。

---

## 配置要点

`plugins/YSMSync/config.yml`：

```yaml
model-sync:
  per-player-rate-limit-mbps: 8.0    # 单玩家同步带宽上限，0 = 不限
  global-rate-limit-mbps: 40.0       # 全服总带宽上限，0 = 不限

model-defaults:
  model-id: "default"                # 玩家模型失效时的回退模型
  texture: "default"

web-portal:
  title: "YSMSync 模型中心"
  enabled: true
  host: "0.0.0.0"
  port: 25584
  sync-check-interval-seconds: 30
  max-upload-bytes: 16777216
  admin-users:                       # 允许管理（含删除）门户模型的账户
    - admin
```

改完执行 `/ysmsync reload`（会同时重载 `config.yml` 与模型缓存）。

---

## 命令

| 命令 | 说明 |
| --- | --- |
| `/ysmsync reload` | 重载配置与模型缓存 |
| `/ysmsync setmodel <玩家> <模型ID> [贴图]` | 设置指定玩家的模型 |
| `/ysmsyncweb bind <绑定码>` | 在游戏内绑定网页账户 |

---

## 常见问题

### 端口 25584 被占用

插件会打印：

```
[YSMSync] 无法在 0.0.0.0:25584 启动 YSM Web 门户（Address already in use）。
          模型同步功能不受影响，如需网页管理请修改 config.yml 的 web-portal.port，
          或设置 web-portal.enabled=false 关闭该提示。
```

**模型同步照常工作**，只是网页管理不可用。换端口或关闭即可：

```yaml
web-portal:
  port: 25585
  # enabled: false
```

### 客户端进服崩溃 `ByteArray with size 51 is bigger than allowed 7`

这是 payload 长度前缀缺失导致的老问题，1.2.0 已修复（见 PORTING.md 坑 1）。
如果仍出现，确认服务端跑的是 1.2.0 及以上版本。

### 按 Z 键动作不播放

1.2.0 起已实现 C2S 7 → S2C 21 的动画广播。日志里应能看到：

```
[YSMSync] Player xxx requested animation 'extra6' (index 6, category '').
```

如果打印的是 `which is out of range for model default`，说明当前用的模型
（通常是 `default`）没有那么多额外动画，换一个有更多动画的模型再试。

---

## 从源码构建

需要 **JDK 25** 和 Gradle 9.x。

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
export PATH=$JAVA_HOME/bin:$PATH
gradle build --no-daemon --console=plain
```

产物：`build/libs/YSMSync-1.2.0-modernysm.jar`（shadow 打包，含依赖）。

依赖说明：

- `paper-api:26.1.2.build.74-stable` 用 `compileOnly` —— **编译基线刻意压在 26.1.2**，
  这样产物能同时跑在 26.1.2 和 26.3 上
- 服务端 NMS jar（`paper-server-*.jar` / `leaves-server-*.jar`）用 `fileTree` 匹配，
  放哪个版本都能编译
- `ysm-core-pure.jar`（YSM 1.20.1 剥离出的纯逻辑类，含 `YsmCrypt` / 模型解析）用 `implementation`

### 为什么一份 jar 能跨 26.1.2 / 26.3

本插件只用到了 `DiscardedPayload` / `ClientboundCustomPayloadPacket` / `ServerboundCustomPayloadPacket` /
`Identifier` / `Connection` 这几个 NMS 类。逐一核对过两个版本的字节码：

- **类名与成员签名完全一致**（`DiscardedPayload(Identifier, byte[])`、`id()`、`data()` 等）
- **codec 的写入行为完全一致**（都是裸 `writeBytes` / `readBytes`，没有版本差异）
- Netty 只用 `ByteBuf` / `Channel` / `ChannelPipeline` 等基础 API，4.2.7 与 4.2.16 都具备

因此不需要运行时版本判断或反射兜底。实测：26.1.2 编译的 jar 放到 26.3 服务端，
插件加载、加密握手、模型同步、模型切换、按 Z 播放动画全部正常。

---

## 许可与致谢

- 原插件： [memzgbl/ysmsync](https://github.com/memzgbl/ysmsync)
- 协议与模型格式参考： [ModernYSM](https://github.com/hongshaoluobotou/ModernYSM)（OpenYSM）
- 网页界面设计语言参考：终末地（Arknights: Endfield）风格规范
