package pioneer.colony.construction;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import pioneer.colony.PioneerColony;
import pioneer.colony.colony.BuildingDefinition;
import pioneer.colony.blockentity.ColonyCoreBlockEntity;
import pioneer.colony.colony.BuildingDefinitions;
import pioneer.colony.colony.BuildingInstance;
import pioneer.colony.colony.Colony;
import pioneer.colony.colony.ColonySavedData;
import pioneer.colony.colony.ItemCost;
import pioneer.colony.config.Config;
import pioneer.colony.registry.ModRegistry;

/**
 * 建造服务（M6.2）：放置地基→扣料（仓库优先/背包兜底）→围挡→倒计时→一次成形；
 * 升级/拆除/收货/存取与 GUI 按钮、调试命令共用同一入口。
 * 全部方法要求服务端主线程调用（世界侧操作 + SavedData 写入）。
 */
public final class ConstructionService {
    private ConstructionService() {
    }

    public record OpResult(boolean success, String message) {
        public static OpResult ok(String message) {
            return new OpResult(true, message);
        }

        public static OpResult fail(String message) {
            return new OpResult(false, message);
        }
    }

    /** 仓库可用 = 殖民地有「运转中」的殖民地仓库（06 §3.2：仓库不可用时从玩家背包扣）。 */
    public static boolean warehouseAvailable(Colony colony) {
        return colony.getBuildings().stream()
                .anyMatch(b -> "warehouse".equals(b.definitionId()) && b.status() == BuildingInstance.Status.ACTIVE);
    }

    // —— 放置地基 ——

