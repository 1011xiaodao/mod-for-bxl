package pioneer.colony.colony;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * 建筑定义（datapack JSON，data/&lt;ns&gt;/pioneer_colony/buildings/*.json）。
 * 逐级数值（产出/岗位/施工时长/床位等）用按等级索引的数组表达——改一个数字即调一级。
 * 数组长度允许 1（全等级复用）或 max_tier，其余视为非法条目。
 */
public final class BuildingDefinition {
    private final String id;
    private final String name;
    private final int maxTier;
    private final List<Integer> buildSecondsPerTier;
    private final List<List<ItemCost>> costPerTier;
    private final List<ItemCost> inputPerMin;
    private final String outputItem;
    private final List<Double> outputPerMinPerTier;
    private final List<Integer> workerSlotsPerTier;
    private final String profession;
    private final List<ItemCost> maintenancePerHour;
    private final int populationProvided;
    private final List<Integer> residentSlotsPerTier;
    private final List<Integer> bufferSlotsPerTier;
    private final List<Integer> bufferPerTypeCapacityPerTier;
    private final String researchRequired;
    /** 建筑标签（研究 global_bonus.building_tag 匹配用）。 */
    private final List<String> tags;
    /** 占地 [宽,高,深]（方块），程序化蓝图与保护范围依据。 */
    private final int[] footprint;

    private BuildingDefinition(String id, String name, int maxTier, List<Integer> buildSecondsPerTier,
                               List<List<ItemCost>> costPerTier, List<ItemCost> inputPerMin, String outputItem,
                               List<Double> outputPerMinPerTier, List<Integer> workerSlotsPerTier, String profession,
                               List<ItemCost> maintenancePerHour, int populationProvided,
                               List<Integer> residentSlotsPerTier, List<Integer> bufferSlotsPerTier,
                               List<Integer> bufferPerTypeCapacityPerTier, String researchRequired, List<String> tags, int[] footprint) {
        this.id = id;
        this.name = name;
        this.maxTier = maxTier;
        this.buildSecondsPerTier = buildSecondsPerTier;
        this.costPerTier = costPerTier;
        this.inputPerMin = inputPerMin;
        this.outputItem = outputItem;
        this.outputPerMinPerTier = outputPerMinPerTier;
        this.workerSlotsPerTier = workerSlotsPerTier;
        this.profession = profession;
        this.maintenancePerHour = maintenancePerHour;
        this.populationProvided = populationProvided;
        this.residentSlotsPerTier = residentSlotsPerTier;
        this.bufferSlotsPerTier = bufferSlotsPerTier;
        this.bufferPerTypeCapacityPerTier = bufferPerTypeCapacityPerTier;
        this.researchRequired = researchRequired;
        this.tags = List.copyOf(tags);
        this.footprint = footprint.clone();
    }

    public static BuildingDefinition parse(JsonObject json) {
        String id = requireString(json, "id");
        String name = requireString(json, "name");
        int maxTier = requireInt(json, "max_tier", 1, 64);

        List<Integer> buildSeconds = readTieredInts(json, "build_seconds_per_tier", maxTier, false);
        List<List<ItemCost>> costPerTier = readCostPerTier(json, "cost_per_tier", maxTier);
        List<ItemCost> inputPerMin = readCosts(json, "input_per_min");
        String outputItem = optString(json, "output_item");
        List<Double> outputPerMin = readTieredDoubles(json, "output_per_min_per_tier", maxTier, outputItem != null);
        List<Integer> workerSlots = readTieredInts(json, "worker_slots_per_tier", maxTier, false);
        String profession = optString(json, "profession");
        List<ItemCost> maintenance = readCosts(json, "maintenance_per_hour");
        int populationProvided = optInt(json, "population_provided", 0);
        List<Integer> residentSlots = readTieredInts(json, "resident_slots_per_tier", maxTier, false);
        List<Integer> bufferSlots = readTieredInts(json, "buffer_slots_per_tier", maxTier, false);
        List<Integer> bufferCapacity = readTieredInts(json, "buffer_per_type_capacity_per_tier", maxTier, false);
        String researchRequired = optString(json, "research_required");
        List<String> tags = readTags(json);
        int[] fp = readFootprint(json);
        if (fp == null) {
            fp = new int[]{5, 5, 5};
        }

        if (outputItem != null && profession == null && workerSlots.isEmpty()) {
            throw new IllegalArgumentException("有产出但未声明岗位（worker_slots_per_tier）");
        }
        return new BuildingDefinition(id, name, maxTier, buildSeconds, costPerTier, inputPerMin, outputItem,
                outputPerMin, workerSlots, profession, maintenance, populationProvided,
                residentSlots, bufferSlots, bufferCapacity, researchRequired, tags, fp);
    }

