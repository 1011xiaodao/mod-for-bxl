package pioneer.colony.research;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.saveddata.SavedData;
import pioneer.colony.PioneerColony;
import pioneer.colony.colony.BuildingDefinitions;
import pioneer.colony.colony.Colony;
import pioneer.colony.colony.ColonySavedData;
import pioneer.colony.colony.ItemCost;
import pioneer.colony.config.Config;

/**
 * 【开发联调桩·研究系统】—— [研究台]（pioneer_research）缺席且 research.stubEnabled=true 时启用，
 * 提供与 [研究台] 文档等效的最小研究系统，用于 M6.3 验收与联调预演：
 * <ul>
 *   <li>研究树 datapack：data/&lt;ns&gt;/pioneer_research/research/*.json——与 [研究台] 格式完全一致，
 *       正式联调时同一批 JSON 可原样迁移；</li>
 *   <li>按玩家记账（完成集合 + 进行中条目），离线/降频推进与经济 tick 同频（60s）；</li>
 *   <li>colony_only 仅对拥有殖民地的玩家可见可研；colony 通道注册后提供并行/加速/效果回调；</li>
 *   <li>发起契约：startViaColony 不扣费（费用由调用方先行扣除——仓库扣料+组织费在市政厅侧）。</li>
 * </ul>
 * [研究台] 在位时本类整体失效（ResearchIntegration 裁决）；红线：正式整合包的研究逻辑归 [研究台]。
 */
public final class StubResearchSystem {
    private static volatile Map<String, StubDef> defs = Map.of();
    private static ColonyResearchChannel colonyChannel;

    private StubResearchSystem() {
    }

    // —— 研究树 datapack（与 [研究台] §3 格式一致） ——

    public static final class StubDef {
        public final String id;
        public final String name;
        public final String parent;
        public final String channel;
        public final int timeMinutes;
        public final List<ItemCost> cost;
        public final Map<String, Object> effects;

        StubDef(String id, String name, String parent, String channel, int timeMinutes,
                List<ItemCost> cost, Map<String, Object> effects) {
            this.id = id;
            this.name = name;
            this.parent = parent;
            this.channel = channel;
            this.timeMinutes = timeMinutes;
            this.cost = cost;
            this.effects = effects;
        }
    }

    /** 目录与 [研究台] 相同：data/&lt;ns&gt;/pioneer_research/research/*.json。 */
    public static final class DefLoader extends SimpleJsonResourceReloadListener {
        public DefLoader() {
            super(new com.google.gson.Gson(), "pioneer_research/research");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
            Map<String, StubDef> loaded = new LinkedHashMap<>();
            files.forEach((key, element) -> {
                if (!element.isJsonObject()) {
                    return;
                }
                try {
                    loaded.putAll(parse(element.getAsJsonObject()));
                } catch (Exception e) {
                    PioneerColony.LOGGER.error("[研究桩] 研究定义加载失败（已跳过）：{} —— {}", key, e.getMessage());
                }
            });
            defs = Map.copyOf(loaded);
            PioneerColony.LOGGER.info("[研究桩] 研究树加载完成：{} 项", defs.size());
        }

        private static Map<String, StubDef> parse(JsonObject json) {
            String id = json.get("id").getAsString();
            String name = json.has("name") ? json.get("name").getAsString() : id;
            String parent = json.has("parent") && !json.get("parent").isJsonNull() ? json.get("parent").getAsString() : null;
            String channel = json.has("channel") ? json.get("channel").getAsString() : "common";
            int timeMinutes = json.has("time_minutes") ? json.get("time_minutes").getAsInt() : 30;
            List<ItemCost> cost = new ArrayList<>();
            if (json.has("cost") && json.get("cost").isJsonArray()) {
                for (JsonElement e : json.getAsJsonArray("cost")) {
                    JsonObject o = e.getAsJsonObject();
                    cost.add(new ItemCost(o.get("item").getAsString(), o.get("count").getAsLong()));
                }
            }
            Map<String, Object> effects = new LinkedHashMap<>();
            if (json.has("effects") && json.get("effects").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("effects").entrySet()) {
                    JsonElement v = e.getValue();
                    Object value;
                    if (v.isJsonObject()) {
                        Map<String, Object> nested = new LinkedHashMap<>();
                        for (Map.Entry<String, JsonElement> ne : v.getAsJsonObject().entrySet()) {
                            nested.put(ne.getKey(), ne.getValue().isJsonPrimitive() ? primitive(ne.getValue()) : ne.getValue().toString());
                        }
                        value = nested;
                    } else {
                        value = v.isJsonPrimitive() ? primitive(v) : v.toString();
                    }
                    effects.put(e.getKey(), value);
                }
            }
            return Map.of(id, new StubDef(id, name, parent, channel, timeMinutes, List.copyOf(cost), effects));
        }

