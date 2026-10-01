package pioneer.colony.colony;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import pioneer.colony.config.Config;
import pioneer.colony.economy.OfflineSummary;

/**
 * 殖民地数据模型（06 §3.1）。每玩家 1 个（可配），全部为存档数据（SavedData 持久化），
 * 主线程独占访问；经济 tick 只做纯数据公式运算，与区块加载完全解耦。
 * 威胁值/研究进度等字段随对应里程碑（M6.3/M6.4）激活，字段先留位。
 */
public final class Colony {
    public enum HqState {
        /** 正常 */
        NORMAL,
        /** 停摆待修复（公式暂停、数据保留；M6.4 启用） */
        STOPPED
    }

    private final UUID uuid;
    private UUID owner;
    private String name;
    private String dimension;
    private long hqChunk;
    private HqState hqState = HqState.NORMAL;
    private final Map<String, LongSet> territory = new LinkedHashMap<>();
    private final List<BuildingInstance> buildings = new ArrayList<>();
    private int population;
    private final LinkedHashMap<String, Long> buffer = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> pseudoStorage = new LinkedHashMap<>();
    private long credits;
    private double threatValue = 0.0;
    private double happiness = 50.0;
    private long lastTickWallTime;
    private OfflineSummary pendingSummary;
    private double starveMinutes = 0.0;
    private double growthProgress = 0.0;

    public Colony(UUID uuid, UUID owner, String name, String dimension, int blockX, int blockZ) {
        this.uuid = uuid;
        this.owner = owner;
        this.name = name;
        this.dimension = dimension;
        this.hqChunk = net.minecraft.world.level.ChunkPos.asLong(blockX >> 4, blockZ >> 4);
        this.lastTickWallTime = 0L;
    }

    // —— 领地（M6.1 数据结构就位；购买/保护流程 M6.2） ——