    // —— 按等级取值（tier 从 1 开始，越界收敛到 maxTier） ——

    public int tierBuildSeconds(int tier) {
        return buildSecondsPerTier.isEmpty() ? 600 : buildSecondsPerTier.get(index(tier));
    }

    public List<ItemCost> tierCost(int tier) {
        return costPerTier.isEmpty() ? List.of() : costPerTier.get(index(tier));
    }

    public double tierOutputPerMin(int tier) {
        return outputPerMinPerTier.isEmpty() ? 0.0 : outputPerMinPerTier.get(index(tier));
    }

    public int tierWorkerSlots(int tier) {
        return workerSlotsPerTier.isEmpty() ? 0 : workerSlotsPerTier.get(index(tier));
    }

    public int tierResidentSlots(int tier) {
        return residentSlotsPerTier.isEmpty() ? 0 : residentSlotsPerTier.get(index(tier));
    }

    public int tierBufferSlots(int tier) {
        return bufferSlotsPerTier.isEmpty() ? 0 : bufferSlotsPerTier.get(index(tier));
    }

    public int tierBufferPerTypeCapacity(int tier) {
        return bufferPerTypeCapacityPerTier.isEmpty() ? 0 : bufferPerTypeCapacityPerTier.get(index(tier));
    }

    private int index(int tier) {
        return Math.max(0, Math.min(maxTier, tier) - 1);
    }

    // —— JSON 解析辅助 ——

