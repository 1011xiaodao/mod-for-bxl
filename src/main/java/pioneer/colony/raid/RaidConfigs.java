package pioneer.colony.raid;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import pioneer.colony.PioneerColony;
import pioneer.colony.colony.ItemCost;

/**
 * 袭击波次配置（06 §6）：data/&lt;ns&gt;/pioneer_colony/raids/tier_N.json（N=1~5）。
 * 波次数量/强度按威胁档位，v1 用原版怪物（可带简单装备）；死伤/伤害数值全部参数化。
 */
public final class RaidConfigs extends SimpleJsonResourceReloadListener {
    public record Wave(String entity, int count, List<String> equipment, int delaySeconds) {
    }

    public record RaidDef(int tier, List<Wave> waves, List<ItemCost> loot, double hqDamagePerHit) {
    }

    private static volatile Map<Integer, RaidDef> defs = Map.of();

    public RaidConfigs() {
        super(new com.google.gson.Gson(), "pioneer_colony/raids");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<Integer, RaidDef> loaded = new HashMap<>();
        files.forEach((key, element) -> {
            try {
                RaidDef def = parse(element.getAsJsonObject());
                loaded.put(def.tier(), def);
            } catch (Exception e) {
                PioneerColony.LOGGER.error("[殖民地经营] 袭击配置加载失败（已跳过）：{} —— {}", key, e.getMessage());
            }
        });
        defs = Map.copyOf(loaded);
        PioneerColony.LOGGER.info("[殖民地经营] 袭击配置加载完成：{} 档", defs.size());
    }

    private static RaidDef parse(JsonObject json) {
        int tier = json.get("tier").getAsInt();
        List<Wave> waves = new ArrayList<>();
        JsonArray waveArr = json.getAsJsonArray("waves");
        for (JsonElement we : waveArr) {
            JsonObject wo = we.getAsJsonObject();
            String entity = wo.get("entity").getAsString();
            int count = wo.has("count") ? wo.get("count").getAsInt() : 1;
            int delay = wo.has("delay_seconds") ? wo.get("delay_seconds").getAsInt() : 0;
            List<String> equipment = new ArrayList<>();
            if (wo.has("equipment") && wo.get("equipment").isJsonArray()) {
                for (JsonElement eq : wo.getAsJsonArray("equipment")) {
                    equipment.add(eq.getAsString());
                }
            }
            waves.add(new Wave(entity, count, List.copyOf(equipment), delay));
        }
        List<ItemCost> loot = new ArrayList<>();
        if (json.has("loot") && json.get("loot").isJsonArray()) {
            for (JsonElement le : json.getAsJsonArray("loot")) {
                JsonObject lo = le.getAsJsonObject();
                loot.add(new ItemCost(lo.get("item").getAsString(), lo.get("count").getAsLong()));
            }
        }
        double hqDamage = json.has("hq_damage_per_hit") ? json.get("hq_damage_per_hit").getAsDouble() : 2.0;
        if (waves.isEmpty()) {
            throw new IllegalArgumentException("waves 不可为空");
        }
        return new RaidDef(tier, List.copyOf(waves), List.copyOf(loot), hqDamage);
    }

    public static RaidDef get(int tier) {
        return defs.get(tier);
    }
}
