package pioneer.colony.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.ChunkPos;
import pioneer.colony.colony.BuildingDefinition;
import pioneer.colony.colony.BuildingDefinitions;
import pioneer.colony.colony.BuildingInstance;
import pioneer.colony.colony.Colony;
import pioneer.colony.colony.ColonyManager;
import pioneer.colony.colony.ColonySavedData;
import pioneer.colony.construction.ConstructionService;
import pioneer.colony.config.Config;
import pioneer.colony.registry.ModRegistry;

/**
 * 建筑核心菜单（M6.2）。零槽位容器：动态状态全部经 data slots 同步，
 * 交互经原版按钮包（handleInventoryButtonClick），无自定义网络层。
 * 页签：0 概览（信息/收取/升级/拆除）｜1 领地（地图购地/退地）｜2 城市管理（解散）——总部专属页签。
 * 仓库类建筑额外有「存入全部/取出全部」按钮。
 */
public class ColonyCoreMenu extends AbstractContainerMenu {
    // —— 按钮 ID（ServerboundContainerButtonPacket） ——
    public static final int BTN_WITHDRAW = 0;
    public static final int BTN_UPGRADE = 1;
    public static final int BTN_DISMANTLE = 2;
    public static final int BTN_DEPOSIT_ALL = 5;
    public static final int BTN_WITHDRAW_ALL = 6;
    public static final int BTN_PAGE_OVERVIEW = 10;
    public static final int BTN_PAGE_TERRITORY = 11;
    public static final int BTN_PAGE_CITY = 12;
    public static final int BTN_CONFIRM_ABANDON = 50;
    public static final int BTN_DISSOLVE = 60;
    public static final int BTN_MAP_BASE = 1000;

    public static final int MAP_SIZE = 11;
    public static final int MAP_CENTER = MAP_SIZE / 2;

    // —— data slots 索引 ——
    public static final int SLOT_PAGE = 0;
    public static final int SLOT_TIER = 1;
    public static final int SLOT_STATUS = 2;
    public static final int SLOT_FILL = 3;
    public static final int SLOT_MAINT_OK = 4;
    public static final int SLOT_STORAGE_FULL = 5;
    public static final int SLOT_INPUT_SHORT = 6;
    public static final int SLOT_REMAIN_SECONDS = 7;
    public static final int SLOT_OUTPUT_COUNT = 8;
    public static final int SLOT_CREDITS = 9;
    public static final int SLOT_PRICE = 10;
    public static final int SLOT_CHUNKS = 11;
    public static final int SLOT_MAX_CHUNKS = 12;
    public static final int SLOT_COOLDOWN = 13;
    public static final int SLOT_SELECTED = 14;
    public static final int SLOT_CONFIRMED = 15;
    public static final int SLOT_IS_HQ = 16;
    public static final int SLOT_IS_WAREHOUSE = 17;
    public static final int SLOT_MAP_BASE = 20; // + offset 0..120
    public static final int SLOT_COUNT = SLOT_MAP_BASE + MAP_SIZE * MAP_SIZE;

    // 地图状态值
    public static final int MAP_EMPTY = 0;
    public static final int MAP_OWN = 1;
    public static final int MAP_FOREIGN = 2;
    public static final int MAP_BUYABLE = 3;
    public static final int MAP_TOO_EXPENSIVE = 4;
    public static final int MAP_NOT_ADJACENT = 5;

    private final int[] data = new int[SLOT_COUNT];
    private final net.minecraft.world.inventory.ContainerData containerData = new net.minecraft.world.inventory.ContainerData() {
        @Override
        public int get(int index) {
            return data[index];
        }

        @Override
        public void set(int index, int value) {
            data[index] = value;
        }

        @Override
        public int getCount() {
            return data.length;
        }
    };
    private final BlockPos pos; // 仅服务端持有；客户端为 null

    // 服务端解析态
    private MinecraftServer server;
    private Colony colony;
    private BuildingInstance building;
    private BuildingDefinition definition;

    // 服务端交互状态
    private int selectedOffset = -1;
    private long dismantleArmWall = 0;
    private boolean dissolveArmed = false;

    public ColonyCoreMenu(int id, Inventory inv) {
        this(id, inv, null);
    }

    public ColonyCoreMenu(int id, Inventory inv, BlockPos pos) {
        super(ModRegistry.COLONY_CORE_MENU.get(), id);
        this.pos = pos;
        this.addDataSlots(this.containerData);
        if (pos != null && inv.player instanceof ServerPlayer sp && sp.level() instanceof ServerLevel level) {
            resolveServer(level, sp);
        }
        refreshServerState();
    }

