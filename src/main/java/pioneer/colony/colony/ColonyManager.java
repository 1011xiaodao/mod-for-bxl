package pioneer.colony.colony;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import pioneer.colony.PioneerColony;
import pioneer.colony.config.Config;
import pioneer.colony.construction.BlueprintGenerator;
import pioneer.colony.construction.ConstructionService;
import pioneer.colony.economy.EconomyTicker;
import pioneer.colony.economy.OfflineSummary;
import pioneer.colony.registry.ModRegistry;

/**
 * 殖民地生命周期与调度入口：经济 tick 降频调度（60s）、创建（含总部实体成形）、
 * 领地购买/退地、解散、登录推送离线汇总。
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
            if (pioneer.colony.research.ResearchIntegration.mode() == pioneer.colony.research.ResearchIntegration.Mode.STUB) {
                pioneer.colony.research.StubResearchSystem.tickServer(server);
            }
        }
        // 袭击系统每秒 tick（倒计时/开打条件/总部耐久/波次/结算）
        if (serverTickCounter % 20 == 0) {
            pioneer.colony.raid.RaidManager.tickServer(server);
            // 市民日程模拟每秒 tick（人口对账补生成/回收 + 日程通勤 + 就餐实扣，M6.5）
            pioneer.colony.citizen.CitizenManager.tickServer(server);
        }
    }

    /**
     * 创建殖民地：维度限定主世界、每玩家上限校验、免费 3×3 领地、启动资金与初始人口、
     * 总部（市政厅）1 级就地一次成形（创建即建，免料免费——创立行为本身）。
     * 返回 null = 校验未过（调用方负责反馈）。
     */
    public static Colony createColony(MinecraftServer server, UUID owner, String name, ServerLevel level,
                                      int blockX, int blockZ) {
        ColonySavedData data = ColonySavedData.get(server);
        if (data.countOfOwner(owner) >= Config.COLONIES_PER_PLAYER.get()) {
            return null;
        }
        if (level.dimension() != Level.OVERWORLD) {
            return null;
        }
        Colony colony = new Colony(UUID.randomUUID(), owner, name,
                level.dimension().location().toString(), blockX, blockZ);
        colony.adjustCredits(Config.STARTING_CREDITS.get());
        long refund = data.takeRefund(owner);
        if (refund > 0) {
            colony.adjustCredits(refund);
        }
        colony.setLastTickWallTime(System.currentTimeMillis());
        colony.setPopulation(Config.INITIAL_POPULATION.get());
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        colony.setHqChunk(ChunkPos.asLong(chunkX, chunkZ));
        // 免费领地：总部周围 3×3（06 §4）
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                colony.claimChunk(colony.getDimension(), chunkX + dx, chunkZ + dz, 0);
            }
        }
        // 总部实体成形：找地表高度，一次成形（免料免费）
        int groundY = level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
        BlockPos hqPos = new BlockPos(blockX, groundY, blockZ);
        BuildingDefinition hqDef = BuildingDefinitions.get("hq").orElse(null);
        BuildingInstance hq = new BuildingInstance("hq", 1, ChunkPos.asLong(hqPos.getX() >> 4, hqPos.getZ() >> 4));
        hq.setOrigin(hqPos.asLong());
        if (hqDef != null) {
            hq.setFootprint(hqDef.footprintW(), hqDef.footprintH(), hqDef.footprintD());
        }
        hq.setStatus(BuildingInstance.Status.ACTIVE);
        colony.addBuilding(hq);
        data.add(colony);

        if (hqDef != null) {
            level.setBlock(hqPos, ModRegistry.COLONY_CORE.get().defaultBlockState(), 3);
            if (level.getBlockEntity(hqPos) instanceof pioneer.colony.blockentity.ColonyCoreBlockEntity core) {
                core.configure(colony.uuid(), "hq", 1, hqDef.footprintW(), hqDef.footprintH(), hqDef.footprintD());
            }
            BlueprintGenerator.materialize(level, hqDef, 1, hqPos);
        }
        PioneerColony.LOGGER.info("[殖民地经营] 殖民地[{}]创建：owner={} 总部={}", name, owner, hqPos.toShortString());
        return colony;
    }

    // —— 领地购买 / 退地（06 §4） ——

    /** 第 N 块价格 = 基础价 × N（N = 含免费块的总持有数 + 1；免费块后首个购买为 N=10）。 */
    public static long nextChunkPrice(Colony colony) {
        return (long) Config.CHUNK_PRICE_BASE.get() * (colony.territorySize() + 1);
    }

    public static ConstructionService.OpResult buyChunk(ServerLevel level, Colony colony, int chunkX, int chunkZ) {
        String dim = colony.getDimension();
        if (!level.dimension().location().toString().equals(dim)) {
            return ConstructionService.OpResult.fail("只能在殖民地所在维度（主世界）购地。");
        }
        if (colony.ownsChunk(dim, chunkX, chunkZ)) {
            return ConstructionService.OpResult.fail("该区块已是你的领地。");
        }
        if (colony.territorySize() >= Config.TERRITORY_MAX_CHUNKS.get()) {
            return ConstructionService.OpResult.fail("已达领地上限 " + Config.TERRITORY_MAX_CHUNKS.get() + " 块。");
        }
        long now = System.currentTimeMillis();
        long cooldownMs = Config.PURCHASE_COOLDOWN_SECONDS.get() * 1000L;
        if (now - colony.getLastPurchaseWallTime() < cooldownMs) {
            return ConstructionService.OpResult.fail("购地冷却中（"
                    + (cooldownMs - (now - colony.getLastPurchaseWallTime())) / 1000 + " 秒）。");
        }
        if (!Config.ALLOW_ENCLAVE.get() && !colony.isAdjacentToTerritory(dim, chunkX, chunkZ)) {
            return ConstructionService.OpResult.fail("新购区块须与已有领地相邻（防飞地，可在配置开启 allowEnclave）。");
        }
        long price = nextChunkPrice(colony);
        if (colony.getCredits() < price) {
            return ConstructionService.OpResult.fail("信用点不足：本块价格 " + price + "（现有 " + colony.getCredits() + "）。");
        }
        colony.claimChunk(dim, chunkX, chunkZ, price);
        colony.adjustCredits(-price);
        colony.setLastPurchaseWallTime(now);
        ColonySavedData.get(level.getServer()).setDirty();
        level.playSound(null, chunkX << 4, level.getHeight(Heightmap.Types.WORLD_SURFACE, chunkX << 4, chunkZ << 4),
                chunkZ << 4, net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1.2F);
        return ConstructionService.OpResult.ok(String.format("购地成功（%d, %d），花费 %d 信用点，余 %d。",
                chunkX, chunkZ, price, colony.getCredits()));
    }

    public static ConstructionService.OpResult abandonChunk(ServerLevel level, Colony colony, int chunkX, int chunkZ) {
        String dim = colony.getDimension();
        if (!colony.ownsChunk(dim, chunkX, chunkZ)) {
            return ConstructionService.OpResult.fail("该区块不是你的领地。");
        }
        if (ChunkPos.asLong(chunkX, chunkZ) == colony.getHqChunk()) {
            return ConstructionService.OpResult.fail("总部所在区块不可退。");
        }
        if (colony.anyBuildingTouchesChunk(dim, chunkX, chunkZ)) {
            return ConstructionService.OpResult.fail("区块内有建筑，先拆除建筑方可退地。");
        }
        long paid = colony.chunkPaidPrice(dim, chunkX, chunkZ);
        long refund = paid * Config.ABANDON_REFUND_PERCENT.get() / 100;
        colony.abandonChunk(dim, chunkX, chunkZ);
        if (refund > 0) {
            colony.adjustCredits(refund);
        }
        ColonySavedData.get(level.getServer()).setDirty();
        return ConstructionService.OpResult.ok(String.format("退地成功（%d, %d），退还 %d 信用点。",
                chunkX, chunkZ, refund));
    }

    // —— 解散（06 §13 A5：拆完建筑为前置、退购地费 50%） ——

    public static ConstructionService.OpResult dissolve(ServerLevel level, Colony colony) {
        if (colony.getBuildings().size() > 1
                || colony.getBuildings().stream().anyMatch(b -> !"hq".equals(b.definitionId()))) {
            return ConstructionService.OpResult.fail("解散前须先拆除全部建筑（总部除外，随解散清除）。");
        }
        ServerLevel colonyLevel = level.getServer().getLevel(Level.OVERWORLD);
        // 清除总部结构（未加载区块由核心方块自清理兜底）
        for (BuildingInstance b : colony.getBuildings()) {
            if (b.hasOrigin() && colonyLevel != null && colonyLevel.isLoaded(BlockPos.of(b.origin()))) {
                BlueprintGenerator.clearArea(colonyLevel, BlockPos.of(b.origin()), b.footprintW(), b.footprintH(),
                        b.footprintD(), true);
            }
        }
        // 退购地费 50%（免费块不退）→ 存入玩家待发账户（信用点无实体形态，随下次建殖民地发放）
        long refund = 0;
        for (var dimEntry : colony.territoryEntries()) {
            for (var entry : dimEntry.getValue().long2LongEntrySet()) {
                refund += entry.getLongValue() * Config.ABANDON_REFUND_PERCENT.get() / 100;
            }
        }
        long creditsLeft = colony.getCredits();
        ColonySavedData.get(level.getServer()).remove(colony.uuid());
        if (refund > 0) {
            ColonySavedData.get(level.getServer()).addRefund(colony.owner(), Math.min(refund, creditsLeft));
        }
        PioneerColony.LOGGER.info("[殖民地经营] 殖民地[{}]解散：购地退款 {} 待发（原余额 {}）",
                colony.getName(), refund, creditsLeft);
        return ConstructionService.OpResult.ok(String.format(
                "殖民地「%s」已解散。购地退款 %d 信用点已存入待发账户，下次创建殖民地时随启动资金发放。",
                colony.getName(), refund));
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