    /**
     * @param free 调试免料免费（仅 OP 命令冒烟用；物品路径恒为 false）
     */
    public static OpResult placeFoundation(ServerLevel level, ServerPlayer player, BlockPos pos,
                                           String defId, int tier, boolean free) {
        if (level.dimension() != Level.OVERWORLD) {
            return OpResult.fail("环境不适合殖民：建筑只能建在主世界。");
        }
        Colony colony = resolveColony(level, player);
        if (colony == null) {
            return OpResult.fail("请先创建殖民地（/colony create <名称>）。");
        }
        BuildingDefinition def = BuildingDefinitions.get(defId).orElse(null);
        if (def == null) {
            return OpResult.fail("未知建筑定义：" + defId);
        }
        if ("hq".equals(defId)) {
            return OpResult.fail("总部随殖民地创建生成，无需单独建造。");
        }
        if (!colony.ownsChunk(colony.getDimension(), pos.getX() >> 4, pos.getZ() >> 4)) {
            return OpResult.fail("只能在自有领地内建造（先购买区块：总部 GUI「领地」或 /colony chunk buy）。");
        }
        if (!isAreaClear(level, pos, def.footprintW(), def.footprintH(), def.footprintD())) {
            return OpResult.fail("建筑区域被占用，请选择平整空地（占地 " + def.footprintW() + "×" + def.footprintD() + "）。");
        }
        if (colony.overlapsExisting(pos.asLong(), def.footprintW(), def.footprintH(), def.footprintD())) {
            return OpResult.fail("与其他建筑占地重叠。");
        }

        List<ItemCost> cost = def.tierCost(tier);
        OpResult pay = payCost(level, colony, player, cost, feeFor(def, tier), free);
        if (!pay.success()) {
            return pay;
        }

        // 数据层：建筑实例（施工中）
        BuildingInstance building = new BuildingInstance(defId, tier, new net.minecraft.world.level.ChunkPos(pos).toLong());
        building.setOrigin(pos.asLong());
        building.setFootprint(def.footprintW(), def.footprintH(), def.footprintD());
        building.setStatus(BuildingInstance.Status.CONSTRUCTION);
        building.setPendingTier(tier);
        building.setBuildEndWall(System.currentTimeMillis() + def.tierBuildSeconds(tier) * 1000L);
        colony.addBuilding(building);
        markDirty(level);

        // 世界侧：核心方块 + 围挡 + 音效
        level.setBlock(pos, ModRegistry.COLONY_CORE.get().defaultBlockState(), 3);
        if (level.getBlockEntity(pos) instanceof ColonyCoreBlockEntity core) {
            core.configure(colony.uuid(), defId, tier, def.footprintW(), def.footprintH(), def.footprintD());
        }
        placeBarriers(level, pos, def.footprintW(), def.footprintD());
        level.playSound(null, pos, SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
        return OpResult.ok(String.format("%s 开始施工（%d 级，%d 秒后成形）%s",
                def.name(), tier, def.tierBuildSeconds(tier), free ? "【调试免料】" : ""));
    }

    // —— 施工完成（核心方块 tick 驱动） ——

    public static void completeConstruction(ServerLevel level, Colony colony, BuildingInstance building,
                                            ColonyCoreBlockEntity core) {
        BuildingDefinition def = BuildingDefinitions.get(building.definitionId()).orElse(null);
        if (def == null) {
            return;
        }
        int newTier = building.getPendingTier() > 0 ? building.getPendingTier() : building.tier();
        building.setTier(newTier);
        building.setPendingTier(-1);
        building.setStatus(BuildingInstance.Status.ACTIVE);
        markDirty(level);

        BlockPos origin = core.getBlockPos();
        BlueprintGenerator.materialize(level, def, newTier, origin);
        removeBarriers(level, origin, def.footprintW(), def.footprintD());
        core.configure(colony.uuid(), building.definitionId(), newTier, def.footprintW(), def.footprintH(), def.footprintD());
        level.playSound(null, origin, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.7F, 1.0F);
        PioneerColony.LOGGER.info("[殖民地经营] 建筑[{}]成形：{} Lv.{}", colony.getName(), def.name(), newTier);
    }

    // —— 升级 ——

    public static OpResult tryUpgrade(ServerLevel level, Colony colony, BuildingInstance building, ServerPlayer player) {
        BuildingDefinition def = BuildingDefinitions.get(building.definitionId()).orElse(null);
        if (def == null) {
            return OpResult.fail("未知建筑定义：" + building.definitionId());
        }
        if (building.status() != BuildingInstance.Status.ACTIVE) {
            return OpResult.fail("建筑当前不可升级（施工/拆除中）。");
        }
        if (building.tier() >= def.maxTier()) {
            return OpResult.fail("已达最高等级 " + def.maxTier() + "。");
        }
        int nextTier = building.tier() + 1;
        OpResult pay = payCost(level, colony, player, def.tierCost(nextTier), feeFor(def, nextTier), false);
        if (!pay.success()) {
            return pay;
        }
        building.setStatus(BuildingInstance.Status.CONSTRUCTION);
        building.setPendingTier(nextTier);
        building.setBuildEndWall(System.currentTimeMillis() + def.tierBuildSeconds(nextTier) * 1000L);
        markDirty(level);
        if (building.origin() >= 0) {
            placeBarriers(level, BlockPos.of(building.origin()), def.footprintW(), def.footprintD());
        }
        return OpResult.ok(String.format("%s 升级至 %d 级施工中（%d 秒）。", def.name(), nextTier, def.tierBuildSeconds(nextTier)));
    }

    // —— 拆除 ——

    public static OpResult tryDismantle(ServerLevel level, Colony colony, BuildingInstance building, ServerPlayer player) {
        if (building.status() != BuildingInstance.Status.ACTIVE) {
            return OpResult.fail("建筑当前不可拆除。");
        }
        if (player != null && !colony.isOwner(player.getUUID())) {
            return OpResult.fail("只有总督本人可以拆除建筑。");
        }
        building.setStatus(BuildingInstance.Status.DISMANTLING);
        building.setDismantleEndWall(System.currentTimeMillis() + Config.DISMANTLE_SECONDS.get() * 1000L);
        markDirty(level);
        return OpResult.ok("拆除倒计时开始（" + Config.DISMANTLE_SECONDS.get() + " 秒），返还材料 "
                + Config.DISMANTLE_REFUND_PERCENT.get() + "%，建设费不退。");
    }

    public static void completeDismantle(ServerLevel level, Colony colony, BuildingInstance building,
                                         ColonyCoreBlockEntity core) {
        BuildingDefinition def = BuildingDefinitions.get(building.definitionId()).orElse(null);
        long refundPercent = Config.DISMANTLE_REFUND_PERCENT.get();
        if (def != null) {
            for (ItemCost cost : def.tierCost(building.tier())) {
                long refund = cost.count() * refundPercent / 100;
                if (refund > 0) {
                    colony.insertOutput(cost.item(), refund);
                }
            }
        }
        BlockPos origin = core.getBlockPos();
        BlueprintGenerator.clearArea(level, origin, def == null ? core.footprintW() : def.footprintW(),
                def == null ? core.footprintH() : def.footprintH(),
                def == null ? core.footprintD() : def.footprintD(), true);
        colony.removeBuildingByDefinition(building.definitionId());
        markDirty(level);
        level.playSound(null, origin, SoundEvents.ITEM_FRAME_BREAK, SoundSource.BLOCKS, 0.8F, 0.7F);
        PioneerColony.LOGGER.info("[殖民地经营] 建筑[{}]拆除完成：{}", colony.getName(),
                def == null ? building.definitionId() : def.name());
    }

    // —— 收取 / 存取（GUI「收取货物」） ——

    /** 取出该建筑产出物品（从殖民地缓冲到玩家背包），返回移动数量。 */
    public static long withdrawOutput(ServerLevel level, Colony colony, BuildingInstance building, ServerPlayer player) {
        BuildingDefinition def = BuildingDefinitions.get(building.definitionId()).orElse(null);
        String item = def != null ? def.outputItem() : null;
        if (item == null) {
            return -1;
        }
        return moveBufferToPlayer(colony, item, Long.MAX_VALUE, player);
    }

    public static long moveBufferToPlayer(Colony colony, String item, long maxCount, ServerPlayer player) {
        long stock = colony.countOf(item);
        long toMove = Math.min(stock, maxCount);
        if (toMove <= 0) {
            return 0;
        }
        Item itemObj = resolveItem(item);
        if (itemObj == null) {
            return 0;
        }
        long moved = 0;
        while (moved < toMove) {
            long chunk = Math.min(64, toMove - moved);
            ItemStack stack = new ItemStack(itemObj, (int) chunk);
            if (!player.getInventory().add(stack)) {
                if (stack.getCount() > 0) {
                    colony.insertOutput(item, stack.getCount()); // 背包放不下的退回缓冲
                }
                break;
            }
            moved += chunk;
        }
        if (moved > 0) {
            colony.takeItems(item, moved);
        }
        return moved;
    }

    /** 玩家背包 → 殖民地缓冲（仓库 GUI「存入全部」）。 */
    public static long depositAllFromPlayer(Colony colony, ServerPlayer player) {
        long total = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            long accepted = colony.insertOutput(id, stack.getCount());
            if (accepted >= stack.getCount()) {
                inv.setItem(i, ItemStack.EMPTY);
                total += accepted;
            } else if (accepted > 0) {
                stack.shrink((int) accepted);
                total += accepted;
            }
        }
        return total;
    }

