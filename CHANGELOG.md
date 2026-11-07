# 更新日志

本文件记录 YSMSync 的版本变更。移植相关的协议细节与踩坑过程见 [PORTING.md](PORTING.md)。

---

## 1.2.1

本次主要解决「能用但不好用」的一批问题：内置模型、图标显示、重复同步、进服模型丢失。

### 新增

- **内置官方模型，提供两个产物**

  内置模型体积差异很大，拆成两套，**代码完全一致，只有资源不同**：

  | 产物 | 大小 | 内置模型 |
  | --- | --- | --- |
  | `YSMSync-1.2.1.jar` | 约 3.5 MB | `misc`（杂项）4 个 |
  | `YSMSync-1.2.1-full.jar` | 约 23 MB | `misc` 4 个 + `wine_fox`（酒狐）22 个 |

  资源取自 ModernYSM / OpenYSM 的 `assets/yes_steve_model/builtin/`，
  首次启动自动释放到 `plugins/YSMSync/models/builtin/`。
  `misc` 放 `src/main/resources`，酒狐单独放 `src/full/resources`，
  由独立的 `shadowJarFull` 任务打进 `-full` 产物。

- **下发资源包（ModelPack）元数据**
  现在会把 `ysm-pack.json` / `ysm-pack.png` 的内容发给客户端：资源包路径、
  封面图（PNG 字节 + 宽高 + 格式）、名称描述、多语言表。

### 修复

- **模型选择界面图标显示成异常色块**

  根因不是图片格式，而是服务端**从不下发资源包元数据**。原代码在 catalog 里写死：

  ```kotlin
  // 当前 YSMSync 不下发资源包元数据，发送 0 表示没有
  out.writeVarInt(0)
  ```

  客户端按资源包对模型分组，并从包的 `ysm-pack.png` 取封面图。
  模型没有归属的包，图标就显示成异常色块。
  现按客户端 `handlePacket03` 的完整读取顺序补齐下发。
  `ServerPackData` 里这些字段本来就有，只是一直没发出去。

- **内置模型解析失败（酒狐全部加载不出来）**

  酒狐贴图是 WebP 格式，而剥离版 `ysm-core-pure.jar` 不含解码器：

  ```
  NoClassDefFoundError: rip/ysm/imagestream/webp/WebpDecoder
    at YSMFolderDeserializer.parseImageMeta
  ```

  22 个酒狐模型全部解析失败，只加载出 23 个模型。
  引入 YSM 依赖的 ImageStream 库（`libs/imagestream.jar`，377 个类）后，
  35 个模型（32 模型 + 3 资源包）全部正常加载。

- **模型没变却反复触发同步**

  `WebPortalManager` 里有个 30 秒定时任务，无条件对所有在线玩家重启同步：

  ```kotlin
  Runnable { ClientConnectionManager.restartModelSynchronizationForConnectedPlayers() }
  ```

  而该方法会重走同步，并调用
  `validateAndCorrectModelSelection(DEFAULT_MODEL_ID, DEFAULT_MODEL_TEXTURE)` ——
  模型判定异常时还会把玩家重置成默认模型。
  结果是玩家进服缓存完成后仍会被莫名重新同步一次（点开模型分组时尤其明显）。

  改为比对模型指纹（所有模型 ID + hash1 + hash2 排序拼接），
  只有上传 / 删除 / 重载导致指纹变化时才触发重新同步，
  并对已握手的 ModernYSM 会话重新发起目录交换（`onCatalogChanged()`）。

- **玩家每次进服都变回默认模型**

  `onPlayerJoin` 只发版本检查并拉起握手，**从不把玩家保存的模型告诉任何人**，
  客户端自然一直停在 `default`。

  新增进服后延迟 40 tick 恢复：广播该玩家保存的模型给所有已握手玩家（含自己），
  并把其他在线玩家的模型推给刚进服的人。

- **切换模型后按 Z 用错动画表**

  `handleSwitchModel` 之前只广播、不写入 `PlayerData`，
  导致 `selectedModelId` 一直是旧值，按 Z 时用旧模型换算动画 index。已补上持久化。

### 变更

