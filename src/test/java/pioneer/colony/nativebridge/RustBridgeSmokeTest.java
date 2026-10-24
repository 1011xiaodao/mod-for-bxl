package pioneer.colony.nativebridge;

/**
 * 构建期 FFM 集成冒烟测试，由 Gradle 的 nativeBridgeSmokeTest 任务调用。
 * 不依赖测试框架，保证原生库实际加载、ABI 校验及返回值都能工作。
 */
public final class RustBridgeSmokeTest {
    private RustBridgeSmokeTest() {
    }

    public static void main(String[] args) {
        RustBridge.initialize();
        if (!RustBridge.isAvailable()) {
            throw new IllegalStateException("Rust 原生库未加载，FFM 冒烟测试无法验证真实桥接。");
        }

        double happiness = RustBridge.calculateHappinessOrFallback(
                25.0, 35.0, 0.5, 25.0, 1.0, 15.0, 1.0);
        requireClose("幸福度", happiness, 82.5);

        double fairPrice = RustBridge.calculateMarketFairPriceOrFallback(100.0, 0.0, 0.0);
        requireClose("市场合理价格", fairPrice, 100.0);

        System.out.println("Rust FFM 桥接冒烟测试通过：ABI、幸福度和市场价格计算均正常。");
    }

    private static void requireClose(String name, double actual, double expected) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > 1e-9) {
            throw new IllegalStateException(
                    name + "计算不正确：期望 " + expected + "，实际 " + actual);
        }
    }
}
