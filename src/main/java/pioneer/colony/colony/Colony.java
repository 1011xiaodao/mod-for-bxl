package pioneer.colony.colony;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import pioneer.colony.config.Config;
import pioneer.colony.economy.OfflineSummary;

/**
 * 殖民地数据模型（06 §3.1/§4）。每玩家 1 个（可配），全部为存档数据（SavedData 持久化），
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

    /** 信任名单两档权限（06 §4）：访客=通行+贸易站交互；可信=+开建筑 GUI 收货。 */
    public enum Trust {
        VISITOR,
        TRUSTED
    }

    private final UUID uuid;
    private UUID owner;
    private String name;
    private String dimension;
    private long hqChunk;
    private HqState hqState = HqState.NORMAL;
    /** 领地：维度 → (区块 → 实付购地费，免费块=0)。退地按实付价退款。 */
    private final Map<String, Long2LongOpenHashMap> territory = new LinkedHashMap<>();
    private final Map<UUID, Trust> trust = new LinkedHashMap<>();
    private long lastPurchaseWallTime = 0;
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
    /** colony 通道研究效果：建筑标签 → 产量加成百分比（叠加求和）。 */
    private final Map<String, Double> researchProductionBonus = new LinkedHashMap<>();
    /** colony 通道研究效果：市民上限加成。 */
    private int researchCitizenCapBonus = 0;
    /** 总部耐久（M6.4：打空 → 停摆待修复）。 */
    private double hqHealth = 200.0;
    /** M6.5 日程就餐已实扣的食物点（经济 tick 从人口需求中抵扣，防双重记账；残留滚存下期）。 */
    private double fedPointsCarry = 0.0;

    public Colony(UUID uuid, UUID owner, String name, String dimension, int blockX, int blockZ) {
        this.uuid = uuid;
        this.owner = owner;
        this.name = name;
        this.dimension = dimension;
        this.hqChunk = ChunkPos.asLong(blockX >> 4, blockZ >> 4);
        this.lastTickWallTime = 0L;
    }

    // —— 领地（M6.2：购买/退地/相邻校验） ——

    public void claimChunk(String dimensionKey, int chunkX, int chunkZ, long paidPrice) {
        territory.computeIfAbsent(dimensionKey, k -> new Long2LongOpenHashMap())
                .put(ChunkPos.asLong(chunkX, chunkZ), paidPrice);
    }

    public boolean ownsChunk(String dimensionKey, int chunkX, int chunkZ) {
        Long2LongOpenHashMap map = territory.get(dimensionKey);
        return map != null && map.containsKey(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** 区块实付购地费（免费块 0）；不拥有返回 -1。 */
    public long chunkPaidPrice(String dimensionKey, int chunkX, int chunkZ) {
        Long2LongOpenHashMap map = territory.get(dimensionKey);
        if (map == null) {
            return -1;
        }
        return map.getOrDefault(ChunkPos.asLong(chunkX, chunkZ), -1);
    }

    public boolean abandonChunk(String dimensionKey, int chunkX, int chunkZ) {
        Long2LongOpenHashMap map = territory.get(dimensionKey);
        if (map == null) {
            return false;
        }
        long key = ChunkPos.asLong(chunkX, chunkZ);
        if (!map.containsKey(key)) {
            return false;
        }
        map.remove(key);
        return true;
    }

    /** 是否与已有领地（4 向）相邻。 */
    public boolean isAdjacentToTerritory(String dimensionKey, int chunkX, int chunkZ) {
        return ownsChunk(dimensionKey, chunkX + 1, chunkZ) || ownsChunk(dimensionKey, chunkX - 1, chunkZ)
                || ownsChunk(dimensionKey, chunkX, chunkZ + 1) || ownsChunk(dimensionKey, chunkX, chunkZ - 1);
    }

    public int territorySize() {
        int n = 0;
        for (Long2LongOpenHashMap map : territory.values()) {
            n += map.size();
        }
        return n;
    }

    public Collection<Map.Entry<String, Long2LongOpenHashMap>> territoryEntries() {
        return territory.entrySet();
    }

    public int chunkCountIn(String dimensionKey) {
        Long2LongOpenHashMap map = territory.get(dimensionKey);
        return map == null ? 0 : map.size();
    }

    // —— 信任名单 ——

    public Trust trustOf(UUID player) {
        return trust.get(player);
    }

    public void setTrust(UUID player, Trust level) {
        if (level == null) {
            trust.remove(player);
        } else {
            trust.put(player, level);
        }
    }

    public Map<UUID, Trust> trustEntries() {
        return trust;
    }

    /** 是否可开建筑 GUI（总督本人或可信）。 */
    public boolean canOpenGui(UUID player) {
        return owner.equals(player) || trust.get(player) == Trust.TRUSTED;
    }

    public boolean isOwner(UUID player) {
        return owner.equals(player);
    }

    public long getLastPurchaseWallTime() {
        return lastPurchaseWallTime;
    }

    public void setLastPurchaseWallTime(long t) {
        this.lastPurchaseWallTime = t;
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

    public BuildingInstance buildingAtOrigin(long originPos) {
        for (BuildingInstance b : buildings) {
            if (b.origin() == originPos) {
                return b;
            }
        }
        return null;
    }

    /** 建筑占地是否触及某区块（按 footprint 展开的区块范围）。 */
    public boolean buildingTouchesChunk(BuildingInstance b, int chunkX, int chunkZ) {
        if (!b.hasOrigin()) {
            return false;
        }
        BlockPos origin = BlockPos.of(b.origin());
        int w = b.footprintW();
        int d = b.footprintD();
        int minCx = origin.getX() >> 4;
        int maxCx = (origin.getX() + w - 1) >> 4;
        int minCz = origin.getZ() >> 4;
        int maxCz = (origin.getZ() + d - 1) >> 4;
        return chunkX >= minCx && chunkX <= maxCx && chunkZ >= minCz && chunkZ <= maxCz;
    }

    public boolean anyBuildingTouchesChunk(String dimensionKey, int chunkX, int chunkZ) {
        if (!dimensionKey.equals(this.dimension)) {
            return false;
        }
        for (BuildingInstance b : buildings) {
            if (buildingTouchesChunk(b, chunkX, chunkZ)) {
                return true;
            }
        }
        return false;
    }

    /** 建筑占地是否与既有建筑重叠。 */
    public boolean overlapsExisting(long originPos, int w, int h, int d) {
        BlockPos p = BlockPos.of(originPos);
        for (BuildingInstance b : buildings) {
            if (!b.hasOrigin()) {
                continue;
            }
            BlockPos o = BlockPos.of(b.origin());
            boolean overlapX = p.getX() < o.getX() + b.footprintW() && o.getX() < p.getX() + w;
            boolean overlapY = p.getY() < o.getY() + b.footprintH() && o.getY() < p.getY() + h;
            boolean overlapZ = p.getZ() < o.getZ() + b.footprintD() && o.getZ() < p.getZ() + d;
            if (overlapX && overlapY && overlapZ) {
                return true;
            }
        }
        return false;
    }

    public List<BuildingInstance> getBuildings() {
        return buildings;
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

    /** 缓冲是否拥有全部物品（不扣减）。 */
    public boolean hasAll(List<ItemCost> costs) {
        for (ItemCost cost : costs) {
            if (countOf(cost.item()) < cost.count()) {
                return false;
            }
        }
        return true;
    }

    /** 扣减整组物品（假定 hasAll 已通过），返回实际扣减。 */
    public void takeAll(List<ItemCost> costs) {
        for (ItemCost cost : costs) {
            takeItems(cost.item(), cost.count());
        }
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
        ListTag bonusList = new ListTag();
        for (Map.Entry<String, Double> e : researchProductionBonus.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString("tag", e.getKey());
            t.putDouble("percent", e.getValue());
            bonusList.add(t);
        }
        tag.put("researchProductionBonus", bonusList);
        tag.putInt("researchCitizenCapBonus", researchCitizenCapBonus);
        tag.putDouble("hqHealth", hqHealth);
        tag.putDouble("fedPointsCarry", fedPointsCarry);
        tag.putLong("lastPurchaseWallTime", lastPurchaseWallTime);

        ListTag territoryList = new ListTag();
        for (Map.Entry<String, Long2LongOpenHashMap> e : territory.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString("dim", e.getKey());
            ListTag chunkList = new ListTag();
            for (Long2LongOpenHashMap.Entry ce : e.getValue().long2LongEntrySet()) {
                CompoundTag c = new CompoundTag();
                c.putLong("chunk", ce.getLongKey());
                c.putLong("paid", ce.getLongValue());
                chunkList.add(c);
            }
            t.put("chunks", chunkList);
            territoryList.add(t);
        }
        tag.put("territory", territoryList);

        ListTag trustList = new ListTag();
        for (Map.Entry<UUID, Trust> e : trust.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("player", e.getKey());
            t.putString("level", e.getValue().name());
            trustList.add(t);
        }
        tag.put("trust", trustList);

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
        ListTag bonusList = tag.getList("researchProductionBonus", Tag.TAG_COMPOUND);
        for (int i = 0; i < bonusList.size(); i++) {
            CompoundTag t = bonusList.getCompound(i);
            colony.researchProductionBonus.put(t.getString("tag"), t.getDouble("percent"));
        }
        colony.researchCitizenCapBonus = tag.getInt("researchCitizenCapBonus");
        colony.hqHealth = tag.contains("hqHealth") ? tag.getDouble("hqHealth") : 200.0;
        colony.fedPointsCarry = tag.contains("fedPointsCarry") ? tag.getDouble("fedPointsCarry") : 0.0;
        colony.lastPurchaseWallTime = tag.getLong("lastPurchaseWallTime");

        ListTag territoryList = tag.getList("territory", Tag.TAG_COMPOUND);
        for (int i = 0; i < territoryList.size(); i++) {
            CompoundTag t = territoryList.getCompound(i);
            Long2LongOpenHashMap chunks = colony.territory.computeIfAbsent(t.getString("dim"), k -> new Long2LongOpenHashMap());
            ListTag chunkList = t.getList("chunks", Tag.TAG_COMPOUND);
            for (int j = 0; j < chunkList.size(); j++) {
                CompoundTag c = chunkList.getCompound(j);
                chunks.put(c.getLong("chunk"), c.getLong("paid"));
            }
        }

        ListTag trustList = tag.getList("trust", Tag.TAG_COMPOUND);
        for (int i = 0; i < trustList.size(); i++) {
            CompoundTag t = trustList.getCompound(i);
            try {
                colony.trust.put(t.getUUID("player"), Trust.valueOf(t.getString("level")));
            } catch (IllegalArgumentException ignored) {
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

    // —— 研究效果（colony 通道 onCompleted 写入，M6.3） ——

    public void addResearchProductionBonus(String buildingTag, double percent) {
        researchProductionBonus.merge(buildingTag, percent, Double::sum);
    }

    /** 产量全局加成（小数形式，如 0.10 = +10%）：空标签加成 + 标签命中之和。 */
    public double productionBonusFor(List<String> buildingTags) {
        double bonus = researchProductionBonus.getOrDefault("", 0.0);
        for (String tag : buildingTags) {
            bonus += researchProductionBonus.getOrDefault(tag, 0.0);
        }
        return bonus / 100.0;
    }

    public int getResearchCitizenCapBonus() {
        return researchCitizenCapBonus;
    }

    public void addResearchCitizenCapBonus(int n) {
        researchCitizenCapBonus += n;
    }

    // —— 总部耐久（M6.4） ——

    public double getHqHealth() {
        return hqHealth;
    }

    /** 扣总部耐久（下限 0），返回扣后值。 */
    public double damageHq(double amount) {
        hqHealth = Math.max(0, hqHealth - Math.max(0, amount));
        return hqHealth;
    }

    public void setHqHealth(double health) {
        this.hqHealth = Math.max(0, health);
    }

    // —— 日程就餐抵扣（M6.5） ——

    public double getFedPointsCarry() {
        return fedPointsCarry;
    }

    public void setFedPointsCarry(double fedPointsCarry) {
        this.fedPointsCarry = Math.max(0, fedPointsCarry);
    }

    /** 市民就餐实扣食物点入账（经济 tick 抵扣用）。 */
    public void addFedPoints(double points) {
        fedPointsCarry += Math.max(0, points);
    }
}
