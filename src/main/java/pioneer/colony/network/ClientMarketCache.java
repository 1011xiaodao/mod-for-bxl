package pioneer.colony.network;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 贸易站 GUI 的客户端只读快照。 */
public final class ClientMarketCache {
    public static volatile ColonyMarketPayload latest;

    private ClientMarketCache() {
    }

    public static void handle(ColonyMarketPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> latest = payload);
    }
}
