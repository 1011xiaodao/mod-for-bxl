package pioneer.colony.raid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import pioneer.colony.colony.BuildingInstance;
import pioneer.colony.colony.Colony;
import pioneer.colony.colony.ColonySavedData;
import pioneer.colony.config.Config;
import pioneer.colony.entity.CitizenEntity;
import pioneer.colony.market.MarketPrices;
import pioneer.colony.registry.ModRegistry;
import pioneer.colony.raid.RaidConfigs.RaidDef;
import pioneer.colony.raid.RaidConfigs.Wave;

/**
 * 袭击系统（06 §6，事件驱动，全模组唯一真实体战斗）：
 * 威胁值收敛 → 档位触发倒计时+方向预警 → 归零时总部 64 格内有人开打（否则推迟零成本）→
 * 波次按档位 datapack 生成（实体标记独立队伍）→ 总部耐久/建筑受损/市民参战伤亡写回 →
 * 全歼或超时撤退=防守成功（威胁 -30%、战利品入仓库）。
 * 袭击态为内存态（重启后袭击怪由 join-event 自清，威胁重新收敛）。
 */
public final class RaidManager {
    public static final String TAG_RAID_MOB = "pioneer_colony_raid_mob";
    public static final String TAG_CITIZEN = "pioneer_colony_citizen";

    public enum Phase {
        COUNTDOWN, ACTIVE
    }

    private static final class RaidState {
        final UUID colonyUuid;
        Phase phase;
        int tier;
        long phaseEndWall;
        int sector;
        int waveIndex;
        long nextWaveWall;
        long raidStartWall;
        long lastBuildingDamageWall;
        final List<UUID> raiderIds = new ArrayList<>();
        final List<UUID> citizenIds = new ArrayList<>();

        RaidState(UUID colonyUuid) {
            this.colonyUuid = colonyUuid;
        }
    }

    private static final Map<UUID, RaidState> states = new HashMap<>();

    /** 市民随机名字池（占位，正式名单待项目主）。 */
    private static final String[] NAMES = {"张三", "李四", "王五", "赵六", "陈七", "刘八", "孙九", "周十",
            "吴一", "郑二", "王芳", "李娜", "张伟", "刘洋", "陈静", "杨帆",
            "赵磊", "黄强", "周杰", "吴迪", "徐婷", "孙丽", "马超", "朱霞"};

    private RaidManager() {
    }

    // —— 威胁值（每经济 tick） ——

    /** 威胁值 = 建筑总等级×系数×权重 + 缓冲物资价值×系数 + 人口×系数 + 金库×系数；向推导值收敛。 */
    public static void computeThreat(Colony colony) {
        int tierSum = 0;
        for (BuildingInstance b : colony.getBuildings()) {
            tierSum += b.tier();
        }
        double bufferValue = 0;
        for (Map.Entry<String, Long> e : colony.bufferSnapshot().entrySet()) {
            bufferValue += MarketPrices.basePrice(e.getKey()) * e.getValue();
        }
        double target = tierSum * Config.THREAT_COEF_BUILDING.get() * Config.THREAT_BUILDING_WEIGHT.get()
                + bufferValue * Config.THREAT_COEF_BUFFER_VALUE.get()
                + colony.getPopulation() * Config.THREAT_COEF_POPULATION.get()
                + colony.getCredits() * Config.THREAT_COEF_CREDITS.get();
        double stored = colony.getThreatValue();
        double conv = Config.THREAT_CONVERGENCE.get();
        colony.setThreatValue(stored <= 0 ? target : stored + (target - stored) * conv);
    }

