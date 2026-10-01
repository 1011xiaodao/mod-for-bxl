package pioneer.colony.protection;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import pioneer.colony.PioneerColony;
import pioneer.colony.block.ColonyCoreBlock;
import pioneer.colony.colony.BuildingInstance;
import pioneer.colony.colony.Colony;
import pioneer.colony.colony.ColonySavedData;

/**
 * 领地基础保护（06 §4，对标市面领地模组功能集，保护集成在本模组内）：
 * 非本殖民地成员在领地内禁止破坏/放置/点燃/爆炸破坏/活塞推拉破坏/开建筑 GUI/伤害市民实体（M6.5 挂钩）。
 * 叠加规则：建筑 footprint（含施工围挡环）对所有人不可挖掘（禁挖保护，创造/OP 除外）；
 * 领地内怪物生成照常，PVP 沿用服务器设定。
 */
@EventBusSubscriber(modid = PioneerColony.MODID)
public final class TerritoryProtection {
    private TerritoryProtection() {
    }

    /** 命中某殖民地的领地（维度匹配）。 */
    private static Colony colonyAt(LevelAccessor level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        String dim = serverLevel.dimension().location().toString();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        for (Colony colony : ColonySavedData.get(serverLevel.getServer()).all()) {
            if (colony.ownsChunk(dim, cx, cz)) {
                return colony;
            }
        }
        return null;
    }

    /** 创造/OP 绕过（禁挖保护与基础保护的统一豁免）。 */
    private static boolean bypass(Player player) {
        return player.getAbilities().instabuild || player.hasPermissions(2);
    }

    /** 位置是否在某建筑 footprint（或施工围挡环）内。 */
    private static boolean insideStructure(Colony colony, BlockPos pos, int expand) {
        for (BuildingInstance b : colony.getBuildings()) {
            if (!b.hasOrigin()) {
                continue;
            }
            var origin = net.minecraft.core.BlockPos.of(b.origin());
            boolean constructing = b.status() == BuildingInstance.Status.CONSTRUCTION
                    || b.status() == BuildingInstance.Status.DISMANTLING;
            int ex = constructing ? expand : 0;
            if (pos.getX() >= origin.getX() - ex && pos.getX() < origin.getX() + b.footprintW() + ex
                    && pos.getY() >= origin.getY() && pos.getY() < origin.getY() + b.footprintH() + 2
                    && pos.getZ() >= origin.getZ() - ex && pos.getZ() < origin.getZ() + b.footprintD() + ex) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        Colony colony = colonyAt(event.getLevel(), event.getPos());
        if (colony == null) {
            return;
        }
        boolean owner = colony.isOwner(player.getUUID());
        if (insideStructure(colony, event.getPos(), 1)) {
            // 禁挖保护：建筑结构（含围挡）对所有人不可挖，仅 GUI 拆除；创造/OP 除外
            if (!bypass(player)) {
                event.setCanceled(true);
                player.displayClientMessage(Component.literal("建筑结构受保护：请通过建筑 GUI 拆除或维修。"), true);
            }
            return;
        }
        if (!owner && !bypass(player)) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("这里是「" + colony.getName() + "」的领地，禁止破坏。"), true);
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return; // 系统放置（蓝图成形等）不受限
        }
        Colony colony = colonyAt(event.getLevel(), event.getPos());
        if (colony == null) {
            return;
        }
        if (insideStructure(colony, event.getPos(), 1) && !bypass(player)) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("建筑结构区域内禁止放置方块。"), true);
            return;
        }
        if (!colony.isOwner(player.getUUID()) && !bypass(player)) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("这里是「" + colony.getName() + "」的领地，禁止放置方块。"), true);
        }
    }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide) {
            return;
        }
        Player player = event.getEntity();
        BlockPos pos = event.getPos();
        Colony colony = colonyAt(level, pos);
        if (colony == null) {
            return;
        }
        // 开核心 GUI 权限在核心方块 useWithoutItem 内校验（owner/可信）；此处拦「点火」
        if (!colony.isOwner(player.getUUID()) && !bypass(player)
                && player.getMainHandItem().is(Items.FLINT_AND_STEEL)) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("领地内禁止点火。"), true);
            return;
        }
        if (level.getBlockState(pos).getBlock() instanceof ColonyCoreBlock
                && !colony.canOpenGui(player.getUUID()) && !bypass(player)) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("需要「可信」权限才能操作「" + colony.getName() + "」的建筑 GUI。"), true);
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        String dim = serverLevel.dimension().location().toString();
        ColonySavedData data = ColonySavedData.get(serverLevel.getServer());
        boolean hasColonies = !data.all().isEmpty();
        if (!hasColonies) {
            return;
        }
        Set<BlockPos> protectedPositions = new HashSet<>();
        for (BlockPos pos : event.getAffectedBlocks()) {
            for (Colony colony : data.all()) {
                if (colony.getDimension().equals(dim) && colony.ownsChunk(dim, pos.getX() >> 4, pos.getZ() >> 4)) {
                    protectedPositions.add(pos);
                    break;
                }
            }
        }
        if (!protectedPositions.isEmpty()) {
            event.getAffectedBlocks().removeIf(protectedPositions::contains);
        }
    }

    @SubscribeEvent
    public static void onPiston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        String dim = serverLevel.dimension().location().toString();
        ColonySavedData data = ColonySavedData.get(serverLevel.getServer());
        if (data.all().isEmpty()) {
            return;
        }
        java.util.function.Predicate<BlockPos> inTerritory = pos -> {
            for (Colony colony : data.all()) {
                if (colony.getDimension().equals(dim) && colony.ownsChunk(dim, pos.getX() >> 4, pos.getZ() >> 4)) {
                    return true;
                }
            }
            return false;
        };
        Set<BlockPos> involved = new HashSet<>();
        involved.add(event.getPos());
        involved.add(event.getFaceOffsetPos());
        var resolver = event.getStructureHelper();
        resolver.getToPush().forEach(involved::add);
        resolver.getToDestroy().forEach(involved::add);
        boolean anyIn = involved.stream().anyMatch(inTerritory);
        boolean allIn = involved.stream().allMatch(inTerritory);
        if (anyIn && !allIn) {
            // 跨边界推拉（领地内↔外）一律拦截；领地内部纯内部推拉放行
            event.setCanceled(true);
        }
    }
}
