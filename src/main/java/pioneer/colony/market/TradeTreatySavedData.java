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

/** 城市间贸易协定的服务器级持久账本；邀约和履约均不要求两位总督同时在线。 */
public final class TradeTreatySavedData extends SavedData {
    private static final String DATA_NAME = "pioneer_colony_trade_treaties";
    private final Map<Long, Treaty> treaties = new LinkedHashMap<>();
    private long nextId = 1L;
    private long lastTickWallTime;

    public static TradeTreatySavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(TradeTreatySavedData::new, TradeTreatySavedData::load, null), DATA_NAME);
    }

    public Collection<Treaty> treaties() { return List.copyOf(treaties.values()); }
    public Treaty treaty(long id) { return treaties.get(id); }
    public long nextId() { return nextId++; }
    public void add(Treaty treaty) { treaties.put(treaty.id(), treaty); setDirty(); }
    public void remove(long id) { if (treaties.remove(id) != null) setDirty(); }
    public long lastTickWallTime() { return lastTickWallTime; }
    public void setLastTickWallTime(long value) { lastTickWallTime = value; setDirty(); }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("nextId", nextId);
        tag.putLong("lastTickWallTime", lastTickWallTime);
        ListTag list = new ListTag();
        for (Treaty treaty : treaties.values()) list.add(treaty.save());
        tag.put("treaties", list);
        return tag;
    }

    public static TradeTreatySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TradeTreatySavedData data = new TradeTreatySavedData();
        data.nextId = Math.max(1L, tag.getLong("nextId"));
        data.lastTickWallTime = tag.getLong("lastTickWallTime");
        ListTag list = tag.getList("treaties", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Treaty treaty = Treaty.load(list.getCompound(i));
            data.treaties.put(treaty.id(), treaty);
            data.nextId = Math.max(data.nextId, treaty.id() + 1L);
        }
        return data;
    }

    public enum Status { INVITED, ACTIVE, TERMINATING }

    public static final class Treaty {
        private final long id;
        private final UUID fromColony;
        private final UUID toColony;
        private final UUID fromOwner;
        private final UUID toOwner;
        private final String exportItem;
        private final long exportPerHour;
        /** 空串或 0 表示不要求返还；"credits" 表示以信用点结算。 */
        private final String returnItem;
        private final long returnPerHour;
        private final long createdAt;
        private final long expiresAt;
        private Status status = Status.INVITED;
        private long terminationAt;
        private double exportCarry;
        private double returnCarry;

        public Treaty(long id, UUID fromColony, UUID toColony, UUID fromOwner, UUID toOwner,
                      String exportItem, long exportPerHour, String returnItem, long returnPerHour,
                      long createdAt, long expiresAt) {
            this.id = id;
            this.fromColony = fromColony;
            this.toColony = toColony;
            this.fromOwner = fromOwner;
            this.toOwner = toOwner;
            this.exportItem = exportItem;
            this.exportPerHour = exportPerHour;
            this.returnItem = returnItem == null ? "" : returnItem;
            this.returnPerHour = returnPerHour;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        public long id() { return id; }
        public UUID fromColony() { return fromColony; }
        public UUID toColony() { return toColony; }
        public UUID fromOwner() { return fromOwner; }
        public UUID toOwner() { return toOwner; }
        public String exportItem() { return exportItem; }
        public long exportPerHour() { return exportPerHour; }
        public String returnItem() { return returnItem; }
        public long returnPerHour() { return returnPerHour; }
        public long createdAt() { return createdAt; }
        public long expiresAt() { return expiresAt; }
        public Status status() { return status; }
        public long terminationAt() { return terminationAt; }
        public double exportCarry() { return exportCarry; }
        public double returnCarry() { return returnCarry; }

        public void activate() { status = Status.ACTIVE; }
        public void terminateAt(long time) { status = Status.TERMINATING; terminationAt = time; }
        public void setCarries(double exportValue, double returnValue) {
            exportCarry = validCarry(exportValue);
            returnCarry = validCarry(returnValue);
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("id", id);
            tag.putUUID("fromColony", fromColony);
            tag.putUUID("toColony", toColony);
            tag.putUUID("fromOwner", fromOwner);
            tag.putUUID("toOwner", toOwner);
            tag.putString("exportItem", exportItem);
            tag.putLong("exportPerHour", exportPerHour);
            tag.putString("returnItem", returnItem);
            tag.putLong("returnPerHour", returnPerHour);
            tag.putLong("createdAt", createdAt);
            tag.putLong("expiresAt", expiresAt);
            tag.putString("status", status.name());
            tag.putLong("terminationAt", terminationAt);
            tag.putDouble("exportCarry", exportCarry);
            tag.putDouble("returnCarry", returnCarry);
            return tag;
        }

        public static Treaty load(CompoundTag tag) {
            Treaty treaty = new Treaty(Math.max(1, tag.getLong("id")),
                    tag.getUUID("fromColony"), tag.getUUID("toColony"),
                    tag.getUUID("fromOwner"), tag.getUUID("toOwner"),
                    tag.getString("exportItem"), Math.max(0, tag.getLong("exportPerHour")),
                    tag.getString("returnItem"), Math.max(0, tag.getLong("returnPerHour")),
                    tag.getLong("createdAt"), tag.getLong("expiresAt"));
            try { treaty.status = Status.valueOf(tag.getString("status")); }
            catch (IllegalArgumentException ignored) { treaty.status = Status.INVITED; }
            treaty.terminationAt = tag.getLong("terminationAt");
            treaty.setCarries(tag.getDouble("exportCarry"), tag.getDouble("returnCarry"));
            return treaty;
        }

        private static double validCarry(double value) {
            return Double.isFinite(value) ? Math.max(0.0, Math.min(0.999999999, value)) : 0.0;
        }
    }
}