        private static Object primitive(JsonElement v) {
            return v.getAsJsonPrimitive().isNumber() ? v.getAsNumber() : v.getAsString();
        }
    }

    // —— 按玩家记账（SavedData） ——

    public static final class StubSavedData extends SavedData {
        private final Map<UUID, PlayerData> players = new LinkedHashMap<>();

        public static StubSavedData get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(
                    new SavedData.Factory<>(StubSavedData::new, StubSavedData::load, null), "pioneer_colony_research_stub");
        }

        public PlayerData player(UUID uuid) {
            return players.computeIfAbsent(uuid, k -> new PlayerData());
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag list = new ListTag();
            for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
                CompoundTag t = new CompoundTag();
                t.putUUID("player", e.getKey());
                PlayerData pd = e.getValue();
                ListTag doneList = new ListTag();
                for (String id : pd.done) {
                    doneList.add(net.minecraft.nbt.StringTag.valueOf(id));
                }
                t.put("done", doneList);
                ListTag activeList = new ListTag();
                for (Map.Entry<String, Active> a : pd.active.entrySet()) {
                    CompoundTag at = new CompoundTag();
                    at.putString("id", a.getKey());
                    at.putDouble("remainingMinutes", a.getValue().remainingMinutes);
                    at.putLong("lastWall", a.getValue().lastWall);
                    at.putString("channelOverride", a.getValue().channelOverride);
                    activeList.add(at);
                }
                t.put("active", activeList);
                list.add(t);
            }
            tag.put("players", list);
            return tag;
        }

        public static StubSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
            StubSavedData data = new StubSavedData();
            ListTag list = tag.getList("players", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag t = list.getCompound(i);
                PlayerData pd = data.player(t.getUUID("player"));
                ListTag doneList = t.getList("done", Tag.TAG_STRING);
                for (int j = 0; j < doneList.size(); j++) {
                    pd.done.add(doneList.getString(j));
                }
                ListTag activeList = t.getList("active", Tag.TAG_COMPOUND);
                for (int j = 0; j < activeList.size(); j++) {
                    CompoundTag at = activeList.getCompound(j);
                    Active a = new Active();
                    a.remainingMinutes = at.getDouble("remainingMinutes");
                    a.lastWall = at.getLong("lastWall");
                    a.channelOverride = at.getString("channelOverride");
                    pd.active.put(at.getString("id"), a);
                }
            }
            return data;
        }
    }

    public static final class PlayerData {
        public final Set<String> done = Collections.newSetFromMap(new LinkedHashMap<>());
        public final Map<String, Active> active = new LinkedHashMap<>();
    }

    public static final class Active {
        public double remainingMinutes;
        public long lastWall;
        public String channelOverride = "colony";
    }

    // —— 推进（60s 与经济 tick 同频） ——

    public static void tickServer(MinecraftServer server) {
        if (colonyChannel == null || defs.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        StubSavedData data = StubSavedData.get(server);
        boolean dirty = false;
        for (Map.Entry<UUID, PlayerData> e : data.players.entrySet()) {
            UUID player = e.getKey();
            PlayerData pd = e.getValue();
            for (Map.Entry<String, Active> a : List.copyOf(pd.active.entrySet())) {
                Active active = a.getValue();
                double elapsedMinutes = (now - active.lastWall) / 60000.0;
                active.lastWall = now;
                double multiplier = "colony".equals(active.channelOverride)
                        ? ColonyResearchChannel.INSTANCE.speedMultiplier(server, player) : 1.0;
                active.remainingMinutes -= elapsedMinutes * multiplier;
                if (active.remainingMinutes <= 0) {
                    pd.active.remove(a.getKey());
                    pd.done.add(a.getKey());
                    StubDef def = defs.get(a.getKey());
                    if (def != null) {
                        colonyChannel.onCompleted(server, player, def.id, def.effects);
                    }
                    dirty = true;
                }
            }
        }
        if (dirty) {
            data.setDirty();
        }
    }

    /** OP 调试：立即完成指定玩家全部进行中研究（冒烟用）。 */
    public static int debugFinish(MinecraftServer server, UUID player) {
        StubSavedData data = StubSavedData.get(server);
        PlayerData pd = data.player(player);
        int n = 0;
        for (Map.Entry<String, Active> a : List.copyOf(pd.active.entrySet())) {
            pd.done.add(a.getKey());
            pd.active.remove(a.getKey());
            StubDef def = defs.get(a.getKey());
            if (def != null && colonyChannel != null) {
                colonyChannel.onCompleted(server, player, def.id, def.effects);
            }
            n++;
        }
        if (n > 0) {
            data.setDirty();
        }
        return n;
    }

    // —— Bridge 实现 ——

    public static final class Bridge implements ResearchBridge {
        @Override
        public boolean isDone(MinecraftServer server, UUID player, String researchId) {
            return StubSavedData.get(server).player(player).done.contains(researchId);
        }

        @Override
        public List<ResearchInfo> visibleResearches(MinecraftServer server, UUID player) {
            StubSavedData data = StubSavedData.get(server);
            PlayerData pd = data.player(player);
            boolean hasColony = ColonySavedData.get(server).colonyOf(player) != null;
            List<ResearchInfo> out = new ArrayList<>();
            for (StubDef def : defs.values()) {
                if ("colony_only".equals(def.channel) && !hasColony) {
                    continue;
                }
                ResearchInfo.State state;
                int remaining = 0;
                if (pd.done.contains(def.id)) {
                    state = ResearchInfo.State.DONE;
                } else if (pd.active.containsKey(def.id)) {
                    state = ResearchInfo.State.ACTIVE;
                    Active active = pd.active.get(def.id);
                    remaining = (int) Math.max(0, Math.round(active.remainingMinutes
                            / Math.max(0.01, effectiveMultiplier(server, player, active)) * 60000.0 / 1000.0));
                } else if (def.parent != null && !pd.done.contains(def.parent)) {
                    state = ResearchInfo.State.LOCKED_PARENT;
                } else {
                    state = ResearchInfo.State.AVAILABLE;
                }
                out.add(new ResearchInfo(def.id, def.name, def.channel, def.timeMinutes, def.cost, def.parent,
                        def.effects, state, remaining));
            }
            return out;
        }

        private double effectiveMultiplier(MinecraftServer server, UUID player, Active active) {
            return "colony".equals(active.channelOverride)
                    ? ColonyResearchChannel.INSTANCE.speedMultiplier(server, player) : 1.0;
        }

        @Override
        public String startViaColony(MinecraftServer server, UUID player, String researchId) {
            StubDef def = defs.get(researchId);
            if (def == null) {
                return "未知研究：" + researchId;
            }
            StubSavedData data = StubSavedData.get(server);
            PlayerData pd = data.player(player);
            if (pd.done.contains(researchId)) {
                return "该研究已完成。";
            }
            if (pd.active.containsKey(researchId)) {
                return "该研究已在进行中。";
            }
            if ("colony_only".equals(def.channel)
                    && ColonySavedData.get(server).colonyOf(player) == null) {
                return "colony_only 研究仅限殖民地总督发起。";
            }
            if (def.parent != null && !pd.done.contains(def.parent)) {
                return "前置研究未完成：" + def.parent;
            }
            int used = (int) pd.active.values().stream()
                    .filter(a -> "colony".equals(a.channelOverride)).count();
            int max = ColonyResearchChannel.INSTANCE.maxParallel(server, player);
            if (used >= max) {
                return "并行槽位已满（" + used + "/" + max + "，随市政厅等级提升）。";
            }
            Active active = new Active();
            active.remainingMinutes = def.timeMinutes;
            active.lastWall = System.currentTimeMillis();
            active.channelOverride = "colony";
            pd.active.put(researchId, active);
            data.setDirty();
            return null;
        }

        @Override
        public void registerColonyChannel(ColonyResearchChannel channel) {
            colonyChannel = channel;
        }
    }

    public static Map<String, StubDef> defs() {
        return defs;
    }
}
