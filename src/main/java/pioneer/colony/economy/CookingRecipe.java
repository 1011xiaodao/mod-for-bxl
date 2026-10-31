package pioneer.colony.economy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import pioneer.colony.colony.ItemCost;

/** 数据包烹饪配方：食堂按在岗厨师数和每分钟批次数进行抽象加工。 */
public record CookingRecipe(String id, List<ItemCost> inputs, String outputItem,
                            long outputCount, double batchesPerMinutePerWorker) {
    public CookingRecipe {
        inputs = List.copyOf(inputs);
    }

    public static CookingRecipe parse(String id, JsonObject json) {
        JsonArray inputArray = json.has("input") && json.get("input").isJsonArray()
                ? json.getAsJsonArray("input") : null;
        if (inputArray == null || inputArray.isEmpty()) throw new IllegalArgumentException("input 必须是非空数组");
        List<ItemCost> inputs = new ArrayList<>();
        for (JsonElement element : inputArray) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("input 条目必须是对象");
            JsonObject input = element.getAsJsonObject();
            String item = input.has("item") ? input.get("item").getAsString() : "";
            long count = input.has("count") ? input.get("count").getAsLong() : 0L;
            if (!validItem(item) || count <= 0) throw new IllegalArgumentException("input 的 item/count 非法：" + item);
            inputs.add(new ItemCost(item, count));
        }
        if (!json.has("output") || !json.get("output").isJsonObject()) {
            throw new IllegalArgumentException("output 必须是 {item,count}");
        }
        JsonObject output = json.getAsJsonObject("output");
        String outputItem = output.has("item") ? output.get("item").getAsString() : "";
        long outputCount = output.has("count") ? output.get("count").getAsLong() : 0L;
        double rate = json.has("batches_per_minute_per_worker")
                ? json.get("batches_per_minute_per_worker").getAsDouble() : 0.0;
        if (!validItem(outputItem) || outputCount <= 0 || !Double.isFinite(rate) || rate <= 0 || rate > 10000) {
            throw new IllegalArgumentException("output 或 batches_per_minute_per_worker 非法");
        }
        return new CookingRecipe(id, inputs, outputItem, outputCount, rate);
    }

    private static boolean validItem(String item) {
        return item != null && item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    }
}
