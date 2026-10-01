package pioneer.colony.research;

import net.neoforged.fml.ModList;
import pioneer.colony.PioneerColony;
import pioneer.colony.config.Config;

/**
 * 研究集成裁决（M6.3）：
 * <ol>
 *   <li>[研究台] pioneer_research 在位 → 真实桥（M8.4 联调接入；当前版本研究页隐藏、不崩）；</li>
 *   <li>[研究台] 缺席 + research.stubEnabled → 研究桩（开发联调，数据格式与 [研究台] 一致）；</li>
 *   <li>其余 → null：研究页隐藏，其余功能不受影响（06 §5 软依赖验收点）。</li>
 * </ol>
 * mode 为 null 表示尚未裁决（配置未就绪时下次调用重试）。
 */
public final class ResearchIntegration {
    public enum Mode {
        /** 研究桩（[研究台] 缺席 + 桩开启） */
        STUB,
        /** [研究台] 在位（真实桥，M8.4 联调） */
        REAL,
        /** 无研究系统（研究页隐藏） */
        DISABLED
    }

    private static volatile Mode mode = null;
    private static volatile ResearchBridge bridge = null;

    private ResearchIntegration() {
    }

    /** 惰性裁决（配置在服务端加载后可读；解析成功后缓存）。 */
    public static ResearchBridge bridge() {
        resolveIfNeeded();
        return mode == Mode.STUB ? bridge : null;
    }

    public static Mode mode() {
        resolveIfNeeded();
        return mode;
    }

    /** 重置裁决（调试用）。 */
    public static void invalidate() {
        mode = null;
        bridge = null;
    }

    private static void resolveIfNeeded() {
        if (mode != null) {
            return;
        }
        synchronized (ResearchIntegration.class) {
            if (mode != null || ModList.get() == null) {
                return;
            }
            if (ModList.get().isLoaded("pioneer_research")) {
                mode = Mode.REAL;
                bridge = null;
                PioneerColony.LOGGER.info("[殖民地经营] 检测到 [研究台]：真实研究桥待 M8.4 联调接入（当前版本研究页隐藏）。");
                return;
            }
            boolean stubEnabled;
            try {
                stubEnabled = Config.RESEARCH_STUB_ENABLED.get();
            } catch (IllegalStateException configNotReady) {
                return; // 配置未就绪，下次调用重试
            }
            if (stubEnabled) {
                mode = Mode.STUB;
                bridge = new StubResearchSystem.Bridge();
                bridge.registerColonyChannel(ColonyResearchChannel.INSTANCE);
                PioneerColony.LOGGER.info("[殖民地经营] [研究台] 缺席：启用研究桩（开发联调用）。");
            } else {
                mode = Mode.DISABLED;
                bridge = null;
                PioneerColony.LOGGER.info("[殖民地经营] 无研究系统（研究桩关闭）：研究页隐藏。");
            }
        }
    }
}
