# YSMSync — Leaves/Paper 26.3 × ModernYSM 版

Yes Steve Model（YSM）服务端同步插件，适配 **Minecraft 26.3** 服务端，
并让客户端使用 **ModernYSM（OpenYSM 2.6.x）** 模组时也能正常同步模型。

原项目：[memzgbl/ysmsync](https://github.com/memzgbl/ysmsync)（面向 Paper/Purpur 1.19.4）。

> 本分支在保留 YSMSync 原有模型管理、网页门户的基础上，
> 新增了一层 **ModernYSM 协议适配**，让使用开源 YSM 替代品（ModernYSM）的客户端
> 能与服务端完成握手、拉取模型、切换模型、播放额外动画。
> 完整的移植过程、协议细节和踩坑记录见 **[PORTING.md](PORTING.md)**，
> 版本变更历史见 **[CHANGELOG.md](CHANGELOG.md)**。

---

## 重要：版本对应关系

| 组件 | 版本 |
| --- | --- |
| 服务端 | **Leaves 26.3**（Paper 系，`protocol_version = 777`） |
| 服务端 Java | **Java 25**（必须，paper-api 26.3 是 Java 25 字节码） |
| 客户端 Minecraft | **26.3** |
| 客户端模组 | **ModernYSM 2.6.x**（复用官方包名 `rip.ysm.*`，`MOD_ID = yes_steve_model`） |
| 协议通道 | `yes_steve_model:2_6_0` |
| 插件版本 | **1.2.1** |

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

## 两个构建产物

内置模型体积差别较大，所以提供两个产物，**代码完全一致，只有内置模型资源不同**：

| 产物 | 大小 | 内置模型 | 适用 |
| --- | --- | --- | --- |
| `YSMSync-1.2.1.jar` | 约 3.5 MB | `misc`（杂项）4 个 | 体积敏感，或模型都由玩家自己上传 |
| `YSMSync-1.2.1-full.jar` | 约 23 MB | `misc` 4 个 + `wine_fox`（酒狐）22 个 | 想开箱即用全套官方内置模型 |

内置模型会在首次启动时自动释放到 `plugins/YSMSync/models/builtin/`，
也可以在这个目录里直接增删模型（改成 `.ysm` 文件放进 `models/custom/` 亦可）。

> 内置资源均来自 ModernYSM / OpenYSM 的 `assets/yes_steve_model/builtin/`。
> 酒狐模型含 WebP 贴图，需要插件内置的 ImageStream 解码库支持（已打包，无需额外安装）。

---

## 安装

1. 把 `YSMSync-1.2.1.jar`（或 `-full` 全量版）放进服务端 `plugins/` 目录
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

这是 payload 长度前缀缺失导致的老问题，1.2.1 已修复（见 PORTING.md 坑 1）。
如果仍出现，确认服务端跑的是 1.2.1 及以上版本。

### 模型选择界面里图标显示成异常色块

服务端需要下发「资源包（ModelPack）」元数据，客户端才会把模型归到对应分组并显示封面图标。
1.2.1 起已正常下发 `ysm-pack.json` / `ysm-pack.png` 的内容（包括名称、描述、多语言和封面图）。
如果仍有异常，确认服务端日志里没有 `Failed to walk directory` —— 出现该错误通常意味着
内置模型里的 WebP 贴图缺少解码支持（1.2.1 已内置 ImageStream 解码库）。

### 每次进服都显示"同步模型"

这是协议本身的正常流程：客户端每次进服都要重新握手、比对目录。**只要模型没变就不会重新下载**，
日志里不会出现 `requested N model(s)`；出现这一行才说明真的在传输。

### 按 Z 键动作不播放

1.2.1 起已实现 C2S 7 → S2C 21 的动画广播。日志里应能看到：

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

产物：`build/libs/YSMSync-1.2.1.jar`（shadow 打包，含依赖）。

依赖说明：`paper-api:26.3.build.9-alpha` 用 `compileOnly`，
`ysm-core-pure.jar`（YSM 1.20.1 剥离出的纯逻辑类，含 `YsmCrypt` / 模型解析）用 `implementation`。

---

## 许可与致谢

- 原插件： [memzgbl/ysmsync](https://github.com/memzgbl/ysmsync)
- 协议与模型格式参考： [ModernYSM](https://github.com/hongshaoluobotou/ModernYSM)（OpenYSM）
- 网页界面设计语言参考：终末地（Arknights: Endfield）风格规范
