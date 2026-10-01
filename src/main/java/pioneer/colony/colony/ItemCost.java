package pioneer.colony.colony;

/**
 * 物品消耗/产出条目（datapack JSON 通用形状：{"item": "minecraft:iron_ore", "count": 4}）。
 * 物品以 id 字符串存储——数据层不接触 ItemStack，零注册表耦合。
 */
public record ItemCost(String item, long count) {
}
