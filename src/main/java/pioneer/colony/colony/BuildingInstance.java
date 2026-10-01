package pioneer.colony.colony;

import net.minecraft.nbt.CompoundTag;

/**
 * 殖民地建筑实例——纯数据（数据模拟层），实体/方块表现层是它的投影。
 * 施工与拆除流程（M6.2）将驱动 status 在 CONSTRUCTION/ACTIVE/DAMAGED/UNDER_REPAIR 间迁移。
 */
public final class BuildingInstance {
    public enum Status {
        /** 施工中（M6.2 启用） */
        CONSTRUCTION,
        /** 正常运转 */
        ACTIVE,
        /** 受损（袭击；效率 50%，需维修，M6.4 启用） */
        DAMAGED,
        /** 维修中（M6.4 启用） */
        UNDER_REPAIR
    }

    private String definitionId;
    private int tier;
    private Status status;
    private long chunk;
    private boolean maintenanceSatisfied = true;
    private boolean storageFull = false;
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
        tag.putBoolean("maintenanceSatisfied", maintenanceSatisfied);
        tag.putBoolean("storageFull", storageFull);
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
        try {
            b.status = Status.valueOf(tag.getString("status"));
        } catch (IllegalArgumentException e) {
            b.status = Status.ACTIVE;
        }
        b.maintenanceSatisfied = tag.getBoolean("maintenanceSatisfied");
        b.storageFull = tag.getBoolean("storageFull");
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

    public Status status() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public long chunk() {
        return chunk;
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
