package pioneer.colony.colony;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import pioneer.colony.PioneerColony;

/**
 * 建筑定义 datapack 加载器：data/&lt;ns&gt;/pioneer_colony/buildings/*.json。
 * 附属模组（如巨构拓展）用同一格式追加定义即可被原样加载，无需本模组改代码（06 §3.2）。
 * 热重载：/reload 生效；加载失败条目跳过并告警，不阻塞其余定义。
 */
public final class BuildingDefinitions extends SimpleJsonResourceReloadListener {
    private static volatile Map<String, BuildingDefinition> definitions = Map.of();

    private static final Gson GSON = new Gson();

    public BuildingDefinitions() {
        super(GSON, "pioneer_colony/buildings");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, BuildingDefinition> loaded = new LinkedHashMap<>();
        files.forEach((key, element) -> {
            if (!element.isJsonObject()) {
                PioneerColony.LOGGER.error("[殖民地经营] 建筑定义加载失败（已跳过）：{} —— 顶层须为 JSON 对象", key);
                return;
            }
            try {
                JsonObject json = element.getAsJsonObject();
                BuildingDefinition def = BuildingDefinition.parse(json);
                loaded.put(def.id(), def);
            } catch (Exception e) {
                PioneerColony.LOGGER.error("[殖民地经营] 建筑定义加载失败（已跳过）：{} —— {}", key, e.getMessage());
            }
        });
        definitions = Map.copyOf(loaded);
        PioneerColony.LOGGER.info("[殖民地经营] 建筑定义加载完成：{} 栋", definitions.size());
    }

    public static Map<String, BuildingDefinition> all() {
        return definitions;
    }

    public static Optional<BuildingDefinition> get(String id) {
        return Optional.ofNullable(definitions.get(id));
    }
}
