package pioneer.colony.blockentity;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import pioneer.colony.PioneerColony;
import pioneer.colony.colony.BuildingDefinition;
import pioneer.colony.colony.BuildingDefinitions;
import pioneer.colony.colony.BuildingInstance;
import pioneer.colony.colony.Colony;
import pioneer.colony.colony.ColonySavedData;
import pioneer.colony.construction.BlueprintGenerator;
import pioneer.colony.construction.ConstructionService;
import pioneer.colony.menu.ColonyCoreMenu;
import pioneer.colony.registry.ModRegistry;

import javax.annotation.Nullable;

/**
 * 建筑逻辑核心 BlockEntity（地基即核心，06 §3.2）。
 * 持有殖民地归属与 footprint；施工/拆除到点驱动世界侧成形与清除；
 * 殖民地解散或建筑数据缺失时自清理（兜底未加载区块的孤儿结构）。
 * 建筑权威数据在 Colony（SavedData），本类只做世界侧投影与调度。
 */
public class ColonyCoreBlockEntity extends BlockEntity implements MenuProvider {
    private UUID colonyUuid;
    private String definitionId;
    private int tier = 1;
    private int footprintW = 5;
    private int footprintH = 5;
    private int footprintD = 5;

    public ColonyCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.COLONY_CORE_BE.get(), pos, state);
    }

    /** 放置/成形时写入（ConstructionService 调用）。 */
    public void configure(UUID colonyUuid, String definitionId, int tier, int w, int h, int d) {
        this.colonyUuid = colonyUuid;
        this.definitionId = definitionId;
        this.tier = tier;
        this.footprintW = w;
        this.footprintH = h;
        this.footprintD = d;
        setChanged();
    }

    public UUID colonyUuid() {
        return colonyUuid;
    }

    public String definitionId() {
        return definitionId;
    }

    public int footprintW() {
        return footprintW;
    }

    public int footprintH() {
        return footprintH;
    }

    public int footprintD() {
        return footprintD;
    }

    /** 开 GUI 权限（总督本人或可信）——由核心方块右键调用（服务端）。 */
    public boolean canPlayerOpen(Player player) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        Colony colony = resolveColony(serverLevel.getServer());
        return colony != null && colony.canOpenGui(player.getUUID());
    }

    public Colony resolveColony(MinecraftServer server) {
        if (colonyUuid == null) {
            return null;
        }
        return ColonySavedData.get(server).colonyByUuid(colonyUuid);
    }

    public BuildingInstance resolveBuilding(Colony colony) {
        if (colony == null) {
            return null;
        }
        return colony.buildingAtOrigin(getBlockPos().asLong());
    }

    public String displayName() {
        BuildingDefinition def = BuildingDefinitions.get(definitionId).orElse(null);
        return def != null ? def.name() : (definitionId == null ? "殖民地建筑" : definitionId);
    }

    // —— 服务端 tick：施工/拆除到点驱动；施工期环境音效与粒子 ——

    public static void serverTick(Level level, BlockPos pos, BlockState state, ColonyCoreBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        Colony colony = be.resolveColony(serverLevel.getServer());
        if (colony == null) {
            // 殖民地已解散：自清理（兜底孤儿结构）
            be.selfDestruct(serverLevel);
            return;
        }
        BuildingInstance building = be.resolveBuilding(colony);
        if (building == null) {
            be.selfDestruct(serverLevel);
            return;
        }
        long now = System.currentTimeMillis();
        if (building.status() == BuildingInstance.Status.CONSTRUCTION && now >= building.getBuildEndWall()) {
            ConstructionService.completeConstruction(serverLevel, colony, building, be);
        } else if (building.status() == BuildingInstance.Status.DISMANTLING && now >= building.getDismantleEndWall()) {
            ConstructionService.completeDismantle(serverLevel, colony, building, be);
        } else if (building.status() == BuildingInstance.Status.CONSTRUCTION
                && level.getGameTime() % 50 == 0) {
            // 施工期占位表现：敲击音效 + 尘土粒子（位置化，范围内随机点）
            double ox = pos.getX() + level.random.nextDouble() * be.footprintW;
            double oy = pos.getY() + 1.0;
            double oz = pos.getZ() + level.random.nextDouble() * be.footprintD;
            level.playSound(null, ox, oy, oz, net.minecraft.sounds.SoundEvents.WOOD_HIT,
                    net.minecraft.sounds.SoundSource.BLOCKS, 0.6F, 0.9F + level.random.nextFloat() * 0.3F);
            serverLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.CLOUD, ox, oy + 0.5, oz,
                    3, 0.2, 0.3, 0.2, 0.01);
        }
    }

    /** 建筑数据不存在（解散/异常）：清空 footprint 与核心自身。 */
    public void selfDestruct(ServerLevel level) {
        PioneerColony.LOGGER.info("[殖民地经营] 核心方块自清理：pos={} def={}", getBlockPos(), definitionId);
        BlueprintGenerator.clearArea(level, getBlockPos(), footprintW, footprintH, footprintD, true);
    }

    // —— NBT ——

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (colonyUuid != null) {
            tag.putUUID("colonyUuid", colonyUuid);
        }
        if (definitionId != null) {
            tag.putString("definitionId", definitionId);
        }
        tag.putInt("tier", tier);
        tag.putInt("footprintW", footprintW);
        tag.putInt("footprintH", footprintH);
        tag.putInt("footprintD", footprintD);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        colonyUuid = tag.hasUUID("colonyUuid") ? tag.getUUID("colonyUuid") : null;
        definitionId = tag.contains("definitionId") ? tag.getString("definitionId") : null;
        tier = Math.max(1, tag.getInt("tier"));
        footprintW = Math.max(1, tag.getInt("footprintW"));
        footprintH = Math.max(1, tag.getInt("footprintH"));
        footprintD = Math.max(1, tag.getInt("footprintD"));
    }

    // —— MenuProvider（菜单标题 = 建筑名） ——

    @Override
    public Component getDisplayName() {
        return Component.literal(displayName());
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, net.minecraft.world.entity.player.Inventory inv, Player player) {
        return new ColonyCoreMenu(windowId, inv, getBlockPos());
    }
}
