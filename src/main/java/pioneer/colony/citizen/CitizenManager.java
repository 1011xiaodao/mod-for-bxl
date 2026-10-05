package pioneer.colony.citizen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import pioneer.colony.PioneerColony;
import pioneer.colony.colony.BuildingDefinition;
import pioneer.colony.colony.BuildingDefinitions;
import pioneer.colony.colony.BuildingInstance;
import pioneer.colony.colony.Colony;
import pioneer.colony.colony.ColonySavedData;
import pioneer.colony.config.Config;
import pioneer.colony.economy.FoodValues;
import pioneer.colony.entity.CitizenEntity;
import pioneer.colony.raid.RaidManager;
import pioneer.colony.registry.ModRegistry;

/**
 * 市民日程模拟（M6.5，06 §8.1）：常驻市民实体的注册表与日程引擎。
 * <p>
 * 单一事实源纪律：人口数字在 Colony（SavedData）里，市民实体是表现层——
 * 实体随所在区块由原版机制持久化（与原版村民同机制），本管理器只做
 * 「数据 ↔ 实体」对账：人口 +1 → 住宅区块加载时补生成；人口 -1 → 回收多余实体。
 * 实体不另存名册（原版区块存档即名册），重启后由 join 事件自动重建注册表。
 * <p>
 * 日程（按 Minecraft 昼夜驱动，时刻全部在配置）：白天在岗工坊、正午/傍晚赴食堂就餐
 * （从殖民地缓冲实扣食物点，经 fedPointsCarry 在经济 tick 中抵扣防双重记账）、夜间归宅；
 * 袭击期间全员向总部集结参战（RALLY，战斗由 AI 自动接敌）。
 * <p>
 * 寻路纪律（06 §9）：按需一次寻路（目标变化才 moveTo），每秒至多补生成 1 个实体摊平尖峰；
 * 卡住由 stuckTeleportSeconds 保险丝传送兜底。单殖民地模拟规模受 maxSimulatedCitizens 保险丝约束。
 */
public final class CitizenManager {
    public static final String TAG_RESIDENT = "pioneer_colony_resident";

    /** 市民随机名字池（占位，正式名单待项目主；沿用 M6.4 袭击名字池）。 */
    private static final String[] NAMES = {"张三", "李四", "王五", "赵六", "陈七", "刘八", "孙九", "周十",
            "吴一", "郑二", "王芳", "李娜", "张伟", "刘洋", "陈静", "杨帆",
            "赵磊", "黄强", "周杰", "吴迪", "徐婷", "孙丽", "马超", "朱霞"};

    /** 常驻市民注册表：colonyUuid → 已加载实体（内存态，join/leave 事件维护）。 */
    private static final Map<UUID, Set<CitizenEntity>> byColony = new HashMap<>();

    // —— 性能计量（32+ 市民寻路帧耗报告数据源） ——
    private static double lastTickMs = 0.0;
    private static double maxTickMs = 0.0;
    private static int lastPathCount = 0;
    private static int maxPathCount = 0;
    private static int lastCitizenCount = 0;
    private static int lastColonyCount = 0;

    private CitizenManager() {
    }

    // —— 注册表（GameEvents 接线） ——

    public static void onEntityJoin(Entity entity) {
        if (entity instanceof CitizenEntity citizen
                && citizen.isPermanent() && citizen.colonyUuid() != null && citizen.isAlive()) {
            byColony.computeIfAbsent(citizen.colonyUuid(), k -> java.util.Collections.newSetFromMap(new java.util.HashMap<>()))
                    .add(citizen);
        }
    }

    public static void onEntityLeave(Entity entity) {
        if (entity instanceof CitizenEntity citizen && citizen.colonyUuid() != null) {
            Set<CitizenEntity> set = byColony.get(citizen.colonyUuid());
            if (set != null) {
                set.remove(citizen);
            }
        }
    }

    public static void onServerStopped() {
        byColony.clear();
    }

    public static int liveCount(UUID colonyUuid) {
        Set<CitizenEntity> set = byColony.get(colonyUuid);
        return set == null ? 0 : set.size();
    }

