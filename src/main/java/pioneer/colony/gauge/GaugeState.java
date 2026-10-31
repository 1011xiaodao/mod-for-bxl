package pioneer.colony.gauge;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

/** 单个殖民地 Gauge 的持久状态；settle 只做确定性数值运算。 */
public final class GaugeState {
    private double stored;
    private double capacity;
    private double productionPerMinute;
    private double consumptionPerMinute;
    private double satisfaction = 1.0;
    /** 其他模组每 tick 更新的临时贡献，按 provider ID 覆盖，不持久化。 */
    private final Map<String, Contribution> contributions = new LinkedHashMap<>();

    private record Contribution(double capacity, double production, double consumption) { }

    public double stored() { return stored; }
    public double capacity() { return capacity; }
    public double productionPerMinute() { return productionPerMinute; }
    public double consumptionPerMinute() { return consumptionPerMinute; }
    public double satisfaction() { return satisfaction; }

    public void setStored(double value) {
        stored = clamp(value, 0.0, capacity);
    }

    public void setContribution(String providerId, double capacity, double productionPerMinute,
                                double consumptionPerMinute) {
        if (providerId == null || providerId.isBlank()) throw new IllegalArgumentException("Gauge provider ID 不能为空");
        contributions.put(providerId, new Contribution(finiteNonNegative(capacity),
                finiteNonNegative(productionPerMinute), finiteNonNegative(consumptionPerMinute)));
    }

    /**
     * 先补充本期产量，再满足本期需求；不足部分转为 satisfaction，剩余存量受容量限制。
     */
    public double settle(double capacity, double productionPerMinute, double consumptionPerMinute, long minutes) {
        double externalCapacity = 0.0;
        double externalProduction = 0.0;
        double externalConsumption = 0.0;
        for (Contribution contribution : contributions.values()) {
            externalCapacity = safeSum(externalCapacity, contribution.capacity());
            externalProduction = safeSum(externalProduction, contribution.production());
            externalConsumption = safeSum(externalConsumption, contribution.consumption());
        }
        contributions.clear();
        this.capacity = safeSum(finiteNonNegative(capacity), externalCapacity);
        this.productionPerMinute = safeSum(finiteNonNegative(productionPerMinute), externalProduction);
        this.consumptionPerMinute = safeSum(finiteNonNegative(consumptionPerMinute), externalConsumption);
        double produced = this.productionPerMinute * Math.max(0L, minutes);
        double demanded = this.consumptionPerMinute * Math.max(0L, minutes);
        // 容量只限制结算后的余量；整段周期内的持续产出都可以直接满足同期需求。
        double available = stored + produced;
        if (!Double.isFinite(available)) available = Double.MAX_VALUE;
        satisfaction = demanded <= 0.0 ? 1.0 : clamp(available / demanded, 0.0, 1.0);
        stored = clamp(available - demanded, 0.0, this.capacity);
        return satisfaction;
    }

    public double efficiencyMultiplier(GaugeDefinition definition) {
        double floor = definition == null ? 0.0 : definition.shortageEfficiencyFloor();
        return floor + (1.0 - floor) * satisfaction;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("stored", stored);
        tag.putDouble("capacity", capacity);
        tag.putDouble("productionPerMinute", productionPerMinute);
        tag.putDouble("consumptionPerMinute", consumptionPerMinute);
        tag.putDouble("satisfaction", satisfaction);
        return tag;
    }

    public static GaugeState load(CompoundTag tag) {
        GaugeState state = new GaugeState();
        state.capacity = finiteNonNegative(tag.getDouble("capacity"));
        state.stored = clamp(tag.getDouble("stored"), 0.0, state.capacity);
        state.productionPerMinute = finiteNonNegative(tag.getDouble("productionPerMinute"));
        state.consumptionPerMinute = finiteNonNegative(tag.getDouble("consumptionPerMinute"));
        state.satisfaction = clamp(tag.getDouble("satisfaction"), 0.0, 1.0);
        return state;
    }

    private static double safeSum(double a, double b) {
        double sum = a + b;
        return Double.isFinite(sum) ? Math.max(0.0, sum) : Double.MAX_VALUE;
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
    }

    private static double clamp(double value, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }
}
