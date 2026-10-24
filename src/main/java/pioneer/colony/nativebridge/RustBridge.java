package pioneer.colony.nativebridge;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

/**
 * Java 25 与 Rust 之间的唯一桥接入口。
 *
 * <p>使用 Java 22+ 稳定版 Foreign Function &amp; Memory API（FFM），不使用 JNI、
 * 不要求生成 JNI 头文件，也不需要在 Rust 中管理 JVM 对象。
 * Java/Rust 边界只允许使用明确的 C ABI 标量类型。</p>
 *
 * <p>原生库是可选项：加载失败或 ABI 版本不匹配时，调用方自动使用等价的 Java 公式，
 * 不会因为缺少本机库而阻止模组启动。</p>
 */
public final class RustBridge {
    private static final int EXPECTED_ABI_VERSION = 2;
    private static final AtomicBoolean RUNTIME_FAILURE_LOGGED = new AtomicBoolean();
    private static final long MAX_JSON_RESPONSE_BYTES = 1_048_576;
    private static final System.Logger FALLBACK_LOGGER = System.getLogger("pioneer_colony");
    private static volatile Logger modLogger;
    private static final LoadResult LOAD_RESULT = load();
    private static final NativeApi API = LOAD_RESULT.api();

    private RustBridge() {
    }

    /** 在模组启动时调用一次，便于在日志中确认当前实际使用的计算后端。 */
    public static void initialize() {
        if (API == null) {
            FALLBACK_LOGGER.log(System.Logger.Level.INFO,
                    "[殖民地经营] Rust 原生计算未启用：{0}；当前使用 Java 回退公式。", LOAD_RESULT.reason());
        } else {
            FALLBACK_LOGGER.log(System.Logger.Level.INFO,
                    "[殖民地经营] Rust 原生计算已启用（ABI v{0}）。", API.abiVersion());
        }
    }

    /** 使用模组日志记录器；无参版本保留给独立冒烟测试使用。 */
    public static void initialize(Logger logger) {
        modLogger = logger;
        if (API == null) {
            logger.info("[殖民地经营] Rust 原生计算未启用：{}；当前使用 Java 回退公式。", LOAD_RESULT.reason());
        } else {
            logger.info("[殖民地经营] Rust 原生计算已启用（ABI v{}）。", API.abiVersion());
        }
    }

    public static boolean isAvailable() {
        return API != null;
    }

    /**
     * 计算殖民地幸福度。
     * 参数及计算顺序与 EconomyTicker 原有 Java 公式一致，避免切换后改变平衡数值。
     */
    public static double calculateHappinessOrFallback(
            double base,
            double heatingWeight,
            double heatingRate,
            double foodWeight,
            double foodRate,
            double housingWeight,
            double housingRate) {
        double fallback = base
                + heatingWeight * heatingRate
                + foodWeight * foodRate
                + housingWeight * housingRate;

        if (API == null) {
            return fallback;
        }

        try {
            double result = (double) API.calculateHappiness().invokeExact(
                    base, heatingWeight, heatingRate,
                    foodWeight, foodRate,
                    housingWeight, housingRate);
            if (!Double.isFinite(result)) {
                throw new IllegalStateException("Rust 幸福度计算返回非有限数值");
            }
            return result;
        } catch (Throwable error) {
            logRuntimeFallback(error);
            return fallback;
        }
    }

    /**
     * 市场合理价格公式的桥接示例，供后续 Rust 市场模块接入。
     * 当前项目尚未将该公式用于现有市场交易流程。
     */
    public static double calculateMarketFairPriceOrFallback(
            double basePrice, double marketScore, double sectorScore) {
        double factor = clamp(1.0 + 0.22 * marketScore + 0.48 * sectorScore, 0.55, 1.85);
        double fallback = Math.max(0.01, basePrice * factor);

        if (API == null) {
            return fallback;
        }

        try {
            double result = (double) API.calculateMarketFairPrice().invokeExact(
                    basePrice, marketScore, sectorScore);
            if (!Double.isFinite(result)) {
                throw new IllegalStateException("Rust 市场价格计算返回非有限数值");
            }
            return result;
        } catch (Throwable error) {
            logRuntimeFallback(error);
            return fallback;
        }
    }