- 版本号 `1.2.0-modernysm` → `1.2.1`

---

## 1.2.0

首次支持 ModernYSM（OpenYSM 2.6.x）协议，并保持对 26.1.2 的兼容。

### 新增

- **ModernYSM 协议适配层**（`modernysm/`）

  | 文件 | 职责 |
  | --- | --- |
  | `ModernYsmProtocol.kt` | 通道名、包 ID 表、版本、分块大小、密钥长度 |
  | `ModernYsmSession.kt` | 单玩家握手状态机与模型分块下发 |
  | `ModernYsmManager.kt` | 会话表、进服发起、C2S 分发、限速传输、广播 |
  | `ModelManagerModelSource.kt` | 把现有模型数据喂给协议层 |
  | `Logs.kt` | 安全日志访问器 |

- **按 Z 键的额外动画**（C2S 7 → S2C 21）
  用模型动画表把 `animationIndex` 换算成动画名（支持 `category` 分类），
  经 `S2C 21`（`flags |= 2048` + `modelSwitchId`）广播；`index == -1` 时广播空串表示停止。

- **Web 门户界面重做**
  单强调色、切角按钮、细线分区、宽体英文配粗体中文，支持深浅配色。
  页面从 Kotlin 字符串抽离为 `src/main/resources/web/index.html`，
  不依赖任何外部 CDN / 字体 / 网络请求，内网离线可用。
  新增 `web-portal.title` 配置项，`/ysmsync reload` 即时生效。

- **跨版本兼容**：一份 jar 可在 26.1.2 ~ 26.3 的服务端运行
  编译基线锁在 `paper-api:26.1.2.build.74-stable`，`api-version` 写 `26.1`。
  两个版本的 `DiscardedPayload` 签名与 codec 行为（裸 `writeBytes` / `readBytes`）
  逐一核对过字节码，完全一致，因此不需要运行时版本判断。

- 新增 JUnit 5 测试（完整握手 + 9000 字节分块重组校验）

### 修复

- **客户端进服崩溃**

  ```
  DecoderException: ByteArray with size 51 is bigger than allowed 7
    at rip.ysm.api.network.impl.YsmRawPayload.lambda$static$1
  ```

  `DiscardedPayload` 编码用裸 `writeBytes`，不带长度前缀；
  客户端 `YsmRawPayload` 解码用 `readByteArray()`，要求 VarInt 前缀。
  两者不匹配时客户端会把包 ID（`51`）当成数组长度。
  发送时补前缀，接收时按「VarInt 值 == 剩余字节数」自动剥壳。

- **握手静默失败**：原实现是「收到 C2S 52 才发 01」，与官方相反。
  官方由服务端在玩家进服后主动下发 01，收到 52 只记录版本。
  顺序反了会导致客户端连发探测包时收到多个 01 互相覆盖。

- **模型切换广播漏掉自己**：广播 `S2C 4` 时排除了发起者，
  导致别人能看到你的模型变化、自己看不到。客户端依赖该包更新本地玩家模型。

### 变更

- `api-version` 由 `26.1` 起步（向下兼容），不再写死 26.3 以上

---

## 1.0.2-paper26.1.2

- 剥离 YSM 模组依赖为独立的 `ysm-core-pure.jar`
- 去掉 ProtocolLib，改用原生 Netty 负载桥
- 去掉 MorePaperLib，改用 Paper 原生调度器
- 适配 26.x 的 `Identifier` 与 Java 25 字节码

详见 [PORTING-26.1.2.md](PORTING-26.1.2.md)。

---

## 已知限制

- 女仆（TouhouLittleMaid）实体的动画轮盘未接管（`C2S 7` 的 `entityId != -1` 直接忽略）
- Molang 变量同步（`S2C 21` 的其他 flag）未实现
- 部分控制包未实现：6 / 8 / 9 / 16 / 17 / 19 / 22 / 23、70–74 上传链路
- 新玩家进服时尚未主动推送「所有在线玩家的当前模型」，靠 `S2C 4` 逐步补齐
- `payloadWrapped` 尚未暴露为配置项（更换发裸字节的客户端实现时才需要）