    /** 殖民地缓冲 → 玩家背包（仓库 GUI「取出全部」）。 */
    public static long withdrawAllToPlayer(Colony colony, ServerPlayer player) {
        long total = 0;
        for (var entry : colony.bufferSnapshot().entrySet()) {
            total += moveBufferToPlayer(colony, entry.getKey(), entry.getValue(), player);
        }
        return total;
    }

    // —— 内部 ——

    private static long feeFor(BuildingDefinition def, int tier) {
        if (!Config.CONSTRUCTION_FEE_ENABLED.get()) {
            return 0;
        }
        return (long) Config.CONSTRUCTION_FEE_BASE.get() * tier;
    }

    /** 扣料：仓库可用 → 缓冲扣；否则玩家背包；另扣一次性建设费。 */
    private static OpResult payCost(ServerLevel level, Colony colony, ServerPlayer player,
                                    List<ItemCost> cost, long fee, boolean free) {
        if (free) {
            colony.adjustCredits(0);
            return OpResult.ok("");
        }
        if (colony.getCredits() < fee) {
            return OpResult.fail("信用点不足：需 " + fee + "（现有 " + colony.getCredits() + "）。");
        }
        boolean fromWarehouse = warehouseAvailable(colony);
        if (fromWarehouse) {
            if (!colony.hasAll(cost)) {
                return OpResult.fail("殖民地仓库材料不足，请补料（仓库 GUI「存入全部」）。");
            }
            colony.takeAll(cost);
        } else {
            if (player == null) {
                return OpResult.fail("仓库不可用且无玩家背包可扣料。");
            }
            if (!takeFromInventory(player, cost)) {
                return OpResult.fail("背包材料不足（仓库未建成，材料从背包扣）。");
            }
        }
        colony.adjustCredits(-fee);
        markDirty(level);
        return OpResult.ok("");
    }