    /**
     * 通用 Rust 业务入口。Rust 侧按 operation 分发 JSON 请求；新增 Rust 算法无需新增 Java FFM 符号。
     * 返回值是 JSON 响应，格式为 {\"ok\":true,\"value\":...} 或 {\"ok\":false,\"error\":...}。
     */
    public static String invokeJson(String operation, String inputJson) {
        if (operation == null || operation.isBlank() || inputJson == null) {
            return "{\"ok\":false,\"error\":\"invalid_java_argument\"}";
        }
        if (API == null) {
            return "{\"ok\":false,\"error\":\"native_unavailable\"}";
        }

        MemorySegment output = MemorySegment.NULL;
        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment operationSegment = callArena.allocateFrom(operation);
            MemorySegment inputSegment = callArena.allocateFrom(inputJson);
            output = (MemorySegment) API.callJson().invokeExact(operationSegment, inputSegment);
            if (output == null || output.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("Rust JSON 接口返回空指针");
            }
            return output.reinterpret(MAX_JSON_RESPONSE_BYTES).getString(0);
        } catch (Throwable error) {
            logRuntimeFallback(error);
            return "{\"ok\":false,\"error\":\"native_call_failed\"}";
        } finally {
            if (output != null && !output.equals(MemorySegment.NULL)) {
                try {
                    API.freeString().invokeExact(output);
                } catch (Throwable error) {
                    logRuntimeFallback(error);
                }
            }
        }
    }

    private static void logRuntimeFallback(Throwable error) {
        if (RUNTIME_FAILURE_LOGGED.compareAndSet(false, true)) {
            if (modLogger != null) {
                modLogger.warn(
                        "[殖民地经营] Rust 原生调用失败，已切换到 Java 回退公式；本会话后续不再重复提示。",
                        error);
            } else {
                FALLBACK_LOGGER.log(System.Logger.Level.WARNING,
                        "[殖民地经营] Rust 原生调用失败，已切换到 Java 回退公式；本会话后续不再重复提示。",
                        error);
            }
        }
    }

    private static LoadResult load() {
        Arena arena = Arena.ofShared();
        try {
            Path libraryPath = resolveLibraryPath();
            if (libraryPath == null) {
                arena.close();
                return new LoadResult(null, "未找到当前平台的原生库资源");
            }

            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = SymbolLookup.libraryLookup(libraryPath, arena);
            MethodHandle versionHandle = linker.downcallHandle(
                    lookup.find("colony_core_abi_version").orElseThrow(
                            () -> new IllegalStateException("原生库缺少 ABI 版本符号")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
            int abiVersion = (int) versionHandle.invokeExact();
            if (abiVersion != EXPECTED_ABI_VERSION) {
                arena.close();
                return new LoadResult(null,
                        "ABI 版本不匹配（期望 " + EXPECTED_ABI_VERSION + "，实际 " + abiVersion + "）");
            }

            MethodHandle happiness = linker.downcallHandle(
                    lookup.find("colony_core_calculate_happiness").orElseThrow(
                            () -> new IllegalStateException("原生库缺少幸福度计算符号")),
                    FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE,
                            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                            ValueLayout.JAVA_DOUBLE));

            MethodHandle marketFairPrice = linker.downcallHandle(
                    lookup.find("colony_core_calculate_market_fair_price").orElseThrow(
                            () -> new IllegalStateException("原生库缺少市场价格计算符号")),
                    FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE,
                            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE));

            MethodHandle callJson = linker.downcallHandle(
                    lookup.find("colony_core_call_json").orElseThrow(
                            () -> new IllegalStateException("原生库缺少通用 JSON 调用符号")),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle freeString = linker.downcallHandle(
                    lookup.find("colony_core_free_string").orElseThrow(
                            () -> new IllegalStateException("原生库缺少字符串释放符号")),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

            return new LoadResult(new NativeApi(
                    abiVersion, happiness, marketFairPrice, callJson, freeString, arena), "加载成功");
        } catch (Throwable error) {
            arena.close();
            String detail = error.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = error.getClass().getSimpleName();
            }
            return new LoadResult(null, "加载失败：" + detail);
        }
    }

    /**
     * 优先使用 -Dpioneer_colony.native.path=/绝对路径/原生库 显式指定的库；
     * 否则从 jar 内按操作系统/CPU 架构提取已打包的库。
     */
    private static Path resolveLibraryPath() throws IOException {
        String override = System.getProperty("pioneer_colony.native.path");
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IOException("指定的原生库不存在：" + path);
            }
            return path;
        }

        String resourceName = "/META-INF/native/" + platformKey() + "/"
                + System.mapLibraryName("colony_ffi");
        try (InputStream input = RustBridge.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                return null;
            }
            String mappedName = System.mapLibraryName("colony_ffi");
            Path extracted = Files.createTempFile("pioneer-colony-native-", "-" + mappedName);
            Files.copy(input, extracted, StandardCopyOption.REPLACE_EXISTING);
            extracted.toFile().deleteOnExit();
            return extracted;
        }
    }

    private static String platformKey() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String os = osName.contains("win") ? "windows"
                : osName.contains("mac") || osName.contains("darwin") ? "macos"
                : osName.contains("linux") ? "linux" : "unknown";
        String archName = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String arch = switch (archName) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> archName;
        };
        return os + "-" + arch;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record NativeApi(
            int abiVersion,
            MethodHandle calculateHappiness,
            MethodHandle calculateMarketFairPrice,
            MethodHandle callJson,
            MethodHandle freeString,
            Arena arena) {
    }

    private record LoadResult(NativeApi api, String reason) {
    }
}
