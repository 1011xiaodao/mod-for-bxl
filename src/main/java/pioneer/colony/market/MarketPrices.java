package pioneer.colony.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import pioneer.colony.PioneerColony;

/**
 * 星联市场价目表（06 §7.2/§6）：datapack data/&lt;ns&gt;/pioneer_colony/market/*.json。
 * 威胁值物资估值（§6）与市场买卖（M6.7）共用同一份基准价——「一份价格表两处用」。
 * 文件格式：数组 [{"item": "minecraft:wheat", "base_price": 2, "min": 1, "max": 10}]
 */
public final class MarketPrices extends SimpleJsonResourceReloadListener {
    private record PriceEntry(double basePrice, double min, double max) {
    }

    private static volatile Map<String, PriceEntry> prices = Map.of();

    public MarketPrices() {
        super(new com.google.gson.Gson(), "pioneer_colony/market");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, PriceEntry> loaded = new LinkedHashMap<>();
        files.forEach((key, element) -> {
            try {
                if (!element.isJsonArray()) {
                    throw new IllegalArgumentException("顶层须为数组");
                }
                JsonArray arr = element.getAsJsonArray();
                for (JsonElement e : arr) {
                    JsonObject o = e.getAsJsonObject();
                    String item = o.get("item").getAsString();
                    double base = o.get("base_price").getAsDouble();
                    double min = o.has("min") ? o.get("min").getAsDouble() : 0;
                    double max = o.has("max") ? o.get("max").getAsDouble() : Double.MAX_VALUE;
                    if (base < 0 || min < 0 || max < min) {
                        throw new IllegalArgumentException("价格区间非法：" + item);
                    }
                    loaded.put(item, new PriceEntry(base, min, max));
                }
            } catch (Exception ex) {
                PioneerColony.LOGGER.error("[殖民地经营] 市场价目表加载失败（已跳过）：{} —— {}", key, ex.getMessage());
            }
        });
        prices = Map.copyOf(loaded);
        PioneerColony.LOGGER.info("[殖民地经营] 市场价目表加载完成：{} 项", prices.size());
    }

    public static double basePrice(String item) {
        PriceEntry e = prices.get(item);
        return e == null ? 0 : e.basePrice;
    }

    public static boolean isListed(String item) {
        return prices.containsKey(item);
    }

    public static int size() {
        return prices.size();
    }
}