    private void resolveServer(ServerLevel level, ServerPlayer sp) {
        var be = level.getBlockEntity(pos);
        if (!(be instanceof pioneer.colony.blockentity.ColonyCoreBlockEntity core)) {
            return;
        }
        this.server = level.getServer();
        this.colony = core.resolveColony(level.getServer());
        if (colony != null) {
            this.building = colony.buildingAtOrigin(pos.asLong());
        }
        if (building != null) {
            this.definition = BuildingDefinitions.get(building.definitionId()).orElse(null);
        }
    }

    /** 客户端读 data slot。 */
    public int data(int index) {
        return data[index];
    }

    /** 客户端读 data slot（长名别名，屏幕标签用）。 */
    public int clientData(int index) {
        return data[index];
    }

    private void setSlot(int index, int value) {
        data[index] = value;
    }

    public boolean isHq() {
        return data[SLOT_IS_HQ] == 1;
    }

    public int page() {
        return data[SLOT_PAGE];
    }

    private void refreshServerState() {
        if (colony == null || building == null) {
            setSlot(SLOT_STATUS, -1);
            return;
        }
        long now = System.currentTimeMillis();
        setSlot(SLOT_PAGE, data[SLOT_PAGE]);
        setSlot(SLOT_TIER, building.tier());
        setSlot(SLOT_STATUS, building.status().ordinal());
        setSlot(SLOT_FILL, (int) Math.round(building.getLastFillRate() * 100));
        setSlot(SLOT_MAINT_OK, building.isMaintenanceSatisfied() ? 1 : 0);
        setSlot(SLOT_STORAGE_FULL, building.isStorageFull() ? 1 : 0);
        setSlot(SLOT_INPUT_SHORT, building.isInputShortage() ? 1 : 0);
        long remain = 0;
        if (building.status() == BuildingInstance.Status.CONSTRUCTION) {
            remain = Math.max(0, (building.getBuildEndWall() - now) / 1000);
        } else if (building.status() == BuildingInstance.Status.DISMANTLING) {
            remain = Math.max(0, (building.getDismantleEndWall() - now) / 1000);
        }
        setSlot(SLOT_REMAIN_SECONDS, (int) Math.min(Integer.MAX_VALUE, remain));
        String output = definition != null ? definition.outputItem() : null;
        setSlot(SLOT_OUTPUT_COUNT, output == null ? 0 : (int) Math.min(Integer.MAX_VALUE / 2, colony.countOf(output)));
        setSlot(SLOT_CREDITS, (int) Math.min(Integer.MAX_VALUE, colony.getCredits()));
        setSlot(SLOT_PRICE, (int) Math.min(Integer.MAX_VALUE, ColonyManager.nextChunkPrice(colony)));
        setSlot(SLOT_CHUNKS, colony.territorySize());
        setSlot(SLOT_MAX_CHUNKS, Config.TERRITORY_MAX_CHUNKS.get());
        long cooldown = Math.max(0, (colony.getLastPurchaseWallTime()
                + Config.PURCHASE_COOLDOWN_SECONDS.get() * 1000L - now) / 1000);
        setSlot(SLOT_COOLDOWN, (int) Math.min(Integer.MAX_VALUE, cooldown));
        setSlot(SLOT_SELECTED, selectedOffset);
        setSlot(SLOT_CONFIRMED, (dissolveArmed || now - dismantleArmWall < 30_000) ? 1 : 0);
        setSlot(SLOT_IS_HQ, "hq".equals(building.definitionId()) ? 1 : 0);
        setSlot(SLOT_IS_WAREHOUSE, "warehouse".equals(building.definitionId()) ? 1 : 0);
        if (data[SLOT_PAGE] == 1) {
            refreshMapStates();
        }
    }

    private void refreshMapStates() {
        if (server == null || colony == null) {
            return;
        }
        String dim = colony.getDimension();
        long hq = colony.getHqChunk();
        int hqX = new ChunkPos(hq).x;
        int hqZ = new ChunkPos(hq).z;
        var savedData = ColonySavedData.get(server);
        long price = ColonyManager.nextChunkPrice(colony);
        for (int offset = 0; offset < MAP_SIZE * MAP_SIZE; offset++) {
            int cx = hqX + (offset % MAP_SIZE) - MAP_CENTER;
            int cz = hqZ + offset / MAP_SIZE - MAP_CENTER;
            int state;
            if (colony.ownsChunk(dim, cx, cz)) {
                state = MAP_OWN;
            } else {
                boolean foreign = false;
                for (Colony other : savedData.all()) {
                    if (other != colony && other.ownsChunk(dim, cx, cz)) {
                        foreign = true;
                        break;
                    }
                }
                if (foreign) {
                    state = MAP_FOREIGN;
                } else if (!Config.ALLOW_ENCLAVE.get() && !colony.isAdjacentToTerritory(dim, cx, cz)) {
                    state = MAP_NOT_ADJACENT;
                } else if (colony.getCredits() < price) {
                    state = MAP_TOO_EXPENSIVE;
                } else {
                    state = MAP_BUYABLE;
                }
            }
            this.setSlot(SLOT_MAP_BASE + offset, state);
        }
    }

