package pioneer.colony.nativebridge;

/**
 * 市场价目数据到 Rust 价格公式的适配层。
 *
 * <p>价格白名单、上下限和行情得分由 Java 管理；Rust 只负责计算基准价对应的合理价格。</p>
 */
public final class RustMarketAdapter {
    private RustMarketAdapter() {
    }

    public static double calculateFairPrice(double basePrice, double marketScore, double sectorScore) {
        if (!Double.isFinite(basePrice) || !Double.isFinite(marketScore)
                || !Double.isFinite(sectorScore) || basePrice < 0.0) {
            return 0.01;
        }

        double factor = clamp(1.0 + 0.22 * marketScore + 0.48 * sectorScore, 0.55, 1.85);
        double fallback = basePrice * factor;
        if (!Double.isFinite(fallback)) {
            fallback = 0.01;
        } else {
            fallback = Math.max(0.01, fallback);
        }
        double result = RustBridge.calculateMarketFairPriceOrFallback(
                basePrice, marketScore, sectorScore);
        return Double.isFinite(result) ? result : fallback;
    }

    /** 统一应用数据包中定义的价格下限与上限。 */
    public static double clampToConfiguredRange(double price, double min, double max) {
        if (!Double.isFinite(price) || !Double.isFinite(min) || Double.isNaN(max)
                || min < 0.0 || max < min) {
            return Math.max(0.0, Double.isFinite(min) ? min : 0.0);
        }
        return Math.max(min, Math.min(max, price));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
