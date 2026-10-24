package pioneer.colony.nativebridge;

/**
 * Minecraft 经济系统到 Rust 纯计算层的适配器。
 *
 * <p>所有输入必须由 Java 从配置、殖民地数据和世界状态中采集。
 * Rust 只接收数值，不读取或修改 Minecraft 对象。</p>
 */
public final class RustEconomyAdapter {
    private RustEconomyAdapter() {
    }

    public record HappinessInput(
            double base,
            double heatingWeight,
            double heatingRate,
            double foodWeight,
            double foodRate,
            double housingWeight,
            double housingRate) {
    }

    /**
     * 计算幸福度。非法数值采用安全值 0，正常输入保持与旧 Java 公式完全相同的计算顺序。
     */
    public static double calculateHappiness(HappinessInput input) {
        if (input == null || !allFinite(
                input.base(),
                input.heatingWeight(), input.heatingRate(),
                input.foodWeight(), input.foodRate(),
                input.housingWeight(), input.housingRate())) {
            return 0.0;
        }

        double fallback = input.base()
                + input.heatingWeight() * input.heatingRate()
                + input.foodWeight() * input.foodRate()
                + input.housingWeight() * input.housingRate();
        if (!Double.isFinite(fallback)) {
            fallback = 0.0;
        }

        double result = RustBridge.calculateHappinessOrFallback(
                input.base(),
                input.heatingWeight(), input.heatingRate(),
                input.foodWeight(), input.foodRate(),
                input.housingWeight(), input.housingRate());
        return Double.isFinite(result) ? result : fallback;
    }

    private static boolean allFinite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }
}
