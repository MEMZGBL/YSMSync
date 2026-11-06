# YSMSync × ModernYSM 移植说明（Minecraft 26.3 / Leaves）

本文记录把 YSMSync 的同步协议从「官方 YSM 自有协议」移植到 **ModernYSM（OpenYSM 2.6.x）** 的过程，
以及踩到的每一个坑。目标读者是后续维护者和想自己改的人。

- 插件版本：**1.2.1**
- 服务端：Leaves 26.3（Paper 系，`protocol_version = 777`），Java 25
- 客户端：Minecraft 26.3 + ModernYSM 2.6.x（`MOD_ID = yes_steve_model`）
- 通道：`yes_steve_model:2_6_0`

---

## 一、为什么需要移植

YSMSync 原本用自己的一套 payload 同步模型（`S2C*Packet` / `C2S*Packet`，见 `network/packet/`）。
ModernYSM 客户端只认官方通道 `yes_steve_model:2_6_0`，两套协议互不兼容，
所以要么改客户端，要么让服务端"说客户端能听懂的话"。本移植选择后者：
**保留 YSMSync 原有的模型管理、存储、网页门户，新增一层 ModernYSM 协议适配**。

适配层全部集中在 `src/main/kotlin/moe/meowrealms/ysmsync/modernysm/`：

| 文件 | 职责 |
| --- | --- |
| `ModernYsmProtocol.kt` | 通道名、包 ID 表、`VERSION`、分块大小、密钥长度等常量 |
| `ModernYsmSession.kt` | 单玩家握手状态机 + 目录/模型分块下发 |
| `ModernYsmManager.kt` | 会话表、进服发起、C2S 分发、限速传输、广播 |
| `ModelManagerModelSource.kt` | 把 YSMSync 的模型数据喂给协议层 |
| `Logs.kt` | 安全日志访问器（插件未就绪时不会 NPE） |

---

## 二、协议总览

### 2.1 两个层次

外层是**明文**的单字节 `discriminator`（包 ID），内层是加密容器：

```
线上 payload = [discriminator][body]
```

- `discriminator` 明文，用来分发到具体包类型
- 模型数据类包（01/02/03/04/05）的 `body` 是**密文**
- 握手完成后，模型数据用会话密钥加密

### 2.2 包 ID 表

| ID | 方向 | 名称 | 说明 |
| --- | --- | --- | --- |
| 1 | S2C | ModelSyncPayload | 公钥交换 / 握手（01） |
| 2 | C2S | ModelSyncPayload | 客户端应答（02） |
| 3 | S2C | ExecuteMolang | Molang 执行 |
| 4 | S2C | SetModelAndTexture | 设置实体模型与贴图 |
| 5 | C2S | RequestSwitchModel | 请求切换模型 |
| 6 | S2C | SyncAuthModels | 同步授权模型列表 |
| 7 | C2S | PlayAnimation | **播放额外动画（按 Z）** |
| 8 | S2C | SyncStarModels | 同步收藏模型 |
| 9 | C2S | SetStarModel | 设置收藏 |
| 15 | C2S | CompleteFeedback | 完成反馈 |
| 16 | S2C | SyncProjectileModel | 弹射物模型 |
| 17 | C2S | RequestExecuteMolang | 请求执行 Molang |
| 18/19 | C2S/S2C | SyncAnimationExpression | 动画表达式同步 |
| 21 | S2C | SyncPlayerState | 玩家状态（动画/飞行/血量等）|
| 22 | S2C | SyncVehicleModel | 载具模型 |
| 23 | C2S | SwingArm | 挥手 |
| 51 | S2C | VersionCheck | 版本检查 |
| 52 | C2S | VersionCheck | 版本上报 |
| 70–74 | C2S/S2C | 模型上传 | 网页上传链路 |

### 2.3 握手流程

**官方实现是"服务端主动"发起**，这一点最容易搞错（我曾照着别的实现做成"等客户端 52 再发 01"，导致客户端连发三次 52 探测、服务端连发三个 01 互相覆盖）：

