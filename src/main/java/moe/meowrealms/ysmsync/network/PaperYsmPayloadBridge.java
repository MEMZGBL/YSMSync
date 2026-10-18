package moe.meowrealms.ysmsync.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 在 Netty 管线中收发 YSM 自定义负载（Paper / Minecraft 26.1.2）。
 *
 * <p>26.x 的自定义负载已经全面 record 化：
 * <ul>
 *   <li>网络包：{@link ServerboundCustomPayloadPacket} / {@link ClientboundCustomPayloadPacket}</li>
 *   <li>负载载体：{@link CustomPacketPayload}，未注册通道统一由 {@link DiscardedPayload} 承载</li>
 *   <li>命名空间标识：{@link Identifier}（26.x 中由 ResourceLocation 更名而来）</li>
 * </ul>
 *
 * <p>本类只接管 YSM 自己那一个通道：命中后即消费该网络包，避免与 Bukkit 的
 * 插件消息广播重复投递；其余任何负载都原样放行，不影响原版客户端与其他插件。
 */
public final class PaperYsmPayloadBridge extends ChannelInboundHandlerAdapter {

    private static final String HANDLER_NAME = "ysmsync_ysm_payload_bridge";
    private static final String CHANNEL_NAME = "yes_steve_model:2_6_0";
    private static final Identifier CHANNEL_ID = Identifier.parse(CHANNEL_NAME);

    private static final Map<UUID, Channel> CHANNELS = new ConcurrentHashMap<>();
    private static final Set<UUID> BRIDGED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final Player player;

    private PaperYsmPayloadBridge(Player player) {
        this.player = player;
    }

    // ------------------------------------------------------------------
    // 安装 / 卸载
    // ------------------------------------------------------------------

    public static void inject(Player player) {
        Channel channel = channelOf(player);
        if (channel == null) {
            Bukkit.getLogger().warning("[YSMSync] 无法获取 " + player.getName() + " 的 Netty 通道，回退到 Bukkit 插件消息通道");
            return;
        }

        channel.eventLoop().execute(() -> {
            try {
                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline.get(HANDLER_NAME) != null) {
                    pipeline.remove(HANDLER_NAME);
                }
                if (pipeline.get("decoder") == null) {
                    Bukkit.getLogger().warning("[YSMSync] " + player.getName() + " 的连接管线缺少 decoder 处理器，回退到 Bukkit 插件消息通道");
                    return;
                }

                pipeline.addAfter("decoder", HANDLER_NAME, new PaperYsmPayloadBridge(player));
                CHANNELS.put(player.getUniqueId(), channel);
                BRIDGED.add(player.getUniqueId());
                Bukkit.getLogger().log(Level.FINE, "[YSMSync] 已安装原生负载桥: " + player.getName());
            } catch (Throwable throwable) {
                Bukkit.getLogger().log(Level.WARNING, "[YSMSync] 安装原生 YSM 负载桥接失败: " + player.getName(), throwable);
            }
        });
    }

    public static void remove(Player player) {
        BRIDGED.remove(player.getUniqueId());
        Channel channel = CHANNELS.remove(player.getUniqueId());
        if (channel == null) {
            return;
        }
        channel.eventLoop().execute(() -> {
            try {
                if (channel.pipeline().get(HANDLER_NAME) != null) {
                    channel.pipeline().remove(HANDLER_NAME);
                }
            } catch (Throwable ignored) {
                // 连接已关闭时管线可能已被销毁
            }
        });
    }

    /** 该玩家是否已由原生桥接管（是则 Bukkit 插件消息回调应跳过，避免重复处理）。 */
    public static boolean isBridged(Player player) {
        return BRIDGED.contains(player.getUniqueId());
    }

    // ------------------------------------------------------------------
    // 发送
    // ------------------------------------------------------------------

    /**
     * 以真正的 play 阶段 CustomPayload 包发送 YSM 数据。
     *
     * <p>用 {@link DiscardedPayload} 承载原始字节：服务端无需注册该通道，
     * 而装了 YSM 的客户端已在本地注册同名通道，会按自己的 codec 解码。
     */
    public static boolean sendClientboundPayload(Player player, byte[] payload) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        Channel resolved = CHANNELS.get(player.getUniqueId());
        if (resolved == null) {
            resolved = channelOf(player);
        }
        if (resolved == null || !resolved.isActive()) {
            return false;
        }
        final Channel channel = resolved;

        try {
            CustomPacketPayload body = new DiscardedPayload(CHANNEL_ID, payload);
            ClientboundCustomPayloadPacket packet = new ClientboundCustomPayloadPacket(body);

            channel.eventLoop().execute(() -> {
                if (channel.isActive()) {
                    channel.writeAndFlush(packet);
                }
            });
            return true;
        } catch (Throwable throwable) {
            Bukkit.getLogger().log(Level.WARNING, "[YSMSync] 发送原生 YSM 负载失败: " + player.getName(), throwable);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 接收
    // ------------------------------------------------------------------

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (message instanceof ServerboundCustomPayloadPacket packet) {
            byte[] payload = extractYsmPayload(packet);
            if (payload != null) {
                ClientConnectionManager.onNativeClientMessage(player, payload);
                // 已由本插件处理：不再下传，避免同一份数据被 Bukkit 再派发一次
                return;
            }
        }
        super.channelRead(context, message);
    }

    private static byte[] extractYsmPayload(ServerboundCustomPayloadPacket packet) {
        CustomPacketPayload body = packet.payload();

        if (body instanceof DiscardedPayload discarded) {
            // 服务端未注册该通道时的标准载体
            if (CHANNEL_ID.equals(discarded.id())) {
                return discarded.data();
            }
            return null;
        }

        // 若该通道被别的插件注册过，payload 可能是其它实现，按类型标识兜底判断
        if (body != null && CHANNEL_ID.equals(body.type().id())) {
            try {
                return body instanceof DiscardedPayload d ? d.data() : null;
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static Channel channelOf(Player player) {
        try {
            ServerPlayer handle = ((CraftPlayer) player).getHandle();
            Connection connection = handle.connection.connection;
            return connection.channel;
        } catch (Throwable throwable) {
            Bukkit.getLogger().log(Level.FINE, "[YSMSync] 定位玩家 Netty 通道失败: " + player.getName(), throwable);
            return null;
        }
    }
}