    private static String requireString(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("缺少必填字段 " + key);
        }
        return json.get(key).getAsString();
    }

    private static String optString(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : null;
    }

    private static int optInt(JsonObject json, String key, int def) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsInt() : def;
    }

    private static int requireInt(JsonObject json, String key, int min, int max) {
        int v = optInt(json, key, -1);
        if (v < min || v > max) {
            throw new IllegalArgumentException("字段 " + key + " 取值非法：" + v);
        }
        return v;
    }

    /** 长度为 1 的数组自动复制到 max_tier；长度须为 max_tier，否则非法。missing 且 optional=true 时返回空表。 */
    private static List<Integer> readTieredInts(JsonObject json, String key, int maxTier, boolean required) {
        JsonArray arr = optArray(json, key);
        if (arr == null) {
            if (required) {
                throw new IllegalArgumentException("缺少必填字段 " + key);
            }
            return List.of();
        }
        List<Integer> ints = new ArrayList<>();
        for (Double v : expandInts(key, arr, maxTier)) {
            ints.add(v.intValue());
        }
        return List.copyOf(ints);
    }

    private static List<Double> readTieredDoubles(JsonObject json, String key, int maxTier, boolean required) {
        JsonArray arr = optArray(json, key);
        if (arr == null) {
            if (required) {
                throw new IllegalArgumentException("缺少必填字段 " + key);
            }
            return List.of();
        }
        return expandInts(key, arr, maxTier);
    }

    private static List<Double> expandInts(String key, JsonArray arr, int maxTier) {
        List<Double> out = new ArrayList<>();
        for (JsonElement e : arr) {
            if (!e.isJsonPrimitive()) {
                throw new IllegalArgumentException("字段 " + key + " 数组元素非法");
            }
            out.add(e.getAsDouble());
        }
        if (out.size() == 1) {
            double v = out.get(0);
            out = new ArrayList<>();
            for (int i = 0; i < maxTier; i++) {
                out.add(v);
            }
        } else if (out.size() != maxTier) {
            throw new IllegalArgumentException("字段 " + key + " 长度须为 1 或 max_tier(" + maxTier + ")，实际 " + out.size());
        }
        return out;
    }

    private static List<ItemCost> readCosts(JsonObject json, String key) {
        JsonArray arr = optArray(json, key);
        if (arr == null) {
            return List.of();
        }
        List<ItemCost> out = new ArrayList<>();
        for (JsonElement e : arr) {
            out.add(readCost(e, key));
        }
        return List.copyOf(out);
    }

    private static List<List<ItemCost>> readCostPerTier(JsonObject json, String key, int maxTier) {
        JsonArray arr = optArray(json, key);
        if (arr == null) {
            return List.of();
        }
        List<List<ItemCost>> out = new ArrayList<>();
        for (JsonElement e : arr) {
            if (!e.isJsonArray()) {
                throw new IllegalArgumentException("字段 " + key + " 须为「数组的数组」");
            }
            List<ItemCost> tierCost = new ArrayList<>();
            for (JsonElement c : e.getAsJsonArray()) {
                tierCost.add(readCost(c, key));
            }
            out.add(List.copyOf(tierCost));
        }
        if (out.size() == 1) {
            List<ItemCost> single = out.get(0);
            out = new ArrayList<>();
            for (int i = 0; i < maxTier; i++) {
                out.add(single);
            }
        } else if (out.size() != maxTier) {
            throw new IllegalArgumentException("字段 " + key + " 长度须为 1 或 max_tier(" + maxTier + ")，实际 " + out.size());
        }
        return List.copyOf(out);
    }

    private static ItemCost readCost(JsonElement e, String key) {
        if (!e.isJsonObject()) {
            throw new IllegalArgumentException("字段 " + key + " 条目须为对象 {item, count}");
        }
        JsonObject o = e.getAsJsonObject();
        String item = optString(o, "item");
        long count = o.has("count") ? o.get("count").getAsLong() : 0;
        if (item == null || item.isBlank() || count < 0) {
            throw new IllegalArgumentException("字段 " + key + " 条目非法（item/count）");
        }
        return new ItemCost(item, count);
    }

    /** tags: 建筑标签数组（可选）。 */
    private static List<String> readTags(JsonObject json) {
        if (!json.has("tags")) {
            return List.of();
        }
        JsonArray arr = json.get("tags").getAsJsonArray();
        List<String> out = new ArrayList<>();
        for (JsonElement e : arr) {
            out.add(e.getAsString());
        }
        return out;
    }

    /** footprint_size: [宽,高,深]（可选，默认 5×5×5）。 */
    private static int[] readFootprint(JsonObject json) {
        if (!json.has("footprint_size")) {
            return null;
        }
        JsonArray arr = json.get("footprint_size").getAsJsonArray();
        if (arr.size() != 3) {
            throw new IllegalArgumentException("footprint_size 须为 [宽,高,深] 三元素数组");
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            out[i] = arr.get(i).getAsInt();
            if (out[i] < 1 || out[i] > 15) {
                throw new IllegalArgumentException("footprint_size 取值须在 1~15：" + out[i]);
            }
        }
        return out;
    }

    private static JsonArray optArray(JsonObject json, String key) {
        if (!json.has(key)) {
            return null;
        }
        JsonElement e = json.get(key);
        if (!e.isJsonArray()) {
            throw new IllegalArgumentException("字段 " + key + " 须为数组");
        }
        return e.getAsJsonArray();
    }

    // —— 访问器 ——

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int maxTier() {
        return maxTier;
    }

    public List<ItemCost> inputPerMin() {
        return inputPerMin;
    }

    public String outputItem() {
        return outputItem;
    }

    public String profession() {
        return profession;
    }

    public List<ItemCost> maintenancePerHour() {
        return maintenancePerHour;
    }

    public int populationProvided() {
        return populationProvided;
    }

    public String researchRequired() {
        return researchRequired;
    }

    public List<String> tags() {
        return tags;
    }

    public int footprintW() {
        return footprint[0];
    }

    public int footprintH() {
        return footprint[1];
    }

    public int footprintD() {
        return footprint[2];
    }
}
