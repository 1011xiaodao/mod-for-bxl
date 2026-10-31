package pioneer.colony.market;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** 服务端共享市场账本。仅由服务端主线程修改。 */
public final class MarketSavedData extends SavedData {
    private static final String DATA_NAME = "pioneer_colony_market";
    private final Map<Long, Order> orders = new LinkedHashMap<>();
    private final Map<String, Double> scores = new LinkedHashMap<>();
    private final Map<String, Long> demand = new LinkedHashMap<>();
    private final Map<String, Long> supply = new LinkedHashMap<>();
    private final Map<UUID, Long> dailyVolume = new LinkedHashMap<>();
    private long nextId = 1L;
    private long lastMatch;
    private long lastPriceUpdate;
    private long volumeDay = -1L;

    public static MarketSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MarketSavedData::new, MarketSavedData::load, null), DATA_NAME);
    }

    public Collection<Order> orders() { return List.copyOf(orders.values()); }
    public Order order(long id) { return orders.get(id); }
    public long nextId() { return nextId++; }
    public void addOrder(Order order) { orders.put(order.id(), order); setDirty(); }
    public void removeOrder(long id) { if (orders.remove(id) != null) setDirty(); }
    public double score(String item) { return scores.getOrDefault(item, 0.0); }
    public long lastMatch() { return lastMatch; }
    public void setLastMatch(long value) { lastMatch = value; setDirty(); }
    public long lastPriceUpdate() { return lastPriceUpdate; }
    public void setLastPriceUpdate(long value) { lastPriceUpdate = value; setDirty(); }

    public void recordFlow(String item, Side side, long quantity) {
        if (quantity <= 0) return;
        (side == Side.BUY ? demand : supply).merge(item, quantity, MarketSavedData::safeAdd);
        setDirty();
    }

    /** 订单成交/撤销时移除对应的未成交供需压力，避免已结束订单继续操纵行情。 */
    public void removeFlow(String item, Side side, long quantity) {
        if (quantity <= 0) return;
        Map<String, Long> target = side == Side.BUY ? demand : supply;
        long current = target.getOrDefault(item, 0L);
        if (quantity >= current) target.remove(item);
        else target.put(item, current - quantity);
        setDirty();
    }

    public void updateScores() {
        java.util.Set<String> items = new java.util.LinkedHashSet<>();
        items.addAll(scores.keySet());
        items.addAll(demand.keySet());
        items.addAll(supply.keySet());
        for (String item : items) {
            long bought = demand.getOrDefault(item, 0L);
            long sold = supply.getOrDefault(item, 0L);
            double total = (double) bought + sold;
            double pressure = total == 0 ? 0 : (bought - sold) / total;
            scores.put(item, Math.max(-1, Math.min(1, score(item) * 0.70 + pressure * 0.30)));
        }
        // 保留当前未成交订单的供需压力，成交/撤单时由 removeFlow 精确扣除。
        setDirty();
    }

    public void rollDailyVolume(long now) {
        rollDay(now);
    }

    public long dailyVolume(UUID colonyId, long now) {
        rollDay(now);
        return dailyVolume.getOrDefault(colonyId, 0L);
    }

    public void addDailyVolume(UUID colonyId, long amount, long now) {
        rollDay(now);
        dailyVolume.merge(colonyId, amount, MarketSavedData::safeAdd);
        setDirty();
    }

    private void rollDay(long now) {
        long day = Math.floorDiv(now, 86_400_000L);
        if (day != volumeDay) {
            volumeDay = day;
            dailyVolume.clear();
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("nextId", nextId);
        tag.putLong("lastMatch", lastMatch);
        tag.putLong("lastPriceUpdate", lastPriceUpdate);
        tag.putLong("volumeDay", volumeDay);
        ListTag orderTags = new ListTag();
        for (Order order : orders.values()) orderTags.add(order.save());
        tag.put("orders", orderTags);
        tag.put("scores", saveDoubles(scores));
        tag.put("demand", saveLongs(demand));
        tag.put("supply", saveLongs(supply));
        ListTag volumeTags = new ListTag();
        for (Map.Entry<UUID, Long> entry : dailyVolume.entrySet()) {
            CompoundTag value = new CompoundTag();
            value.putUUID("colony", entry.getKey());
            value.putLong("amount", entry.getValue());
            volumeTags.add(value);
        }
        tag.put("dailyVolume", volumeTags);
        return tag;
    }

    public static MarketSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        MarketSavedData data = new MarketSavedData();
        data.nextId = Math.max(1, tag.getLong("nextId"));
        data.lastMatch = tag.getLong("lastMatch");
        data.lastPriceUpdate = tag.getLong("lastPriceUpdate");
        data.volumeDay = tag.getLong("volumeDay");
        ListTag orderTags = tag.getList("orders", Tag.TAG_COMPOUND);
        for (int i = 0; i < orderTags.size(); i++) {
            Order order = Order.load(orderTags.getCompound(i));
            if (order.remainingQuantity() <= 0 || order.item().isBlank()) continue;
            if (order.side() == Side.BUY && (order.reserveUnitPrice() <= 0
                    || order.remainingQuantity() > order.escrowCredits() / order.reserveUnitPrice())) continue;
            data.orders.put(order.id(), order);
            data.nextId = Math.max(data.nextId, order.id() + 1);
        }
        loadDoubles(data.scores, tag.getList("scores", Tag.TAG_COMPOUND));
        // 供需压力从有效未成交订单重建，避免旧版/损坏存档中的孤儿压力长期影响价格。
        data.demand.clear();
        data.supply.clear();
        for (Order order : data.orders.values()) {
            (order.side() == Side.BUY ? data.demand : data.supply)
                    .merge(order.item(), order.remainingQuantity(), MarketSavedData::safeAdd);
        }
        ListTag volumeTags = tag.getList("dailyVolume", Tag.TAG_COMPOUND);
        for (int i = 0; i < volumeTags.size(); i++) {
            CompoundTag value = volumeTags.getCompound(i);
            data.dailyVolume.put(value.getUUID("colony"), Math.max(0, value.getLong("amount")));
        }
        return data;
    }

    private static ListTag saveDoubles(Map<String, Double> values) {
        ListTag list = new ListTag();
        values.forEach((key, number) -> {
            CompoundTag tag = new CompoundTag();
            tag.putString("key", key);
            tag.putDouble("value", number);
            list.add(tag);
        });
        return list;
    }

    private static void loadDoubles(Map<String, Double> target, ListTag list) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            double value = tag.getDouble("value");
            if (Double.isFinite(value)) target.put(tag.getString("key"), Math.max(-1, Math.min(1, value)));
        }
    }

    private static ListTag saveLongs(Map<String, Long> values) {
        ListTag list = new ListTag();
        values.forEach((key, number) -> {
            CompoundTag tag = new CompoundTag();
            tag.putString("key", key);
            tag.putLong("value", number);
            list.add(tag);
        });
        return list;
    }

    private static void loadLongs(Map<String, Long> target, ListTag list) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            target.put(tag.getString("key"), Math.max(0, tag.getLong("value")));
        }
    }

    private static long safeAdd(long a, long b) {
        try { return Math.addExact(a, b); } catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }

    public enum Side { BUY, SELL }

    public static final class Order {
        private final long id;
        private final UUID colonyId;
        private final UUID ownerId;
        private final String item;
        private final Side side;
        private final long originalQuantity;
        private long remainingQuantity;
        private final long limitPrice;
        private final long reserveUnitPrice;
        private long escrowCredits;
        private final long createdAt;
        private final long expiresAt;

        public Order(long id, UUID colonyId, UUID ownerId, String item, Side side, long quantity,
                     long limitPrice, long reserveUnitPrice, long escrowCredits, long createdAt, long expiresAt) {
            this.id = id;
            this.colonyId = colonyId;
            this.ownerId = ownerId;
            this.item = item;
            this.side = side;
            this.originalQuantity = quantity;
            this.remainingQuantity = quantity;
            this.limitPrice = limitPrice;
            this.reserveUnitPrice = reserveUnitPrice;
            this.escrowCredits = escrowCredits;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        public long id() { return id; }
        public UUID colonyId() { return colonyId; }
        public UUID ownerId() { return ownerId; }
        public String item() { return item; }
        public Side side() { return side; }
        public long originalQuantity() { return originalQuantity; }
        public long remainingQuantity() { return remainingQuantity; }
        public long limitPrice() { return limitPrice; }
        public long reserveUnitPrice() { return reserveUnitPrice; }
        public long escrowCredits() { return escrowCredits; }
        public long createdAt() { return createdAt; }
        public long expiresAt() { return expiresAt; }
        public void fill(long quantity, long reserveSpent) {
            remainingQuantity = Math.max(0, remainingQuantity - quantity);
            escrowCredits = Math.max(0, escrowCredits - reserveSpent);
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("id", id);
            tag.putUUID("colonyId", colonyId);
            tag.putUUID("ownerId", ownerId);
            tag.putString("item", item);
            tag.putString("side", side.name());
            tag.putLong("originalQuantity", originalQuantity);
            tag.putLong("remainingQuantity", remainingQuantity);
            tag.putLong("limitPrice", limitPrice);
            tag.putLong("reserveUnitPrice", reserveUnitPrice);
            tag.putLong("escrowCredits", escrowCredits);
            tag.putLong("createdAt", createdAt);
            tag.putLong("expiresAt", expiresAt);
            return tag;
        }

        public static Order load(CompoundTag tag) {
            Side side;
            try { side = Side.valueOf(tag.getString("side")); } catch (IllegalArgumentException ignored) { side = Side.SELL; }
            long original = Math.max(1, tag.getLong("originalQuantity"));
            Order order = new Order(Math.max(1, tag.getLong("id")), tag.getUUID("colonyId"), tag.getUUID("ownerId"),
                    tag.getString("item"), side, original, Math.max(0, tag.getLong("limitPrice")),
                    Math.max(0, tag.getLong("reserveUnitPrice")), Math.max(0, tag.getLong("escrowCredits")),
                    tag.getLong("createdAt"), tag.getLong("expiresAt"));
            order.remainingQuantity = Math.max(0, Math.min(original, tag.getLong("remainingQuantity")));
            return order;
        }
    }
}
