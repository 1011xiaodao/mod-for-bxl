package pioneer.colony.colony;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import pioneer.colony.PioneerColony;
import pioneer.colony.config.Config;
import pioneer.colony.economy.EconomyTicker;
import pioneer.colony.economy.OfflineSummary;

/**
 * 殖民地生命周期与调度入口：经济 tick 降频调度（60s）、创建校验、登录推送离线汇总。
 */
public final class ColonyManager {
    /** 保留给控制台/无主调试殖民地的特殊 owner（0-0）。 */
    public static final UUID DEBUG_OWNER = new UUID(0L, 0L);

    private static long serverTickCounter = 0;

    private ColonyManager() {
    }

    /** 每服务端 tick 调用；按配置周期触发经济结算（降频纪律：60s 一次）。 */
    public static void onServerTick(MinecraftServer server) {
        serverTickCounter++;
        int intervalTicks = Math.max(1, Config.ECONOMY_TICK_SECONDS.get()) * 20;
        if (serverTickCounter % intervalTicks == 0) {
            EconomyTicker.settleAll(server, false);
        }
    }

    /**
     * 创建殖民地：维度限定主世界、每玩家上限校验、免费 3×3 领地、启动资金与初始人口、总部建筑。
     * 返回 null = 校验未过（调用方负责反馈）。
     */
    public static Colony createColony(MinecraftServer server, UUID owner, String name, ServerLevel level,
                                      int blockX, int blockZ) {
        ColonySavedData data = ColonySavedData.get(server);
        if (data.countOfOwner(owner) >= Config.COLONIES_PER_PLAYER.get()) {
            return null;
        }
        Colony colony = new Colony(UUID.randomUUID(), owner, name,
                level.dimension().location().toString(), blockX, blockZ);
        colony.adjustCredits(Config.STARTING_CREDITS.get());
        colony.setPopulation(Config.INITIAL_POPULATION.get());
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        colony.setHqChunk(ChunkPos.asLong(chunkX, chunkZ));
        // 免费领地：总部周围 3×3（06 §4）
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                colony.claimChunk(colony.getDimension(), chunkX + dx, chunkZ + dz);
            }
        }
        // 总部（市政厅）1 级自动就位；真实建造流程（地基/施工）M6.2 接入
        BuildingDefinitions.get("hq").ifPresent(def ->
                colony.addBuilding(new BuildingInstance("hq", 1, colony.getHqChunk())));
        data.add(colony);
        PioneerColony.LOGGER.info("[殖民地经营] 殖民地[{}]创建：owner={} 位置={} ({}, {})",
                name, owner, colony.getDimension(), blockX, blockZ);
        return colony;
    }

    public static Colony colonyOf(MinecraftServer server, UUID player) {
        return ColonySavedData.get(server).colonyOf(player);
    }

    /** 控制台场景下的目标殖民地（调试无主殖民地）。 */
    public static Colony debugColony(MinecraftServer server) {
        return ColonySavedData.get(server).colonyOf(DEBUG_OWNER);
    }

    /** 总督上线：若有未领取的离线汇总则推送（推送即清除，防重复）。 */
    public static void onOwnerLoggedIn(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        Colony colony = ColonySavedData.get(server).colonyOf(player.getUUID());
        if (colony == null) {
            return;
        }
        OfflineSummary summary = colony.pollPendingSummary();
        if (summary == null) {
            return;
        }
        for (var line : summary.displayLines()) {
            player.sendSystemMessage(line);
        }
    }
}
