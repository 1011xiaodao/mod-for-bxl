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
        // 市场价目表（威胁值物资估值 + M6.7 市场共用）
        event.addListener(new pioneer.colony.market.MarketPrices());
        // 袭击波次配置（5 档）
        event.addListener(new pioneer.colony.raid.RaidConfigs());
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        // 袭击怪/市民参战实体孤儿自清（服务器重启后无活跃袭击即清除）
        if (!event.getLevel().isClientSide) {
            pioneer.colony.raid.RaidManager.cleanupOrphan(event.getEntity());
            // 常驻市民注册表（区块加载 → join 事件重建内存名册）
            pioneer.colony.citizen.CitizenManager.onEntityJoin(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide) {
            pioneer.colony.citizen.CitizenManager.onEntityLeave(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        pioneer.colony.citizen.CitizenManager.onServerStopped();
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