    /** 背包扣料：全部物品足够才扣。 */
    private static boolean takeFromInventory(ServerPlayer player, List<ItemCost> cost) {
        for (ItemCost c : cost) {
            if (countInInventory(player, c.item()) < c.count()) {
                return false;
            }
        }
        for (ItemCost c : cost) {
            Item item = resolveItem(c.item());
            if (item == null) {
                continue;
            }
            long remaining = c.count();
            var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && stack.is(item)) {
                    long take = Math.min(remaining, stack.getCount());
                    stack.shrink((int) take);
                    remaining -= take;
                }
            }
        }
        return true;
    }

    private static long countInInventory(ServerPlayer player, String itemId) {
        Item item = resolveItem(itemId);
        if (item == null) {
            return 0;
        }
        long total = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static Item resolveItem(String itemId) {
        try {
            return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId)).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isAreaClear(ServerLevel level, BlockPos origin, int w, int h, int d) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h + 1; y++) {
                for (int z = 0; z < d; z++) {
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (!level.getBlockState(cursor).canBeReplaced()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** 控制台/命令建造用：在 base 附近找一块可用的平整地面（螺旋尝试）。 */
    public static BlockPos findClearOrigin(ServerLevel level, BlockPos base, int w, int h, int d) {
        int[][] offsets = {{0, 0}, {9, 0}, {-9, 0}, {0, 9}, {0, -9}, {9, 9}, {-9, -9}, {9, -9}, {-9, 9},
                {18, 0}, {0, 18}, {-18, 0}, {0, -18}, {18, 9}, {-18, -9}};
        for (int[] o : offsets) {
            int x = base.getX() + o[0];
            int z = base.getZ() + o[1];
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (isAreaClear(level, candidate, w, h, d)) {
                return candidate;
            }
        }
        return base;
    }

    /** 围挡：footprint 外圈一格，地面与 +1 两层。 */
    public static void placeBarriers(ServerLevel level, BlockPos origin, int w, int d) {
        var barrier = ModRegistry.CONSTRUCTION_BARRIER.get().defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -1; x <= w; x++) {
            for (int z = -1; z <= d; z++) {
                boolean ring = x == -1 || z == -1 || x == w || z == d;
                if (!ring) {
                    continue;
                }
                for (int y = 1; y <= 2; y++) {
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (level.getBlockState(cursor).isAir()) {
                        level.setBlock(cursor.immutable(), barrier, 3);
                    }
                }
            }
        }
    }

    public static void removeBarriers(ServerLevel level, BlockPos origin, int w, int d) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -1; x <= w; x++) {
            for (int z = -1; z <= d; z++) {
                boolean ring = x == -1 || z == -1 || x == w || z == d;
                if (!ring) {
                    continue;
                }
                for (int y = 1; y <= 2; y++) {
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (level.getBlockState(cursor).is(ModRegistry.CONSTRUCTION_BARRIER.get())) {
                        level.removeBlock(cursor.immutable(), false);
                    }
                }
            }
        }
    }

    private static Colony resolveColony(ServerLevel level, ServerPlayer player) {
        if (player == null) {
            return ColonySavedData.get(level.getServer()).colonyOf(
                    pioneer.colony.colony.ColonyManager.DEBUG_OWNER);
        }
        return ColonySavedData.get(level.getServer()).colonyOf(player.getUUID());
    }

    private static void markDirty(ServerLevel level) {
        ColonySavedData.get(level.getServer()).setDirty();
    }
}
