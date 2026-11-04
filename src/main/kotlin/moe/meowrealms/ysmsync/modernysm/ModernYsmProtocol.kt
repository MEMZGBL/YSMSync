package moe.meowrealms.ysmsync.modernysm

/**
 * ModernYSM / OpenYSM 2.6.x 服务端协议常量。
 *
 * 这些值来自 OpenYSM 源码 `com.elfmcys.yesstevemodel.network.NetworkHandler`
 * 与 `rip.ysm.api.network.YSMChannel` 的注册表，是客户端硬编码的线上契约，
 * 不可随意更改。
 *
 * 说明：
 * - 通道名与官方 YSM 2.6.0 完全一致（`yes_steve_model:2_6_0`），
 *   因此本插件沿用原有通道即可，无需新增注册。
 * - 明文层每个包的首字节是 discriminator（包 ID）。
 * - 模型同步（01/02/03/04/05）走「加密容器」，即外层是包 ID 1/2，
 *   内层才是真正的包体，且带随机垃圾头（garbage header）。
 */
object ModernYsmProtocol {

    /** 协议版本，客户端按此字符串比对。 */
    const val VERSION = "2.6.0"

    /** 通道：namespace:path */
    const val CHANNEL = "yes_steve_model:2_6_0"

    /** 服务端声明支持「模型同步分片」的品牌串（可选，见 S2CVersionCheckPacket.decode）。 */
    const val BRAND_MODEL_SYNC_FRAGMENTS = "open_ysm:model_sync_fragments_v1"

    /** OpenYSM 变体品牌串（BPM 等衍生端使用，携带 allowUpload 布尔）。 */
    const val BRAND_OPEN_YSM = "open_ysm:v1"

    // ---- 明文层 discriminator ----
    object Packets {
        /** S2C 模型同步载荷（加密容器） */
        const val S2C_MODEL_SYNC_PAYLOAD = 1

        /** C2S 模型同步载荷（加密容器） */
        const val C2S_MODEL_SYNC_PAYLOAD = 2

        /** S2C 执行 Molang */
        const val S2C_EXECUTE_MOLANG = 3

        /** S2C 设置实体模型与贴图 */
        const val S2C_SET_MODEL_AND_TEXTURE = 4

        /** C2S 请求切换模型 */
        const val C2S_REQUEST_SWITCH_MODEL = 5

        /** S2C 同步已授权模型列表 */
        const val S2C_SYNC_AUTH_MODELS = 6

        /** C2S 播放动画 */
        const val C2S_PLAY_ANIMATION = 7

        /** S2C 同步收藏模型列表 */
        const val S2C_SYNC_STAR_MODELS = 8

        /** C2S 设置收藏模型 */
        const val C2S_SET_STAR_MODEL = 9

        /** C2S 完成反馈 */
        const val C2S_COMPLETE_FEEDBACK = 15

        /** S2C 同步投掷物模型 */
        const val S2C_SYNC_PROJECTILE_MODEL = 16

        /** C2S 请求执行 Molang */
        const val C2S_REQUEST_EXECUTE_MOLANG = 17

        /** C2S 同步动画表达式 */
        const val C2S_SYNC_ANIMATION_EXPRESSION = 18

        /** S2C 同步动画表达式 */
        const val S2C_SYNC_ANIMATION_EXPRESSION = 19

        /** S2C 同步玩家状态 */
        const val S2C_SYNC_PLAYER_STATE = 21

        /** S2C 同步载具模型 */
        const val S2C_SYNC_VEHICLE_MODEL = 22

        /** C2S 挥手 */
        const val C2S_SWING_ARM = 23

        /** S2C 版本检查 */
        const val S2C_VERSION_CHECK = 51

        /** C2S 版本检查 */
        const val C2S_VERSION_CHECK = 52

        /** C2S 模型上传开始 */
        const val C2S_MODEL_UPLOAD_START = 70

        /** S2C 模型上传开始应答 */
        const val S2C_MODEL_UPLOAD_START = 71

        /** C2S 模型上传分块 */
        const val C2S_MODEL_UPLOAD_CHUNK = 72

        /** C2S 模型上传结束 */
        const val C2S_MODEL_UPLOAD_FINISH = 73

        /** S2C 模型上传结果 */
        const val S2C_MODEL_UPLOAD_RESULT = 74
    }

    // ---- 加密容器内的包 ID ----
    object Inner {
        /** S2C 公钥交换 */
        const val PUBLIC_KEY_EXCHANGE = 0x01

        /** C2S 公钥交换应答（Pong） */
        const val PUBLIC_KEY_PONG = 0x02

        /** S2C 模型目录 */
        const val CATALOG = 3

        /** C2S 请求模型内容 */
        const val REQUEST_MODELS = 4

        /** S2C 模型文件分块 */
        const val MODEL_CHUNK = 5
    }

    /** 单个模型分块的最大载荷（与官方实现一致）。 */
    const val MAX_CHUNK_BYTES = 32_000

    /** 垃圾头长度范围：16 ~ 63 字节。 */
    const val GARBAGE_MIN = 16

    /** 垃圾头长度附加随机量。 */
    const val GARBAGE_RANDOM_RANGE = 48

    /** 密钥长度（YSM 固定使用 56 字节）。 */
    const val KEY_LENGTH = 56

    /** 客户端缓存的默认密钥种子（与 OpenYSM 一致，保证跨会话稳定）。 */
    const val CLIENT_KEY_SEED = 114514L
}
