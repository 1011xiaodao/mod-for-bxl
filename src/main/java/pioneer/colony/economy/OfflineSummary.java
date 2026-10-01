package pioneer.colony.economy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 离线汇总（06 §3.3）：上线时聊天栏推送一条（离线时长/产出/消耗/成交/事件）。
 * 随殖民地 NBT 持久化——服务器重启后仍未领取的汇总不丢。
 */
public final class OfflineSummary {
    private final long settledMinutes;
    private final boolean capped;
    private final long settledAtWall;
    private final long creditsDelta;
    private final LinkedHashMap<String, Long> produced;
    private final LinkedHashMap<String, Long> consumed;
    private final List<String> events;

    public OfflineSummary(long settledMinutes, boolean capped, long settledAtWall, long creditsDelta,
                          Map<String, Long> produced, Map<String, Long> consumed, List<String> events) {
        this.settledMinutes = settledMinutes;
        this.capped = capped;
        this.settledAtWall = settledAtWall;
        this.creditsDelta = creditsDelta;
        this.produced = new LinkedHashMap<>(produced);
        this.consumed = new LinkedHashMap<>(consumed);
        this.events = List.copyOf(events);
    }

    public List<Component> displayLines() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(String.format("【殖民地经营】离线汇总：离线 %d 小时 %d 分钟%s",
                settledMinutes / 60, settledMinutes % 60, capped ? "（超出补算上限部分未结算）" : "")));
        lines.add(sectionLine("产出", produced));
        lines.add(sectionLine("消耗", consumed));
        lines.add(Component.literal(String.format("信用点变化：%+d", creditsDelta)));
        if (events.isEmpty()) {
            lines.add(Component.literal("事件：无"));
        } else {
            lines.add(Component.literal("事件："));
            for (String event : events) {
                lines.add(Component.literal(" · " + event));
            }
        }
        return lines;
    }

    private static Component sectionLine(String label, Map<String, Long> items) {
        if (items.isEmpty()) {
            return Component.literal(label + "：无");
        }
        StringBuilder sb = new StringBuilder(label + "：");
        boolean first = true;
        for (Map.Entry<String, Long> e : items.entrySet()) {
            if (!first) {
                sb.append("、");
            }
            sb.append(itemName(e.getKey())).append("×").append(e.getValue());
            first = false;
        }
        return Component.literal(sb.toString());
    }

    private static String itemName(String itemId) {
        try {
            return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId))
                    .map(ItemStack::new)
                    .map(s -> s.getHoverName().getString())
                    .orElse(itemId);
        } catch (Exception e) {
            return itemId;
        }
    }

    // —— NBT ——

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("settledMinutes", settledMinutes);
        tag.putBoolean("capped", capped);
        tag.putLong("settledAtWall", settledAtWall);
        tag.putLong("creditsDelta", creditsDelta);
        tag.put("produced", saveItems(produced));
        tag.put("consumed", saveItems(consumed));
        ListTag eventList = new ListTag();
        for (String event : events) {
            eventList.add(net.minecraft.nbt.StringTag.valueOf(event));
        }
        tag.put("events", eventList);
        return tag;
    }

    private static ListTag saveItems(Map<String, Long> items) {
        ListTag list = new ListTag();
        for (Map.Entry<String, Long> e : items.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString("item", e.getKey());
            t.putLong("count", e.getValue());
            list.add(t);
        }
        return list;
    }

    public static OfflineSummary load(CompoundTag tag) {
        LinkedHashMap<String, Long> produced = new LinkedHashMap<>();
        LinkedHashMap<String, Long> consumed = new LinkedHashMap<>();
        loadItems(produced, tag.getList("produced", Tag.TAG_COMPOUND));
        loadItems(consumed, tag.getList("consumed", Tag.TAG_COMPOUND));
        List<String> events = new ArrayList<>();
        ListTag eventList = tag.getList("events", Tag.TAG_STRING);
        for (int i = 0; i < eventList.size(); i++) {
            events.add(eventList.getString(i));
        }
        return new OfflineSummary(tag.getLong("settledMinutes"), tag.getBoolean("capped"),
                tag.getLong("settledAtWall"), tag.getLong("creditsDelta"), produced, consumed, events);
    }

    private static void loadItems(Map<String, Long> target, ListTag list) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            target.put(t.getString("item"), t.getLong("count"));
        }
    }

    public long getSettledMinutes() {
        return settledMinutes;
    }

    public long getSettledAtWall() {
        return settledAtWall;
    }
}