```
服务端                                    客户端
  │  玩家进服，延迟 20 tick                  │
  │──── 51 VersionCheck("2.6.0") ─────────→│
  │──── 01 公钥交换（密文，含 garbage+0x01）→│
  │                                         │ 解密 01，取末尾 56 字节 = key1
  │                                         │ syncStep = 2
  │←─── 02 应答（密文，garbage+0x02 0x00）──│
  │ 解密 02，取末尾 56 字节 = clientNextKey   │
  │──── 03 目录（clientNextKey 加密）──────→│
  │←─── 04 请求模型（逐个 hash）───────────│
  │──── 05 模型分块（key1 加密）───────────→│
  │                                         │ 校验并写缓存，完成
```

关键点：

- 客户端 `syncStep` 初始为 1；收到 01 → 2；收到 03 → 3
- 客户端靠 `syncStep` 决定用哪把密钥解密，**顺序错了就静默失败**（解密返回 null，不报错）
- 服务端收到 52 只记录版本，不发 01（官方 `C2SVersionCheckPacket.handle` 的行为）

### 2.4 密钥体系

`YsmCrypt`（XChaCha20 + CityHash + MT19937）：

- `publicKey` 是 56 字节硬编码常量，官方与 ModernYSM **完全一致**
- `encrypt(data, key, isPublicKey)` 返回 `EncryptedPacket(data, nextKey)`
- 解密时明文末尾会追加 56 字节的 `nextKey`，接收方取末尾 56 字节作为下一把密钥

客户端缓存键：`new byte[56]` 用 `new Random(114514).nextBytes(key)` 生成；
模型缓存文件名：`String.format("%016x%016x", hash1, hash2)`（32 位十六进制）。

**已验证**：用 1.20.1 剥离版 `YsmCrypt` 加密的数据，交给编译出来的 ModernYSM 2.6.x 解密，
正文与 `nextKey` 完全一致 —— 算法层没有差异，排除了加解密不兼容的可能。

---

## 三、踩过的坑（按发现顺序）

### 坑 1：`DiscardedPayload` 的编码和客户端期望不一致（最致命）

**现象**：客户端一进服就崩溃

```
DecoderException: ByteArray with size 51 is bigger than allowed 7
  at rip.ysm.api.network.impl.YsmRawPayload.lambda$static$1
```

**原因**：Minecraft 26.3 的 `DiscardedPayload` 编码器用裸 `writeBytes(data)`，
**不带长度前缀**；而客户端的 `YsmRawPayload` 解码用 `readByteArray()`，
**要求 VarInt 长度前缀**。于是客户端把第一个字节（包 ID `51`）当成了数组长度。

**修复**：发送前自己补 VarInt 长度前缀，接收时按「VarInt 值 == 剩余字节数」的判据自动剥壳。

```kotlin
// 发送：VarInt(len) + data
private fun wrapPayload(data: ByteArray): ByteArray

// 接收：如果首字节解出的长度恰好等于剩余字节数，说明带前缀，剥掉
private fun unwrapPayload(raw: ByteArray): ByteArray
```

配置项 `web-portal.*` 之外没有暴露，但代码里保留 `payloadWrapped` 开关，
换成发送裸字节的客户端实现时可以关掉。

### 坑 2：握手方向搞反

见 2.3。改成服务端主动发 01 后，客户端日志立刻出现：

```
[YSM] Exchanged Key1. Preparing to send Packet 02.
```

### 坑 3：`YsmRawPayload` 与各包 codec 的分层关系

容易误以为"每个包都要加长度前缀"。实际是**两层**：

1. 外层：整个通道的 payload 类型 `YsmRawPayload` 用 `readByteArray()` → 需要 VarInt 前缀
2. 内层：`dispatch()` 读掉 discriminator 后，各包 codec 用 `readBytes(readableBytes())` → **裸字节**

所以只在外层加一次前缀，内层不要重复加。

### 坑 4：`S2CModelSyncPayload` 是裸字节

确认过它的 `encode` / `decode`：

