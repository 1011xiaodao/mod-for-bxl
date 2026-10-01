package pioneer.colony.colony;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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

/**
 * 殖民地存档数据（主世界 DimensionDataStorage）：殖民地集合 + 玩家→殖民地映射（权威底账）。
 */
public final class ColonySavedData extends SavedData {
    private static final String DATA_NAME = "pioneer_colony";

    private final Map<UUID, Colony> colonies = new LinkedHashMap<>();
    private final Map<UUID, UUID> colonyByOwner = new HashMap<>();
    /** 解散退款的待发账户（信用点无实体形态：随该玩家下次创建殖民地发放）。 */
    private final Map<UUID, Long> pendingRefunds = new HashMap<>();

    public static ColonySavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ColonySavedData::new, ColonySavedData::load, null), DATA_NAME);
    }

    public ColonySavedData() {
    }

    public void add(Colony colony) {
        colonies.put(colony.uuid(), colony);
        colonyByOwner.put(colony.owner(), colony.uuid());
        setDirty();
    }

    public Colony colonyOf(UUID player) {
        UUID colonyId = colonyByOwner.get(player);
        return colonyId == null ? null : colonies.get(colonyId);
    }

    public Colony colonyByUuid(UUID colonyId) {
        return colonies.get(colonyId);
    }

    public Collection<Colony> all() {
        return colonies.values();
    }

    public int countOfOwner(UUID player) {
        return (int) colonies.values().stream().filter(c -> c.owner().equals(player)).count();
    }

    public void remove(UUID colonyId) {
        Colony colony = colonies.remove(colonyId);
        if (colony != null) {
            colonyByOwner.remove(colony.owner());
            setDirty();
        }
    }

    public void addRefund(UUID player, long amount) {
        pendingRefunds.merge(player, amount, Long::sum);
        setDirty();
    }

    /** 取走待发退款（建殖民地时）。 */
    public long takeRefund(UUID player) {
        Long value = pendingRefunds.remove(player);
        if (value != null) {
            setDirty();
        }
        return value == null ? 0L : value;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Colony colony : colonies.values()) {
            list.add(colony.save());
        }
        tag.put("colonies", list);
        ListTag refundList = new ListTag();
        for (Map.Entry<UUID, Long> e : pendingRefunds.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("player", e.getKey());
            t.putLong("amount", e.getValue());
            refundList.add(t);
        }
        tag.put("pendingRefunds", refundList);
        return tag;
    }

    public static ColonySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ColonySavedData data = new ColonySavedData();
        ListTag list = tag.getList("colonies", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Colony colony = Colony.load(list.getCompound(i));
            data.colonies.put(colony.uuid(), colony);
            data.colonyByOwner.put(colony.owner(), colony.uuid());
        }
        ListTag refundList = tag.getList("pendingRefunds", Tag.TAG_COMPOUND);
        for (int i = 0; i < refundList.size(); i++) {
            CompoundTag t = refundList.getCompound(i);
            data.pendingRefunds.put(t.getUUID("player"), t.getLong("amount"));
        }
        return data;
    }
}
