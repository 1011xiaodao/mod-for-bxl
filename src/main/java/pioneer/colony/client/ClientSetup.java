package pioneer.colony.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import pioneer.colony.PioneerColony;
import pioneer.colony.registry.ModRegistry;

/** 客户端注册：菜单屏幕。 */
@EventBusSubscriber(modid = PioneerColony.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientSetup {
    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(ModRegistry.COLONY_CORE_MENU.get(), ColonyCoreScreen::new);
    }

    @SubscribeEvent
    public static void onRegisterRenderers(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModRegistry.CITIZEN_ENTITY.get(), CitizenRenderer::new);
    }
}