```java
public static void encode(S2CModelSyncPayload m, FriendlyByteBuf buf) {
    buf.writeBytes(m.data.duplicate());          // 裸字节
}
public static S2CModelSyncPayload decode(FriendlyByteBuf buf) {
    ByteBuffer data = ByteBuffer.allocateDirect(buf.readableBytes());
    buf.readBytes(data);                          // 读剩余全部
}
```

因此 `[discriminator][密文]` 的构造是正确的。

### 坑 5：模型切换广播要包含自己

最初广播 S2C 4 时写了 `if (viewer == source) continue`，结果**别人能看到你的模型变化，你自己看不到**。
客户端靠这个包更新本地玩家模型，必须回发给自己。

### 坑 6：按 Z 键的动画完全没有实现

**现象**：进服后按 Z，动作不播放。

**链路**（之前 C2S 7 被当成"暂未处理"直接吞掉）：

```
客户端按 Z → C2S 7 PlayAnimation(animationIndex, category, entityId)
服务端     → 用模型动画表把 index 换成动画名
           → S2C 21 SyncPlayerState(entityId, flags |= 2048, modelSwitchId)
客户端     → flags & 2048 → cap.requestModelSwitch(动画名)
```

`entityId == -1` 表示玩家自己（`entityId != -1` 官方只用于女仆兼容，这里忽略）；
`animationIndex == -1` 表示**停止**当前额外动画（`modelSwitchId` 传空串）。

另一处连带问题：`handleSwitchModel` 只广播**不存储**，
`PlayerData.selectedModelId` 一直是旧值，导致按 Z 时用错模型的动画表换算 index。
已补上持久化。

### 坑 7：`YSMByteBuf.close()` 会自己 release

单元测试里 `finally { inner.release() }` 导致二次释放：

```
io.netty.util.IllegalReferenceCountException: refCnt: 0, decrement: 1
```

`YSMByteBuf.close()` 内部已经 `release()`，外层不要再释放。

### 坑 8：编译期的几个坑

- `leaves-server-26.3.jar` 只有 `org/bukkit/craftbukkit/`（NMS），**不含 Bukkit API**；
  `leaves-api` 又缺 adventure/slf4j/fastutil 传递依赖。最终用 Maven 的
  `io.papermc.paper:paper-api:26.3.build.9-alpha` 做 `compileOnly`。
- Kotlin 里 `0x99AABBCCDDEEFF00L` 会报 `Value out of range`（超 Long 范围）。
- 把类名当表达式赋值会报 `Classifier 'X' does not have a companion object`，应直接调静态方法。

---

## 四、26.3 环境要点

| 项 | 值 |
| --- | --- |
| `protocol_version` | 777 |
| `world_version` | 5023 |
| Java | 25 |
| Gradle | 9.8.2 |
| paper-api | `26.3.build.9-alpha` |
| `plugin.yml` | `api-version: '26.3'` |

构建：

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
export PATH=$JAVA_HOME/bin:$PATH
gradle build --no-daemon --console=plain
```

产物：`build/libs/YSMSync-1.2.1.jar`（shadow，约 2.6 MB，含内置 misc 模型资源）

---

## 五、测试

- `src/test/kotlin/.../ModernYsmSessionTest.kt`：完整握手 + 9000 字节分块重组校验
- `protocol-test/HandshakeSimulation.java`：01/02/03/05 全流程加解密自洽性验证
- `crosscheck/`：1.20.1 与 2.6.x 两套 `YsmCrypt` 的交叉加解密验证

跑法：`gradle test`（JUnit 5）。

---

## 六、已知限制 / 后续可做

- 女仆（TouhouLittleMaid）实体的动画轮盘未接管（`entityId != -1` 直接忽略）
- 部分控制包（6/8/9/15/16/17/18/19/22/23、70–74 上传链路）只做了最小处理或未实现
- 新玩家进服时，尚未把"其他在线玩家当前用的模型"主动推给他（靠 S2C 4 广播逐步补齐）
- Molang 变量同步（S2C 21 的其他 flag）未实现