    public void claimChunk(String dimensionKey, int chunkX, int chunkZ) {
        territory.computeIfAbsent(dimensionKey, k -> new LongOpenHashSet())
                .add(net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ));
    }

    public boolean ownsChunk(String dimensionKey, int chunkX, int chunkZ) {
        LongSet set = territory.get(dimensionKey);
        return set != null && set.contains(net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ));
    }

    public int territorySize() {
        int n = 0;
        for (LongSet set : territory.values()) {
            n += set.size();
        }
        return n;
    }

    public Collection<Map.Entry<String, LongSet>> territoryEntries() {
        return territory.entrySet();
    }

    // —— 资源缓冲（容量 = 基础 + 活跃仓库类建筑加成） ——

    public int bufferTypeCapacity() {
        int cap = Config.BUFFER_BASE_TYPES.get();
        for (BuildingInstance b : buildings) {
            if (b.status() == BuildingInstance.Status.ACTIVE) {
                cap += BuildingDefinitions.get(b.definitionId()).map(d -> d.tierBufferSlots(b.tier())).orElse(0);
            }
        }
        return cap;
    }

    public long bufferPerTypeCapacity() {
        long cap = Config.BUFFER_PER_TYPE_CAPACITY.get();
        for (BuildingInstance b : buildings) {
            if (b.status() == BuildingInstance.Status.ACTIVE) {
                cap += BuildingDefinitions.get(b.definitionId()).map(d -> (long) d.tierBufferPerTypeCapacity(b.tier())).orElse(0L);
            }
        }
        return cap;
    }

    /** 产出入缓冲：受种类与单类容量双上限约束，返回实际接收量。 */
    public long insertOutput(String item, long count) {
        if (count <= 0) {
            return 0;
        }
        boolean isNew = !buffer.containsKey(item);
        if (isNew && buffer.size() >= bufferTypeCapacity()) {
            return 0;
        }
        long current = buffer.getOrDefault(item, 0L);
        long accepted = Math.min(count, bufferPerTypeCapacity() - current);
        if (accepted <= 0) {
            return 0;
        }
        buffer.put(item, current + accepted);
        return accepted;
    }

    /** 从缓冲取物，返回实际取出量。 */
    public long takeItems(String item, long count) {
        if (count <= 0 || !buffer.containsKey(item)) {
            return 0;
        }
        long current = buffer.get(item);
        long taken = Math.min(current, count);
        if (taken >= current) {
            buffer.remove(item);
        } else {
            buffer.put(item, current - taken);
        }
        return taken;
    }

    public long countOf(String item) {
        return buffer.getOrDefault(item, 0L);
    }

    /** 缓冲只读快照（跨线程传不可变快照纪律）。 */
    public Map<String, Long> bufferSnapshot() {
        return Map.copyOf(buffer);
    }

    public void depositPseudoStorage(String item, long count) {
        pseudoStorage.merge(item, count, Long::sum);
    }

    public Map<String, Long> pseudoStorageSnapshot() {
        return Map.copyOf(pseudoStorage);
    }

    // —— 建筑 ——

    public void addBuilding(BuildingInstance building) {
        buildings.add(building);
    }

    public boolean removeBuildingByDefinition(String definitionId) {
        for (int i = 0; i < buildings.size(); i++) {
            if (buildings.get(i).definitionId().equals(definitionId)) {
                buildings.remove(i);
                return true;
            }
        }
        return false;
    }

    public List<BuildingInstance> getBuildings() {
        return buildings;
    }

    // —— NBT ——

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("uuid", uuid);
        tag.putUUID("owner", owner);
        tag.putString("name", name);
        tag.putString("dimension", dimension);
        tag.putLong("hqChunk", hqChunk);
        tag.putString("hqState", hqState.name());
        tag.putInt("population", population);
        tag.putLong("credits", credits);
        tag.putDouble("threatValue", threatValue);
        tag.putDouble("happiness", happiness);
        tag.putLong("lastTickWallTime", lastTickWallTime);
        tag.putDouble("starveMinutes", starveMinutes);
        tag.putDouble("growthProgress", growthProgress);

        ListTag territoryList = new ListTag();
        for (Map.Entry<String, LongSet> e : territory.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString("dim", e.getKey());
            t.putLongArray("chunks", e.getValue().toLongArray());
            territoryList.add(t);
        }
        tag.put("territory", territoryList);

        ListTag buildingList = new ListTag();
        for (BuildingInstance b : buildings) {
            buildingList.add(b.save());
        }
        tag.put("buildings", buildingList);

        tag.put("buffer", saveItems(buffer));
        tag.put("pseudoStorage", saveItems(pseudoStorage));

        if (pendingSummary != null) {
            tag.put("pendingSummary", pendingSummary.save());
        }
        return tag;
    }

    public static Colony load(CompoundTag tag) {
        Colony colony = new Colony(tag.getUUID("uuid"), tag.getUUID("owner"),
                tag.getString("name"), tag.getString("dimension"), 0, 0);
        colony.hqChunk = tag.getLong("hqChunk");
        try {
            colony.hqState = HqState.valueOf(tag.getString("hqState"));
        } catch (IllegalArgumentException e) {
            colony.hqState = HqState.NORMAL;
        }
        colony.population = tag.getInt("population");
        colony.credits = tag.getLong("credits");
        colony.threatValue = tag.getDouble("threatValue");
        colony.happiness = tag.contains("happiness") ? tag.getDouble("happiness") : 50.0;
        colony.lastTickWallTime = tag.getLong("lastTickWallTime");
        colony.starveMinutes = tag.getDouble("starveMinutes");
        colony.growthProgress = tag.getDouble("growthProgress");

        ListTag territoryList = tag.getList("territory", Tag.TAG_COMPOUND);
        for (int i = 0; i < territoryList.size(); i++) {
            CompoundTag t = territoryList.getCompound(i);
            long[] chunks = t.getLongArray("chunks");
            LongSet set = colony.territory.computeIfAbsent(t.getString("dim"), k -> new LongOpenHashSet());
            for (long c : chunks) {
                set.add(c);
            }
        }

        ListTag buildingList = tag.getList("buildings", Tag.TAG_COMPOUND);
        for (int i = 0; i < buildingList.size(); i++) {
            colony.buildings.add(BuildingInstance.load(buildingList.getCompound(i)));
        }

        loadItems(colony.buffer, tag.getList("buffer", Tag.TAG_COMPOUND));
        loadItems(colony.pseudoStorage, tag.getList("pseudoStorage", Tag.TAG_COMPOUND));

        if (tag.contains("pendingSummary")) {
            colony.pendingSummary = OfflineSummary.load(tag.getCompound("pendingSummary"));
        }
        return colony;
    }

    private static ListTag saveItems(LinkedHashMap<String, Long> items) {
        ListTag list = new ListTag();
        for (Map.Entry<String, Long> e : items.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString("item", e.getKey());
            t.putLong("count", e.getValue());
            list.add(t);
        }
        return list;
    }

    private static void loadItems(LinkedHashMap<String, Long> target, ListTag list) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            target.put(t.getString("item"), t.getLong("count"));
        }
    }

    // —— 访问器 ——

    public UUID uuid() {
        return uuid;
    }

    public UUID owner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public String getDimension() {
        return dimension;
    }

    public void setHqChunk(long hqChunk) {
        this.hqChunk = hqChunk;
    }

    public long getHqChunk() {
        return hqChunk;
    }

    public HqState getHqState() {
        return hqState;
    }

    public void setHqState(HqState hqState) {
        this.hqState = hqState;
    }

    public int getPopulation() {
        return population;
    }

    public void setPopulation(int population) {
        this.population = Math.max(0, population);
    }

    public long getCredits() {
        return credits;
    }

    /** 加/扣信用点（余额不为负），返回调整后余额。 */
    public long adjustCredits(long delta) {
        credits = Math.max(0, credits + delta);
        return credits;
    }

    public void setCredits(long credits) {
        this.credits = Math.max(0, credits);
    }

    public double getThreatValue() {
        return threatValue;
    }

    public void setThreatValue(double threatValue) {
        this.threatValue = Math.max(0, threatValue);
    }

    public double getHappiness() {
        return happiness;
    }

    public void setHappiness(double happiness) {
        this.happiness = Math.max(0.0, Math.min(100.0, happiness));
    }

    public long getLastTickWallTime() {
        return lastTickWallTime;
    }

    public void setLastTickWallTime(long lastTickWallTime) {
        this.lastTickWallTime = lastTickWallTime;
    }

    public OfflineSummary getPendingSummary() {
        return pendingSummary;
    }

    public void setPendingSummary(OfflineSummary pendingSummary) {
        this.pendingSummary = pendingSummary;
    }

    public OfflineSummary pollPendingSummary() {
        OfflineSummary s = pendingSummary;
        pendingSummary = null;
        return s;
    }

    public double getStarveMinutes() {
        return starveMinutes;
    }

    public void setStarveMinutes(double starveMinutes) {
        this.starveMinutes = Math.max(0, starveMinutes);
    }

    public double getGrowthProgress() {
        return growthProgress;
    }

    public void setGrowthProgress(double growthProgress) {
        this.growthProgress = Math.max(0, growthProgress);
    }
}
