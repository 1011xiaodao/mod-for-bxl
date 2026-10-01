package pioneer.colony.colony;

import net.minecraft.nbt.CompoundTag;

/**
 * 殖民地建筑实例——纯数据（数据模拟层），实体/方块表现层是它的投影。
 * 状态迁移：CONSTRUCTION --(施工到点+区块加载)--> ACTIVE --(升级)--> CONSTRUCTION(更高 tier)
 * --> ACTIVE；ACTIVE --(拆除倒计时)--> DISMANTLING --(移除)；DAMAGED/UNDER_REPAIR 于 M6.4 启用。
 * origin = 逻辑核心方块（地基）位置；-1 = M6.1 调试用的纯数据建筑（无实体结构）。
 */
public final class BuildingInstance {
    public enum Status {
        /** 施工中（含升级施工）：无产出，围挡 */
        CONSTRUCTION,
        /** 正常运转 */
        ACTIVE,
        /** 受损（袭击；效率 50%，需维修，M6.4 启用） */
        DAMAGED,
        /** 维修中（M6.4 启用） */
        UNDER_REPAIR,
        /** 拆除倒计时 */
        DISMANTLING
    }

    private String definitionId;
    private int tier;
    private Status status;
    private long chunk;
    private long origin = -1;
    private int footprintW = 5;
    private int footprintH = 5;
    private int footprintD = 5;
    /** 升级目标等级（施工完成时生效）。 */
    private int pendingTier = -1;
    private long buildEndWall = 0;
    private long dismantleEndWall = 0;
    private boolean maintenanceSatisfied = true;
    private boolean storageFull = false;
    private boolean inputShortage = false;
    private double maintenanceAccum = 0.0;
    private double productionAccum = 0.0;
    private double lastFillRate = 1.0;

    public BuildingInstance(String definitionId, int tier, long chunk) {
        this.definitionId = definitionId;
        this.tier = tier;
        this.chunk = chunk;
        this.status = Status.ACTIVE;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("definitionId", definitionId);
        tag.putInt("tier", tier);
        tag.putString("status", status.name());
        tag.putLong("chunk", chunk);
        tag.putLong("origin", origin);
        tag.putInt("footprintW", footprintW);
        tag.putInt("footprintH", footprintH);
        tag.putInt("footprintD", footprintD);
        tag.putInt("pendingTier", pendingTier);
        tag.putLong("buildEndWall", buildEndWall);
        tag.putLong("dismantleEndWall", dismantleEndWall);
        tag.putBoolean("maintenanceSatisfied", maintenanceSatisfied);
        tag.putBoolean("storageFull", storageFull);
        tag.putBoolean("inputShortage", inputShortage);
        tag.putDouble("maintenanceAccum", maintenanceAccum);
        tag.putDouble("productionAccum", productionAccum);
        tag.putDouble("lastFillRate", lastFillRate);
        return tag;
    }

    public static BuildingInstance load(CompoundTag tag) {
        BuildingInstance b = new BuildingInstance(
                tag.getString("definitionId"),
                Math.max(1, tag.getInt("tier")),
                tag.getLong("chunk"));
        b.origin = tag.contains("origin") ? tag.getLong("origin") : -1;
        b.footprintW = tag.contains("footprintW") ? Math.max(1, tag.getInt("footprintW")) : 5;
        b.footprintH = tag.contains("footprintH") ? Math.max(1, tag.getInt("footprintH")) : 5;
        b.footprintD = tag.contains("footprintD") ? Math.max(1, tag.getInt("footprintD")) : 5;
        b.pendingTier = tag.getInt("pendingTier");
        b.buildEndWall = tag.getLong("buildEndWall");
        b.dismantleEndWall = tag.getLong("dismantleEndWall");
        try {
            b.status = Status.valueOf(tag.getString("status"));
        } catch (IllegalArgumentException e) {
            b.status = Status.ACTIVE;
        }
        b.maintenanceSatisfied = tag.getBoolean("maintenanceSatisfied");
        b.storageFull = tag.getBoolean("storageFull");
        b.inputShortage = tag.getBoolean("inputShortage");
        b.maintenanceAccum = tag.getDouble("maintenanceAccum");
        b.productionAccum = tag.getDouble("productionAccum");
        b.lastFillRate = tag.contains("lastFillRate") ? tag.getDouble("lastFillRate") : 1.0;
        return b;
    }

    public String definitionId() {
        return definitionId;
    }

    public int tier() {
        return tier;
    }

    public void setTier(int tier) {
        this.tier = tier;
    }

    public Status status() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public long chunk() {
        return chunk;
    }

    public void setChunk(long chunk) {
        this.chunk = chunk;
    }

    public long origin() {
        return origin;
    }

    public void setOrigin(long origin) {
        this.origin = origin;
    }

    public int footprintW() {
        return footprintW;
    }

    public void setFootprint(int w, int h, int d) {
        this.footprintW = Math.max(1, w);
        this.footprintH = Math.max(1, h);
        this.footprintD = Math.max(1, d);
    }

    public int footprintH() {
        return footprintH;
    }

    public int footprintD() {
        return footprintD;
    }

    public int getPendingTier() {
        return pendingTier;
    }

    public void setPendingTier(int pendingTier) {
        this.pendingTier = pendingTier;
    }

    public long getBuildEndWall() {
        return buildEndWall;
    }

    public void setBuildEndWall(long buildEndWall) {
        this.buildEndWall = buildEndWall;
    }

    public long getDismantleEndWall() {
        return dismantleEndWall;
    }

    public void setDismantleEndWall(long dismantleEndWall) {
        this.dismantleEndWall = dismantleEndWall;
    }

    public boolean isMaintenanceSatisfied() {
        return maintenanceSatisfied;
    }

    public void setMaintenanceSatisfied(boolean maintenanceSatisfied) {
        this.maintenanceSatisfied = maintenanceSatisfied;
    }

    public boolean isStorageFull() {
        return storageFull;
    }

    public void setStorageFull(boolean storageFull) {
        this.storageFull = storageFull;
    }

    public boolean isInputShortage() {
        return inputShortage;
    }

    public void setInputShortage(boolean inputShortage) {
        this.inputShortage = inputShortage;
    }

    public double getMaintenanceAccum() {
        return maintenanceAccum;
    }

    public void setMaintenanceAccum(double maintenanceAccum) {
        this.maintenanceAccum = Math.min(24.0, Math.max(0.0, maintenanceAccum));
    }

    public double getProductionAccum() {
        return productionAccum;
    }

    public void setProductionAccum(double productionAccum) {
        this.productionAccum = productionAccum;
    }

    public double getLastFillRate() {
        return lastFillRate;
    }

    public void setLastFillRate(double lastFillRate) {
        this.lastFillRate = lastFillRate;
    }
}
