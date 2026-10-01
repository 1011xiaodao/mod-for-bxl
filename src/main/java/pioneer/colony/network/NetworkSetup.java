package pioneer.colony.network;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import pioneer.colony.PioneerColony;

/** 网络注册（M6.3：研究页数据推送，服务端→客户端单向）。 */
@EventBusSubscriber(modid = PioneerColony.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class NetworkSetup {
    private NetworkSetup() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(ColonyResearchPayload.TYPE, ColonyResearchPayload.STREAM_CODEC,
                ClientResearchCache::handle);
    }
}