    /** 威胁档位：0=无袭击（低于全部阈值）。 */
    public static int tierFor(double threat) {
        int tier = 0;
        for (String entry : Config.RAID_TIER_THRESHOLDS.get()) {
            String[] parts = entry.split(":");
            try {
                int t = Integer.parseInt(parts[0].trim());
                double threshold = Double.parseDouble(parts[1].trim());
                if (threat >= threshold && t > tier) {
                    tier = t;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return tier;
    }

    /** 档位触发检查（每经济 tick；已有袭击态不重复触发）。 */
    public static void checkTrigger(MinecraftServer server, Colony colony) {
        if (states.containsKey(colony.uuid())) {
            return;
        }
        int tier = tierFor(colony.getThreatValue());
        if (tier < 1 || RaidConfigs.get(tier) == null) {
            return;
        }
        RaidState state = new RaidState(colony.uuid());
        state.phase = Phase.COUNTDOWN;
        state.tier = tier;
        state.sector = java.util.concurrent.ThreadLocalRandom.current().nextInt(8);
        state.phaseEndWall = System.currentTimeMillis() + Config.RAID_COUNTDOWN_SECONDS.get() * 1000L;
        states.put(colony.uuid(), state);
        notifyOwner(server, colony, String.format("⚠ 威胁值 %.0f 达到袭击阈值！强度 %d 档，%d 秒后来袭。",
                colony.getThreatValue(), tier, Config.RAID_COUNTDOWN_SECONDS.get()));
        notifyOwner(server, colony, "⚠ " + sectorName(state.sector) + "方向侦测到袭击集群——布防有依据，刷新点可在扇区内偏移。");
    }

    // —— 每秒 tick ——

    public static void tickServer(MinecraftServer server) {
        if (states.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (RaidState state : List.copyOf(states.values())) {
            Colony colony = ColonySavedData.get(server).colonyByUuid(state.colonyUuid);
            if (colony == null) {
                removeState(server, state, null);
                continue;
            }
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            if (level == null) {
                continue;
            }
            BlockPos hq = hqPos(colony, level);
            RaidDef def = RaidConfigs.get(state.tier);
            if (def == null) {
                removeState(server, state, colony);
                continue;
            }
            if (state.phase == Phase.COUNTDOWN) {
                if (now >= state.phaseEndWall) {
                    if (hasPlayerNear(level, hq, Config.RAID_PLAYER_RANGE.get())) {
                        beginRaid(server, level, colony, state, def, hq);
                    } else {
                        state.phaseEndWall = now + Config.RAID_POSTPONE_SECONDS.get() * 1000L;
                        notifyOwner(server, colony, "总部附近无人驻守，袭击推迟（零成本）。");
                    }
                }
            } else {
                tickActive(server, level, colony, state, def, hq, now);
            }
        }
    }

    private static void tickActive(MinecraftServer server, ServerLevel level, Colony colony,
                                   RaidState state, RaidDef def, BlockPos hq, long now) {
        List<Monster> raiders = liveRaiders(level, state);
        // 袭击怪向总部推进（无近战目标时的导航提示；近战目标由原版 AI 处理）
        if (now - state.raidStartWall > 3000 && raiders.size() > 0 && now % 5 == 0) {
            for (Monster mob : raiders) {
                if (mob.getTarget() == null) {
                    mob.getNavigation().moveTo(hq.getX() + 0.5, hq.getY(), hq.getZ() + 0.5, 1.0);
                }
            }
        }
        // 总部耐久：6 格内的袭击怪每秒造成伤害
        double hqDamage = 0;
        for (Monster mob : raiders) {
            if (mob.distanceToSqr(hq.getX() + 0.5, hq.getY(), hq.getZ() + 0.5) < 36) {
                hqDamage += def.hqDamagePerHit();
            }
        }
        if (hqDamage > 0) {
            double before = colony.getHqHealth();
            double after = colony.damageHq(hqDamage);
            if (after <= 0) {
                colony.setHqState(Colony.HqState.STOPPED);
                notifyOwner(server, colony, "!!! 总部被攻破，殖民地停摆！公式暂停，支付材料原地修复（总部 GUI / /colony repair）。");
            } else if (before > after) {
                if (now % 10 == 0) {
                    notifyOwner(server, colony, String.format("总部耐久受击：%d%%（%d/%d）",
                            (int) (after / Config.HQ_MAX_HEALTH.get() * 100),
                            (int) after, (int) Config.HQ_MAX_HEALTH.get().doubleValue()));
                }
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE,
                        hq.getX() + 0.5, hq.getY() + 1, hq.getZ() + 0.5, 4, 0.3, 0.5, 0.3, 0.01);
            }
        }
        // 随机建筑受损（不逐块破坏——状态标记）
        if (now - state.lastBuildingDamageWall > 30_000) {
            state.lastBuildingDamageWall = now;
            if (Config.RAID_BUILDING_DAMAGE_CHANCE.get() > 0
                    && java.util.concurrent.ThreadLocalRandom.current().nextDouble()
                    < Config.RAID_BUILDING_DAMAGE_CHANCE.get()) {
                List<BuildingInstance> candidates = colony.getBuildings().stream()
                        .filter(b -> b.status() == pioneer.colony.colony.BuildingInstance.Status.ACTIVE
                                && !"hq".equals(b.definitionId()))
                        .toList();
                if (!candidates.isEmpty()) {
                    BuildingInstance victim = candidates.get(
                            java.util.concurrent.ThreadLocalRandom.current().nextInt(candidates.size()));
                    victim.setStatus(pioneer.colony.colony.BuildingInstance.Status.DAMAGED);
                    ColonySavedData.get(server).setDirty();
                    notifyOwner(server, colony, buildingName(victim) + " 在袭击中受损（效率 50%，需维修）。");
                }
            }
        }
        // 波次推进
        if (state.waveIndex < def.waves().size() && now >= state.nextWaveWall) {
            Wave wave = def.waves().get(state.waveIndex);
            spawnWave(server, level, colony, state, wave, hq);
            state.waveIndex++;
            state.nextWaveWall = now + Math.max(5, wave.delaySeconds()) * 1000L;
        }
        // 结束判定：确认全歼（名单清空，未加载区块不误判）或超时撤退 = 防守成功
        boolean timeout = now - state.raidStartWall > Config.RAID_TIMEOUT_SECONDS.get() * 1000L;
        if ((state.raiderIds.isEmpty() && raiders.isEmpty()) || timeout) {
            victory(server, level, colony, state, timeout);
        }
    }

    private static void beginRaid(MinecraftServer server, ServerLevel level, Colony colony,
                                  RaidState state, RaidDef def, BlockPos hq) {
        state.phase = Phase.ACTIVE;
        state.raidStartWall = System.currentTimeMillis();
        state.nextWaveWall = state.raidStartWall;
        state.lastBuildingDamageWall = state.raidStartWall;
        // 全体市民生成参战实体（守卫 100%，其他 25%；受研究加成）
        spawnCitizens(server, level, colony, state, hq);
        notifyOwner(server, colony, "⚠ 袭击开始！全体市民已参战（"
                + state.citizenIds.size() + " 人出战）。");
    }

    private static void spawnWave(MinecraftServer server, ServerLevel level, Colony colony,
                                  RaidState state, Wave wave, BlockPos hq) {
        int cap = Config.RAID_MOB_CAP.get();
        // 实体预算按已生成总数（含未解析/未加载区块）计，防止绕过上限
        int current = state.raiderIds.size();
        int toSpawn = Math.min(wave.count(), Math.max(0, cap - current));
        Optional<EntityType<?>> type = EntityType.byString(wave.entity());
        if (type.isEmpty()) {
            raidLog("未知袭击实体：" + wave.entity());
            return;
        }
        for (int i = 0; i < toSpawn; i++) {
            BlockPos pos = sectorPoint(level, hq, state.sector);
            Mob mob = (Mob) type.get().create(level);
            if (mob == null) {
                continue;
            }
            mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
            mob.addTag(TAG_RAID_MOB);
            mob.setPersistenceRequired();
            // 孤儿清理依据：persistentData 必须带 ColonyUUID（join-event 判定归属）
            mob.getPersistentData().putUUID("ColonyUUID", state.colonyUuid);
            applyEquipment(mob, wave.equipment());
            level.addFreshEntity(mob);
            state.raiderIds.add(mob.getUUID());
        }
        notifyOwner(server, colony, "⚠ 袭击波抵达：" + wave.entity().replace("minecraft:", "")
                + " ×" + toSpawn + "（" + sectorName(state.sector) + "方向）");
    }

    private static void applyEquipment(Mob mob, List<String> equipment) {
        EquipmentSlot[] slots = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < Math.min(equipment.size(), slots.length); i++) {
            try {
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(equipment.get(i)));
                if (item != null) {
                    mob.setItemSlot(slots[i], new ItemStack(item));
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 全体市民参战实体：守卫 = 守卫哨在岗数；其余 = 人口-守卫。 */
    private static void spawnCitizens(MinecraftServer server, ServerLevel level, Colony colony,
                                      RaidState state, BlockPos hq) {
        int guards = 0;
        for (BuildingInstance b : colony.getBuildings()) {
            if ("guard_post".equals(b.definitionId())
                    && b.status() == pioneer.colony.colony.BuildingInstance.Status.ACTIVE) {
                guards += Math.max(0, (int) Math.round(pioneer.colony.colony.BuildingDefinitions
                        .get(b.definitionId()).map(d -> d.tierWorkerSlots(b.tier()) * b.getLastFillRate()).orElse(0.0)));
            }
        }
        guards = Math.min(guards, colony.getPopulation());
        int workers = Math.max(0, colony.getPopulation() - guards);
        double guardBonus = 1.0 + colony.productionBonusFor(List.of("__guard_power"));
        double guardDamage = Config.CITIZEN_BASE_DAMAGE.get() * Config.CITIZEN_GUARD_MULTIPLIER.get() * guardBonus;
        double workerDamage = Config.CITIZEN_BASE_DAMAGE.get() * Config.CITIZEN_WORKER_MULTIPLIER.get();
        java.util.Random random = new java.util.Random();
        spawnCitizensBatch(level, colony, state, hq, guards, guardDamage, true, random);
        spawnCitizensBatch(level, colony, state, hq, workers, workerDamage, false, random);
    }

    private static void spawnCitizensBatch(ServerLevel level, Colony colony, RaidState state, BlockPos hq,
                                           int count, double damage, boolean guard,
                                           java.util.Random random) {
        for (int i = 0; i < count; i++) {
            CitizenEntity citizen = ModRegistry.CITIZEN_ENTITY.get().create(level);
            if (citizen == null) {
                continue;
            }
            double ang = random.nextDouble() * Math.PI * 2;
            double r = 2 + random.nextDouble() * 4;
            double x = hq.getX() + 0.5 + Math.cos(ang) * r;
            double z = hq.getZ() + 0.5 + Math.sin(ang) * r;
            citizen.moveTo(x, hq.getY(), z, random.nextFloat() * 360, 0);
            String name = NAMES[random.nextInt(NAMES.length)] + (guard ? "（守卫）" : "（民兵）");
            citizen.setup(colony.uuid(), guard, name, damage);
            if (guard) {
                citizen.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(
                        BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace("iron_sword"))));
            }
            level.addFreshEntity(citizen);
            state.citizenIds.add(citizen.getUUID());
        }
    }

    /** 阵亡写回（06 §6：伤亡由真实战斗结算并写回人口，岗位自动释放=填充率随人口重算）。 */
    public static void onCitizenDeath(CitizenEntity citizen) {
        if (!(citizen.level() instanceof ServerLevel level) || citizen.colonyUuid() == null) {
            return;
        }
        Colony colony = ColonySavedData.get(level.getServer()).colonyByUuid(citizen.colonyUuid());
        if (colony == null) {
            return;
        }
        colony.setPopulation(Math.max(0, colony.getPopulation() - 1));
        ColonySavedData.get(level.getServer()).setDirty();
        String name = citizen.getCustomName() != null ? citizen.getCustomName().getString() : "市民";
        notifyOwner(level.getServer(), colony, "☠ " + name + " 因袭击身亡（人口 " + colony.getPopulation() + "）。");
    }

    private static void victory(MinecraftServer server, ServerLevel level, Colony colony,
                                RaidState state, boolean byTimeout) {
        // 威胁值 -30%（随财富自然回升：威胁向推导值收敛）
        double reduced = colony.getThreatValue() * (1.0 - Config.VICTORY_THREAT_REDUCTION_PERCENT.get() / 100.0);
        colony.setThreatValue(reduced);
        // 战利品入仓库
        RaidDef def = RaidConfigs.get(state.tier);
        StringBuilder lootText = new StringBuilder();
        if (def != null) {
            for (pioneer.colony.colony.ItemCost loot : def.loot()) {
                colony.insertOutput(loot.item(), loot.count());
                if (lootText.length() > 0) {
                    lootText.append("、");
                }
                lootText.append(loot.item().replace("minecraft:", "")).append("×").append(loot.count());
            }
        }
        removeState(server, state, colony);
        ColonySavedData.get(server).setDirty();
        notifyOwner(server, colony, String.format("★ 防守成功%s！威胁值 -%d%%（当前 %.0f）%s",
                byTimeout ? "（袭击者超时撤退）" : "（袭击者全歼）",
                Config.VICTORY_THREAT_REDUCTION_PERCENT.get(), colony.getThreatValue(),
                lootText.length() > 0 ? "，战利品入仓库：" + lootText : ""));
    }

    private static void removeState(MinecraftServer server, RaidState state, Colony colony) {
        states.remove(state.colonyUuid);
        // 清除参战/袭击实体
        for (ServerLevel lvl : server.getAllLevels()) {
            for (var entity : lvl.getEntities().getAll()) {
                if (entity.getTags().contains(TAG_RAID_MOB) || entity.getTags().contains(TAG_CITIZEN)) {
                    var data = entity.getPersistentData();
                    if (data.hasUUID("ColonyUUID") && (colony == null || data.getUUID("ColonyUUID").equals(state.colonyUuid))) {
                        entity.discard();
                    }
                }
            }
        }
    }

    /** 无活跃袭击的标记实体自清（服务器重启后孤儿清理，join-event 调用）。 */
    public static void cleanupOrphan(net.minecraft.world.entity.Entity entity) {
        if (entity.level().isClientSide) {
            return;
        }
        boolean tagged = entity.getTags().contains(TAG_RAID_MOB) || entity.getTags().contains(TAG_CITIZEN);
        if (!tagged) {
            return;
        }
        var data = entity.getPersistentData();
        if (data.hasUUID("ColonyUUID")) {
            Colony colony = ColonySavedData.get(((ServerLevel) entity.level()).getServer()).colonyByUuid(data.getUUID("ColonyUUID"));
            RaidState state = colony == null ? null : states.get(colony.uuid());
            if (state == null || state.phase != Phase.ACTIVE) {
                entity.discard();
            }
        } else {
            entity.discard();
        }
    }

    // —— 调试/查询 ——

    /** 菜单显示用：当前袭击态剩余秒数（-1 = 无袭击）。 */
    public static int raidCountdownSeconds(MinecraftServer server, Colony colony) {
        RaidState state = states.get(colony.uuid());
        if (state == null) {
            return -1;
        }
        long now = System.currentTimeMillis();
        if (state.phase == Phase.ACTIVE) {
            long remain = Math.max(0, (state.raidStartWall + Config.RAID_TIMEOUT_SECONDS.get() * 1000L - now) / 1000);
            return (int) Math.min(remain, Config.RAID_TIMEOUT_SECONDS.get());
        }
        long remain = Math.max(0, (state.phaseEndWall - now) / 1000);
        return (int) Math.min(remain, Config.RAID_COUNTDOWN_SECONDS.get());
    }

    public static boolean hasActiveRaid(UUID colonyUuid) {
        RaidState state = states.get(colonyUuid);
        return state != null && state.phase == Phase.ACTIVE;
    }

    public static String debugStatus(MinecraftServer server, Colony colony) {
        RaidState state = states.get(colony.uuid());
        if (state == null) {
            return String.format("无活跃袭击。威胁值 %.1f（档位 %d）。", colony.getThreatValue(), tierFor(colony.getThreatValue()));
        }
        long remain;
        if (state.phase == Phase.ACTIVE) {
            remain = Math.max(0, (state.raidStartWall + Config.RAID_TIMEOUT_SECONDS.get() * 1000L
                    - System.currentTimeMillis()) / 1000);
        } else {
            remain = Math.max(0, (state.phaseEndWall - System.currentTimeMillis()) / 1000);
        }
        return String.format("袭击态 %s：档位 %d，方向 %s，%s %d 秒。威胁值 %.1f。实体：袭击怪 %d/%d（未解析 %d），市民 %d。",
                state.phase == Phase.COUNTDOWN ? "倒计时" : "战斗中", state.tier, sectorName(state.sector),
                state.phase == Phase.COUNTDOWN ? "距来袭" : "超时剩余", remain,
                colony.getThreatValue(),
                liveRaiders(server.getLevel(Level.OVERWORLD), state).size(),
                Config.RAID_MOB_CAP.get(), state.raiderIds.size(), state.citizenIds.size());
    }

    /** OP 调试：直接把威胁值顶到指定档位阈值（触发正常倒计时）。 */
    public static String debugTrigger(MinecraftServer server, Colony colony, int tier) {
        colony.setThreatValue(thresholdOf(tier));
        checkTrigger(server, colony);
        RaidState state = states.get(colony.uuid());
        if (state != null && state.phase == Phase.COUNTDOWN) {
            state.phaseEndWall = Math.min(state.phaseEndWall,
                    System.currentTimeMillis() + 15_000); // 调试：15 秒短倒计时
            return "已触发 " + tier + " 档袭击倒计时（调试 15 秒）。" + debugStatus(server, colony);
        }
        return "触发失败（配置缺失？）。";
    }

    /** OP 调试：跳过倒计时与人位检查立即开打。 */
    public static String debugSpawnNow(MinecraftServer server, Colony colony, int tier) {
        if (states.containsKey(colony.uuid())) {
            return "已有袭击态：" + debugStatus(server, colony);
        }
        RaidDef def = RaidConfigs.get(tier);
        if (def == null) {
            return "袭击配置缺失：tier_" + tier;
        }
        RaidState state = new RaidState(colony.uuid());
        state.tier = tier;
        state.sector = java.util.concurrent.ThreadLocalRandom.current().nextInt(8);
        states.put(colony.uuid(), state);
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) {
            return "主世界不可用";
        }
        beginRaid(server, level, colony, state, def, hqPos(colony, level));
        return "已强制开打（" + tier + " 档）。" + debugStatus(server, colony);
    }

    public static String debugEnd(MinecraftServer server, Colony colony) {
        RaidState state = states.get(colony.uuid());
        if (state == null) {
            return "无活跃袭击。";
        }
        victory(server, server.getLevel(Level.OVERWORLD), colony, state, false);
        return "已强制结算（防守成功）。";
    }

    // —— 工具 ——

    private static BlockPos hqPos(Colony colony, ServerLevel level) {
        BlockPos hq = colony.getBuildings().stream()
                .filter(b -> "hq".equals(b.definitionId()) && b.hasOrigin())
                .map(b -> net.minecraft.core.BlockPos.of(b.origin()))
                .findFirst().orElse(null);
        if (hq == null) {
            long chunk = colony.getHqChunk();
            int cx = new net.minecraft.world.level.ChunkPos(chunk).x;
            int cz = new net.minecraft.world.level.ChunkPos(chunk).z;
            hq = new BlockPos(cx << 4, level.getHeight(Heightmap.Types.WORLD_SURFACE, cx << 4, cz << 4), cz << 4);
        }
        return hq;
    }

    private static boolean hasPlayerNear(ServerLevel level, BlockPos pos, int range) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) < (double) range * range) {
                return true;
            }
        }
        return false;
    }

    private static List<Monster> liveRaiders(ServerLevel level, RaidState state) {
        List<Monster> out = new ArrayList<>();
        if (level == null) {
            return out;
        }
        for (UUID id : List.copyOf(state.raiderIds)) {
            net.minecraft.world.entity.Entity e = level.getEntity(id);
            if (e instanceof Monster mob && mob.isAlive()) {
                out.add(mob);
            } else if (e != null) {
                // 确认死亡：从名单移除（全歼判定依据）
                state.raiderIds.remove(id);
            }
            // e == null：区块未加载（实体下落未知）——保留在列，不视为阵亡；超时路径兜底
        }
        return out;
    }

    /** 扇区内随机点（半径 40~56，贴地表）。 */
    private static BlockPos sectorPoint(ServerLevel level, BlockPos hq, int sector) {
        var random = java.util.concurrent.ThreadLocalRandom.current();
        double angle = Math.toRadians(sector * 45.0) + Math.toRadians(random.nextDouble(-22.5, 22.5));
        double radius = 40 + random.nextDouble(16);
        int x = hq.getX() + (int) Math.round(Math.sin(angle) * radius);
        int z = hq.getZ() + (int) Math.round(-Math.cos(angle) * radius);
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        return new BlockPos(x, y, z);
    }

    private static double thresholdOf(int tier) {
        for (String entry : Config.RAID_TIER_THRESHOLDS.get()) {
            String[] parts = entry.split(":");
            if (parts[0].trim().equals(String.valueOf(tier))) {
                try {
                    return Double.parseDouble(parts[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 100 * tier;
    }

    private static String sectorName(int sector) {
        String[] names = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
        return names[sector % 8];
    }

    private static String buildingName(BuildingInstance b) {
        return pioneer.colony.colony.BuildingDefinitions.get(b.definitionId())
                .map(d -> d.name()).orElse(b.definitionId());
    }

    private static void notifyOwner(MinecraftServer server, Colony colony, String message) {
        ServerPlayer owner = server.getPlayerList().getPlayer(colony.owner());
        if (owner != null) {
            owner.sendSystemMessage(Component.literal("【殖民地经营】" + message));
        } else if (colony.owner().equals(pioneer.colony.colony.ColonyManager.DEBUG_OWNER)) {
            // 调试无主殖民地：广播到日志（RCON 冒烟可见）
            raidLog(message);
        }
    }

    private static void raidLog(String message) {
        pioneer.colony.PioneerColony.LOGGER.info("[殖民地经营][袭击] {}", message);
    }
}
