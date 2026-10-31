package pioneer.colony.gauge;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用殖民地资源注册表。附属模组可以注册自有 Gauge，无需修改本体经济结算器。
 */
public final class GaugeRegistry {
    private static final Map<String, GaugeDefinition> DEFINITIONS = new ConcurrentHashMap<>();

    static {
        register(new GaugeDefinition("pioneer_colony:heat", "热能", "HU", 0.0));
    }

    private GaugeRegistry() {
    }

    public static void register(GaugeDefinition definition) {
        if (DEFINITIONS.putIfAbsent(definition.id(), definition) != null) {
            throw new IllegalStateException("Gauge 已注册：" + definition.id());
        }
    }

    public static Optional<GaugeDefinition> get(String id) {
        return Optional.ofNullable(DEFINITIONS.get(id));
    }

    public static Map<String, GaugeDefinition> all() {
        return Map.copyOf(DEFINITIONS);
    }
}
