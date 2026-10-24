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

        String happinessInput = "{\"base\":25,\"heatingWeight\":35,\"heatingRate\":0.5,"
                + "\"foodWeight\":25,\"foodRate\":1,\"housingWeight\":15,\"housingRate\":1}";
        RustNativeClient.Response jsonResponse = RustNativeClient.invoke("calculate_happiness", happinessInput);
        if (!jsonResponse.success()) {
            throw new IllegalStateException("Rust 通用 JSON 接口调用失败：" + jsonResponse.error());
        }
        requireClose("结构化 JSON 幸福度", jsonResponse.doubleValueOr(Double.NaN), 82.5);

        RustNativeClient.Response unknownOperation = RustNativeClient.invoke("unknown_operation", "{}");
        if (unknownOperation.success() || !unknownOperation.error().startsWith("unknown_operation:")) {
            throw new IllegalStateException("未知 Rust 操作没有被转换成结构化错误：" + unknownOperation);
        }
        requireClose("JSON 调用回退值",
                RustNativeClient.invokeDouble("unknown_operation", "{}", 123.0), 123.0);

        RustNativeClient.Response malformed = RustNativeClient.parseResponse("not-json");
        if (malformed.success() || !"invalid_native_response".equals(malformed.error())) {
            throw new IllegalStateException("非法原生响应未被安全拒绝：" + malformed);
        }
        RustNativeClient.Response invalidInput = RustNativeClient.invoke("calculate_happiness", "not-json");
        if (invalidInput.success() || !"invalid_input_json".equals(invalidInput.error())) {
            throw new IllegalStateException("非法请求 JSON 未被安全拒绝：" + invalidInput);
        }

        RustEconomyAdapter.HappinessInput adapterInput = new RustEconomyAdapter.HappinessInput(
                25.0, 35.0, 0.5, 25.0, 1.0, 15.0, 1.0);
        requireClose("经济适配层幸福度", RustEconomyAdapter.calculateHappiness(adapterInput), 82.5);
        requireClose("非法经济输入保护", RustEconomyAdapter.calculateHappiness(null), 0.0);
        requireClose("市场价格适配层", RustMarketAdapter.calculateFairPrice(100.0, 0.0, 0.0), 100.0);
        requireClose("市场价格上下限", RustMarketAdapter.clampToConfiguredRange(100.0, 1.0, 10.0), 10.0);
        requireClose("非法市场输入保护",
                RustMarketAdapter.calculateFairPrice(Double.NaN, 0.0, 0.0), 0.01);
        requireClose("市场价格溢出保护",
                RustMarketAdapter.calculateFairPrice(Double.MAX_VALUE, 1.0, 1.0), 0.01);
        requireClose("幸福度非法输入保护",
                RustBridge.calculateHappinessOrFallback(Double.NaN, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0), 0.0);

        System.out.println("Rust FFM 桥接冒烟测试通过：ABI、结构化 JSON、错误回退、经济/市场适配和字符串释放均正常。");
    }

    private static void requireClose(String name, double actual, double expected) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > 1e-9) {
            throw new IllegalStateException(
                    name + "计算不正确：期望 " + expected + "，实际 " + actual);
        }
    }
}
