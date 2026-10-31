package pioneer.colony.economy;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import pioneer.colony.PioneerColony;

/** 烹饪配方数据包加载器：data/<namespace>/pioneer_colony/cooking/*.json。 */
public final class CookingRecipes extends SimpleJsonResourceReloadListener {
    private static volatile Map<String, CookingRecipe> recipes = Map.of();

    public CookingRecipes() {
        super(new Gson(), "pioneer_colony/cooking");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, CookingRecipe> loaded = new LinkedHashMap<>();
        files.forEach((key, element) -> {
            if (!element.isJsonObject()) {
                PioneerColony.LOGGER.error("[殖民地经营] 烹饪配方加载失败（已跳过）：{} —— 顶层须为对象", key);
                return;
            }
            try {
                CookingRecipe recipe = CookingRecipe.parse(key.toString(), element.getAsJsonObject());
                if (!registeredItem(recipe.outputItem())
                        || recipe.inputs().stream().anyMatch(input -> !registeredItem(input.item()))) {
                    throw new IllegalArgumentException("配方引用了未注册物品 ID");
                }
                loaded.put(key.toString(), recipe);
            } catch (Exception error) {
                PioneerColony.LOGGER.error("[殖民地经营] 烹饪配方加载失败（已跳过）：{} —— {}", key, error.getMessage());
            }
        });
        recipes = Map.copyOf(loaded);
        PioneerColony.LOGGER.info("[殖民地经营] 烹饪配方加载完成：{} 条", recipes.size());
    }

    private static boolean registeredItem(String id) {
        try {
            return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id)).isPresent();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static Map<String, CookingRecipe> all() {
        return recipes;
    }
}
