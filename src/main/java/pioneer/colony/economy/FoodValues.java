package pioneer.colony.economy;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import pioneer.colony.config.Config;

/**
 * 食物点体系（06 §3.2）：食物点 = 该物品的饱和度值（自动读取、零配置）
 * × 全局倍率 − 个别物品覆盖表（config）。非食物为 0。
 */
public final class FoodValues {
    private FoodValues() {
    }

    /** 物品的食物点；非食物/未知物品返回 0。 */
    public static double foodValue(String itemId) {
        double override = lookupOverride(itemId);
        if (override >= 0) {
            return override;
        }
        Item item = resolveItem(itemId);
        if (item == null) {
            return 0.0;
        }
        FoodProperties food = new net.minecraft.world.item.ItemStack(item).getFoodProperties(null);
        if (food == null) {
            return 0.0;
        }
        // saturation() 即该物品实际恢复的饱和度值（= nutrition × modifier × 2，面包 6.0），
        // 06 §3.2 定稿「食物点 = 该物品的饱和度值」——直接采用，勿再乘 nutrition
        return food.saturation() * Config.FOOD_GLOBAL_MULTIPLIER.get();
    }

    private static Item resolveItem(String itemId) {
        try {
            return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId)).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 覆盖表未命中返回 -1。 */
    private static double lookupOverride(String itemId) {
        List<? extends String> overrides = Config.FOOD_VALUE_OVERRIDES.get();
        for (String entry : overrides) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (entry.substring(0, eq).trim().equals(itemId)) {
                try {
                    return Double.parseDouble(entry.substring(eq + 1).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }
}
