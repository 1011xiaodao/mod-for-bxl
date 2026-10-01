package pioneer.colony;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import pioneer.colony.colony.BuildingDefinitions;
import pioneer.colony.colony.ColonyManager;
import pioneer.colony.command.ColonyCommands;

/** 游戏总线事件注册。 */
@EventBusSubscriber(modid = PioneerColony.MODID)
public final class GameEvents {
    private GameEvents() {
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new BuildingDefinitions());
        // 研究桩加载器（与 [研究台] 目录格式一致；桩未启用时仅空载）
        event.addListener(new pioneer.colony.research.StubResearchSystem.DefLoader());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        ColonyCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ColonyManager.onServerTick(event.getServer());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ColonyManager.onOwnerLoggedIn(player);
        }
    }
}
