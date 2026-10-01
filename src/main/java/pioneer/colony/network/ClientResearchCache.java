package pioneer.colony.network;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 研究页数据客户端缓存（common 类，不引用客户端类型，双端注册安全）。
 * 屏幕（client 包）读取 {@link #latest} 渲染。
 */
public final class ClientResearchCache {
    public static volatile ColonyResearchPayload latest = null;

    private ClientResearchCache() {
    }

    public static void handle(ColonyResearchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> latest = payload);
    }
}