    public static List<CitizenEntity> liveOf(Colony colony) {
        Set<CitizenEntity> set = byColony.get(colony.uuid());
        if (set == null || set.isEmpty()) {
            return List.of();
        }
        List<CitizenEntity> out = new ArrayList<>();
        for (CitizenEntity c : set) {
            if (c.isAlive()) {
                out.add(c);
            }
        }
        return out;
    }

    // —— 每秒 tick（ColonyManager %20 调度） ——

    public static void tickServer(MinecraftServer server) {
        ColonySavedData data = ColonySavedData.get(server);
        if (data.all().isEmpty()) {
            return;
        }
        long startNanos = System.nanoTime();
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) {
            return;
        }
        int paths = 0;
        int citizens = 0;
        int colonies = 0;
        for (Colony colony : data.all()) {
            colonies++;
            syncPopulation(level, colony);
            List<CitizenEntity> citizensList = liveOf(colony);
            citizens += citizensList.size();
            paths += schedulePass(level, colony, citizensList);
        }
        lastTickMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        maxTickMs = Math.max(maxTickMs, lastTickMs);
        lastPathCount = paths;
        maxPathCount = Math.max(maxPathCount, paths);
        lastCitizenCount = citizens;
        lastColonyCount = colonies;
    }

    /** 数据 ↔ 实体对账：目标数 = min(人口, 保险丝)；少则补（每秒 1 个摊平尖峰），多则回收。 */
    private static void syncPopulation(ServerLevel level, Colony colony) {
        List<CitizenEntity> live = liveOf(colony);
        int fuse = Config.MAX_SIMULATED_CITIZENS.get();
        int target = fuse < 0 ? colony.getPopulation() : Math.min(colony.getPopulation(), fuse);
        if (live.size() < target) {
            trySpawnOne(level, colony, live, target);
        } else if (live.size() > target) {
            // 回收优先非守卫（守卫最后撤）；数据层减员（断粮等）已有人口日志，实体静默移除
            CitizenEntity victim = live.stream()
                    .filter(c -> !c.isGuard())
                    .findFirst()
                    .orElse(live.get(0));
            victim.discard();
            Set<CitizenEntity> set = byColony.get(colony.uuid());
            if (set != null) {
                set.remove(victim);
            }
        }
    }

    private static void trySpawnOne(ServerLevel level, Colony colony, List<CitizenEntity> live, int target) {
        List<Assign> assigns = computeAssignments(colony, target);
        int idx = live.size();
        if (idx >= assigns.size()) {
            return;
        }
        Assign assign = assigns.get(idx);
        if (!assign.homeValid()) {
            return;
        }
        BlockPos pos = assign.homePos();
        if (!level.isLoaded(pos)) {
            return; // 住宅区块未加载：等加载再补生成（原版冻结纪律）
        }
        CitizenEntity citizen = ModRegistry.CITIZEN_ENTITY.get().create(level);
        if (citizen == null) {
            return;
        }
        citizen.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        citizen.setupPermanent(colony.uuid(), NAMES[idx % NAMES.length]);
        citizen.setHome(assign.homeOrigin(), assign.homeValid());
        citizen.setWork(assign.workOrigin(), assign.workValid(), assign.workDefId());
        citizen.applyRole("guard_post".equals(assign.workDefId()), roleDamage(colony, assign.workDefId()));
        level.addFreshEntity(citizen);
        if (Config.LOG_CITIZEN_EVENTS.get()) {
            citizenLog(colony, String.format("市民[%s] 出生（实体 %d/%d，人口 %d）",
                    NAMES[idx % NAMES.length], live.size() + 1, target, colony.getPopulation()));
        }
    }

    // —— 日程引擎 ——

    /** 返回本轮发出的寻路次数（性能计量）。 */
    private static int schedulePass(ServerLevel level, Colony colony, List<CitizenEntity> citizens) {
        if (citizens.isEmpty()) {
            return 0;
        }
        int paths = 0;
        long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        int day = (int) Math.floorDiv(level.getDayTime(), 24000L);
        boolean lunch = dayTime >= Config.LUNCH_START_TICK.get() && dayTime < Config.LUNCH_END_TICK.get();
        boolean dinner = dayTime >= Config.DINNER_START_TICK.get() && dayTime < Config.DINNER_END_TICK.get();
        boolean mealWindow = lunch || dinner;
        boolean workTime = dayTime >= Config.WORK_START_TICK.get() && dayTime < Config.DINNER_START_TICK.get()
                && !mealWindow;
        boolean raidActive = RaidManager.hasActiveRaid(colony.uuid());
        boolean stopped = colony.getHqState() == Colony.HqState.STOPPED;
        BlockPos canteen = canteenFront(colony);
        int mealKey = day * 10 + (lunch ? 1 : 2);
        int stuckLimit = Config.STUCK_TELEPORT_SECONDS.get();

        // 岗位对账（顺序与经济 tick 填充率分配一致：建筑列表序）
        applyAssignments(colony, citizens);

        for (CitizenEntity citizen : citizens) {
            // 卡住检测：寻路中每秒漂移 <0.3 视为卡住，超保险丝传送到目标点
            if (citizen.getNavigation().isInProgress()) {
                if (citizen.anchorDrift() < 0.3) {
                    if (stuckLimit > 0 && citizen.bumpStuck() >= stuckLimit && citizen.hasGoalTarget()) {
                        BlockPos t = citizen.goalTargetPos();
                        citizen.teleportTo(t.getX() + 0.5, t.getY(), t.getZ() + 0.5);
                        citizen.resetStuck();
                        if (Config.LOG_CITIZEN_EVENTS.get()) {
                            citizenLog(colony, "市民[" + citizenName(citizen) + "] 通勤受阻，已传送兜底");
                        }
                    }
                } else {
                    citizen.resetStuck();
                    citizen.recordAnchor();
                }
            } else {
                citizen.resetStuck();
            }

            CitizenEntity.ScheduleGoal desired;
            if (raidActive) {
                desired = CitizenEntity.ScheduleGoal.RALLY;
            } else if (stopped) {
                desired = citizen.hasHome() ? CitizenEntity.ScheduleGoal.HOME : CitizenEntity.ScheduleGoal.IDLE;
            } else if (mealWindow && canteen != null) {
                desired = CitizenEntity.ScheduleGoal.CANTEEN;
            } else if (workTime && citizen.hasWork()) {
                desired = CitizenEntity.ScheduleGoal.WORK;
            } else if (citizen.hasHome()) {
                desired = CitizenEntity.ScheduleGoal.HOME;
            } else {
                desired = CitizenEntity.ScheduleGoal.IDLE;
            }

            if (desired != citizen.goal()) {
                citizen.setGoal(desired);
                citizen.stopMoving();
                // 无目标即进入下方抖动-导航流程（含指派变更/初次指派的自愈路径）
            }
            if (!citizen.hasGoalTarget() && desired != CitizenEntity.ScheduleGoal.IDLE) {
                if (citizen.navDelay() < 0) {
                    // 全员同拍换目标（上下班/开饭）→ 随机抖动把寻路摊到数秒，压平帧尖峰
                    citizen.setNavDelay(java.util.concurrent.ThreadLocalRandom.current().nextInt(6));
                } else if (citizen.navDelay() > 0) {
                    citizen.setNavDelay(citizen.navDelay() - 1);
                } else {
                    citizen.setNavDelay(-1);
                    BlockPos target = targetFor(colony, citizen, desired);
                    if (target != null) {
                        citizen.navigateTo(target);
                        paths++;
                    }
                }
            } else if (desired == CitizenEntity.ScheduleGoal.CANTEEN
                    && citizen.atGoalTarget() && citizen.lastMealKey() != mealKey) {
                eatMeal(level, colony, citizen, mealKey);
            }
        }
        return paths;
    }

    /** 就餐：从缓冲实扣一餐食物点（先吃食物点低的），入 fedPointsCarry 待经济 tick 抵扣。 */
    private static void eatMeal(ServerLevel level, Colony colony, CitizenEntity citizen, int mealKey) {
        citizen.setLastMealKey(mealKey);
        double mealPoints = Config.FOOD_POINTS_PER_CITIZEN_PER_DAY.get()
                / Math.max(1, Config.MEALS_PER_DAY.get());
        if (mealPoints <= 0) {
            return;
        }
        // 先吃食物点低的（与经济 tick 同策略）
        List<Map.Entry<String, Long>> foods = new ArrayList<>();
        for (Map.Entry<String, Long> e : colony.bufferSnapshot().entrySet()) {
            if (FoodValues.foodValue(e.getKey()) > 0 && e.getValue() > 0) {
                foods.add(Map.entry(e.getKey(), e.getValue()));
            }
        }
        foods.sort(Comparator.comparingDouble(e -> FoodValues.foodValue(e.getKey())));
        double remaining = mealPoints;
        StringBuilder ate = new StringBuilder();
        double obtained = 0;
        for (Map.Entry<String, Long> food : foods) {
            if (remaining <= 1e-9) {
                break;
            }
            double value = FoodValues.foodValue(food.getKey());
            long stock = colony.countOf(food.getKey());
            long take = Math.min(stock, (long) Math.ceil(remaining / value));
            if (take <= 0) {
                continue;
            }
            take = colony.takeItems(food.getKey(), take);
            if (take > 0) {
                double got = take * value;
                remaining -= got;
                obtained += got;
                if (ate.length() > 0) {
                    ate.append("、");
                }
                ate.append(food.getKey().replace("minecraft:", "")).append("×").append(take);
            }
        }
        if (obtained <= 0) {
            return; // 缓冲无粮：饿一顿（数据层断粮公式兜底人口后果）
        }
        colony.addFedPoints(obtained);
        ColonySavedData.get(level.getServer()).setDirty();
        level.playSound(null, citizen.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.5F, 1.0F);
        if (Config.LOG_CITIZEN_EVENTS.get()) {
            citizenLog(colony, String.format("市民[%s] 就餐：%s（%.1f 食物点）",
                    citizenName(citizen), ate, obtained));
        }
    }

    // —— 岗位/住宅指派（与经济 tick 填充分配同序：建筑列表序填充） ——

    private record Assign(BlockPos homePos, boolean homeValid, long homeOrigin,
                          BlockPos workPos, boolean workValid, long workOrigin, String workDefId) {
    }

    private static List<Assign> computeAssignments(Colony colony, int count) {
        List<Assign> out = new ArrayList<>(count);
        List<BuildingInstance> homes = new ArrayList<>();
        List<Integer> homeSlots = new ArrayList<>();
        BuildingInstance hq = null;
        List<BuildingInstance> works = new ArrayList<>();
        for (BuildingInstance b : colony.getBuildings()) {
            if (b.status() != BuildingInstance.Status.ACTIVE) {
                continue;
            }
            BuildingDefinition def = BuildingDefinitions.get(b.definitionId()).orElse(null);
            if (def == null) {
                continue;
            }
            int residents = def.tierResidentSlots(b.tier());
            int workers = def.tierWorkerSlots(b.tier());
            if ("hq".equals(b.definitionId())) {
                hq = b;
            }
            if (residents > 0) {
                homes.add(b);
                homeSlots.add(residents);
            }
            if (workers > 0) {
                works.add(b);
            }
        }
        int homeIdx = 0, homeUsed = 0;
        int workIdx = 0, workUsed = 0;
        for (int i = 0; i < count; i++) {
            // 住宅：住宅类按床位数填充；溢出走总部床位；无任何住房 = 无家
            BlockPos homePos = null;
            boolean homeValid = false;
            long homeOrigin = 0;
            while (homeIdx < homes.size() && homeUsed >= homeSlots.get(homeIdx)) {
                homeIdx++;
                homeUsed = 0;
            }
            BuildingInstance home = homeIdx < homes.size() ? homes.get(homeIdx) : null;
            if (home != null) {
                homeUsed++;
                homePos = doorFront(home);
                homeValid = true;
                homeOrigin = home.origin();
            }
            // 岗位：按 worker_slots 顺序填充，满则无岗
            BlockPos workPos = null;
            boolean workValid = false;
            long workOrigin = 0;
            String workDefId = "";
            while (workIdx < works.size()
                    && workUsed >= workerSlots(works.get(workIdx))) {
                workIdx++;
                workUsed = 0;
            }
            BuildingInstance work = workIdx < works.size() ? works.get(workIdx) : null;
            if (work != null) {
                workUsed++;
                workPos = doorFront(work);
                workValid = true;
                workOrigin = work.origin();
                workDefId = work.definitionId();
            }
            // 完全无住房时：总部门面作为默认起居点
            if (!homeValid && hq != null) {
                homePos = doorFront(hq);
                homeValid = true;
                homeOrigin = hq.origin();
            }
            out.add(new Assign(homePos, homeValid, homeOrigin, workPos, workValid, workOrigin, workDefId));
        }
        return out;
    }

    private static int workerSlots(BuildingInstance b) {
        return BuildingDefinitions.get(b.definitionId()).map(d -> d.tierWorkerSlots(b.tier())).orElse(0);
    }

    private static void applyAssignments(Colony colony, List<CitizenEntity> citizens) {
        List<CitizenEntity> sorted = new ArrayList<>(citizens);
        sorted.sort(Comparator.comparing(c -> c.getUUID().toString()));
        List<Assign> assigns = computeAssignments(colony, sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            CitizenEntity citizen = sorted.get(i);
            Assign a = assigns.get(i);
            if (citizen.hasHome() != a.homeValid()
                    || (a.homeValid && citizen.homeOrigin() != a.homeOrigin)) {
                citizen.setHome(a.homeOrigin(), a.homeValid());
                if (citizen.goal() == CitizenEntity.ScheduleGoal.HOME) {
                    citizen.stopMoving(); // 目标点变了，下轮日程评估重新导航
                }
            }
            boolean workChanged = citizen.hasWork() != a.workValid()
                    || (a.workValid && citizen.workOrigin() != a.workOrigin);
            if (workChanged) {
                citizen.setWork(a.workOrigin(), a.workValid(), a.workDefId());
                if (citizen.goal() == CitizenEntity.ScheduleGoal.WORK) {
                    citizen.stopMoving();
                }
            }
            // 战力随岗位与研究加成每秒校准（自愈：覆盖研究完成/建筑变动）
            citizen.applyRole("guard_post".equals(citizen.workDefId()),
                    roleDamage(colony, citizen.workDefId()));
        }
    }

    /** 守卫=守卫哨在岗（100%），其余 25%，受研究 __guard_power 加成（与 M6.4 袭击公式一致）。 */
    private static double roleDamage(Colony colony, String workDefId) {
        double base = Config.CITIZEN_BASE_DAMAGE.get();
        if ("guard_post".equals(workDefId)) {
            return base * Config.CITIZEN_GUARD_MULTIPLIER.get()
                    * (1.0 + colony.productionBonusFor(List.of("__guard_power")));
        }
        return base * Config.CITIZEN_WORKER_MULTIPLIER.get();
    }

    // —— 目标点 ——

    /** 建筑南门 1 格外（蓝图南向门洞；建筑间距≥2 保证门外必为可站立空气）。 */
    private static BlockPos doorFront(BuildingInstance b) {
        return BlockPos.of(b.origin()).offset(b.footprintW() / 2, 1, b.footprintD());
    }

    private static BlockPos canteenFront(Colony colony) {
        return colony.getBuildings().stream()
                .filter(b -> "canteen".equals(b.definitionId()) && b.status() == BuildingInstance.Status.ACTIVE)
                .findFirst().map(CitizenManager::doorFront).orElse(null);
    }

    private static BlockPos targetFor(Colony colony, CitizenEntity citizen, CitizenEntity.ScheduleGoal goal) {
        return switch (goal) {
            case HOME -> citizen.hasHome() ? doorFrontOf(citizen.homeOrigin(), colony) : null;
            case WORK -> citizen.hasWork() ? doorFrontOf(citizen.workOrigin(), colony) : null;
            case CANTEEN -> spread(canteenFront(colony), citizen);
            case RALLY -> spread(rallyPoint(colony), citizen);
            case IDLE -> null;
        };
    }

    /** 集合点个体化散布（±2 格）：33 人同挤一个门点位会被挤出到达圈。 */
    private static BlockPos spread(BlockPos base, CitizenEntity citizen) {
        if (base == null) {
            return null;
        }
        int h = citizen.getUUID().hashCode();
        return base.offset(h % 5 - 2, 0, (h >> 4) % 5 - 2);
    }

    /** origin 指定的建筑可能已被拆除（指派滞后一拍），退化为总部集结点。 */
    private static BlockPos doorFrontOf(long origin, Colony colony) {
        BuildingInstance b = colony.buildingAtOrigin(origin);
        return b != null ? doorFront(b) : rallyPoint(colony);
    }

    private static BlockPos rallyPoint(Colony colony) {
        return colony.getBuildings().stream()
                .filter(b -> "hq".equals(b.definitionId()) && b.status() == BuildingInstance.Status.ACTIVE)
                .findFirst().map(CitizenManager::doorFront).orElse(null);
    }

    // —— 袭击动员 / 死亡写回 ——

    /** 袭击开始：返回已加载常驻市民名单（战斗由其 AI 自动接敌；日程引擎检 RALLY 向总部集结）。 */
    public static List<UUID> mobilizeForRaid(Colony colony) {
        List<UUID> out = new ArrayList<>();
        for (CitizenEntity c : liveOf(colony)) {
            out.add(c.getUUID());
        }
        return out;
    }

    /** 常驻市民阵亡：人口 -1（真实战斗结果写回），岗位自动释放（指派随下轮对账收缩）。 */
    public static void onCitizenDeath(CitizenEntity citizen, net.minecraft.world.damagesource.DamageSource source) {
        if (citizen.colonyUuid() == null || !(citizen.level() instanceof ServerLevel level)) {
            return;
        }
        Colony colony = ColonySavedData.get(level.getServer()).colonyByUuid(citizen.colonyUuid());
        if (colony == null) {
            return;
        }
        colony.setPopulation(Math.max(0, colony.getPopulation() - 1));
        ColonySavedData.get(level.getServer()).setDirty();
        String cause = source.getLocalizedDeathMessage(citizen).getString();
        notifyOwner(level.getServer(), colony, String.format("☠ 市民[%s] 身亡（%s），人口 %d。",
                citizenName(citizen), cause, colony.getPopulation()));
    }

    // —— 查询 / 调试 ——

    public static String infoLine(Colony colony) {
        List<CitizenEntity> live = liveOf(colony);
        Map<String, Integer> goals = new HashMap<>();
        for (CitizenEntity c : live) {
            goals.merge(c.goal().name(), 1, Integer::sum);
        }
        int fuse = Config.MAX_SIMULATED_CITIZENS.get();
        return String.format("人口 %d，实体 %d（保险丝 %s），日程分布 %s",
                colony.getPopulation(), live.size(), fuse < 0 ? "不限" : fuse, goals);
    }

    public static String perfReport() {
        return String.format(
                "市民模拟性能：殖民地 %d 个，实体 %d 个；管理器每秒耗时 %.3f ms（会话峰值 %.3f ms）；"
                        + "本秒日程寻路 %d 次（会话峰值 %d 次）。寻路为按需一次制，实体 AI/寻路本体开销随模拟距离由原版机制裁剪。",
                lastColonyCount, lastCitizenCount, lastTickMs, maxTickMs, lastPathCount, maxPathCount);
    }

    public static void resetPerf() {
        maxTickMs = 0;
        maxPathCount = 0;
    }

    private static String citizenName(CitizenEntity citizen) {
        return citizen.getCustomName() != null ? citizen.getCustomName().getString() : "无名";
    }

    private static void citizenLog(Colony colony, String message) {
        PioneerColony.LOGGER.info("[殖民地经营][市民][{}] {}", colony.getName(), message);
    }

    private static void notifyOwner(MinecraftServer server, Colony colony, String message) {
        ServerPlayer owner = server.getPlayerList().getPlayer(colony.owner());
        if (owner != null) {
            owner.sendSystemMessage(Component.literal("【殖民地经营】" + message));
        } else if (colony.owner().equals(pioneer.colony.colony.ColonyManager.DEBUG_OWNER)) {
            citizenLog(colony, message);
        }
    }
}