    // —— 交互 ——

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!(player instanceof ServerPlayer sp) || !(sp.level() instanceof ServerLevel level)) {
            return false;
        }
        if (colony == null || building == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (id == BTN_WITHDRAW) {
            long moved = ConstructionService.withdrawOutput(level, colony, building, sp);
            feedback(sp, moved < 0 ? "该建筑无产出物可收取。"
                    : moved == 0 ? "没有可收取的产出（或背包已满）。"
                    : "已收取产出 ×" + moved);
            return true;
        }
        if (id == BTN_UPGRADE) {
            var result = ConstructionService.tryUpgrade(level, colony, building, sp);
            feedback(sp, result.message());
            return true;
        }
        if (id == BTN_DISMANTLE) {
            if (now - dismantleArmWall >= 30_000) {
                dismantleArmWall = now;
                feedback(sp, "已进入拆除确认状态：30 秒内再次点击确认拆除（返还材料 "
                        + Config.DISMANTLE_REFUND_PERCENT.get() + "%）。");
                return true;
            }
            dismantleArmWall = 0;
            var result = ConstructionService.tryDismantle(level, colony, building, sp);
            feedback(sp, result.message());
            return true;
        }
        if (id == BTN_DEPOSIT_ALL) {
            long n = ConstructionService.depositAllFromPlayer(colony, sp);
            feedback(sp, "已存入 " + n + " 件物品到殖民地仓库。");
            return true;
        }
        if (id == BTN_WITHDRAW_ALL) {
            long n = ConstructionService.withdrawAllToPlayer(colony, sp);
            feedback(sp, "已取出 " + n + " 件物品到背包。");
            return true;
        }
        if (id >= BTN_PAGE_OVERVIEW && id <= BTN_PAGE_CITY) {
            setSlot(SLOT_PAGE, id - BTN_PAGE_OVERVIEW);
            refreshServerState();
            return true;
        }
        if (id >= BTN_MAP_BASE && id < BTN_MAP_BASE + MAP_SIZE * MAP_SIZE) {
            handleMapClick(level, sp, id - BTN_MAP_BASE);
            return true;
        }
        if (id == BTN_CONFIRM_ABANDON) {
            if (selectedOffset < 0) {
                feedback(sp, "先在地图上选中要退的己方区块。");
                return true;
            }
            long hq = colony.getHqChunk();
            int cx = new ChunkPos(hq).x + (selectedOffset % MAP_SIZE) - MAP_CENTER;
            int cz = new ChunkPos(hq).z + selectedOffset / MAP_SIZE - MAP_CENTER;
            var result = ColonyManager.abandonChunk(level, colony, cx, cz);
            if (result.success()) {
                selectedOffset = -1;
            }
            feedback(sp, result.message());
            return true;
        }
        if (id == BTN_DISSOLVE) {
            if (!colony.isOwner(sp.getUUID())) {
                feedback(sp, "只有总督本人可以解散殖民地。");
                return true;
            }
            if (!dissolveArmed) {
                dissolveArmed = true;
                feedback(sp, "危险操作：再次点击确认解散殖民地（拆除全部建筑为前置，购地退款随下次建城发放）。");
                return true;
            }
            var result = ColonyManager.dissolve(level, colony);
            dissolveArmed = false;
            feedback(sp, result.message());
            return true;
        }
        return false;
    }

    private void handleMapClick(ServerLevel level, ServerPlayer sp, int offset) {
        long hq = colony.getHqChunk();
        int cx = new ChunkPos(hq).x + (offset % MAP_SIZE) - MAP_CENTER;
        int cz = new ChunkPos(hq).z + offset / MAP_SIZE - MAP_CENTER;
        String dim = colony.getDimension();
        if (colony.ownsChunk(dim, cx, cz)) {
            selectedOffset = offset;
            feedback(sp, "已选中己方区块（" + cx + ", " + cz + "），可点击「确认退地」。");
            return;
        }
        selectedOffset = -1;
        var result = ColonyManager.buyChunk(level, colony, cx, cz);
        feedback(sp, result.message());
    }

    private void feedback(ServerPlayer sp, String message) {
        sp.displayClientMessage(Component.literal(message), true);
        refreshServerState();
    }

    @Override
    public void broadcastChanges() {
        if (colony != null && building != null) {
            refreshServerState();
        }
        super.broadcastChanges();
    }

    // —— 必备实现（零槽位容器） ——

    @Override
    public net.minecraft.world.item.ItemStack quickMoveStack(Player player, int index) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
