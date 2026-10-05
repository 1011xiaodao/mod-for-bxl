package pioneer.colony.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import pioneer.colony.PioneerColony;
import pioneer.colony.colony.BuildingDefinition;
import pioneer.colony.colony.BuildingDefinitions;
import pioneer.colony.colony.BuildingInstance;
import pioneer.colony.colony.Colony;
import pioneer.colony.colony.ColonySavedData;
import pioneer.colony.colony.ItemCost;
import pioneer.colony.config.Config;

/**
 * 经济 tick（06 §3.3）：每 60s 对每个殖民地跑一轮纯数据公式运算——
 * 输入扣减 → 产出入缓冲 → 维护消耗 → 食物消耗/烹饪 → 人口食物 → 增长/断粮。
 * 离线补算：结算量 = min(离线时长, 补算上限) ÷ 60 × 公式，一次性入账并生成汇总。
 * <p>
 * 主线程纪律：本方法体量小（纯内存公式），M6.1 在主线程执行并以毫秒计时上报（Spark 或等效证据）；
 * 市场撮合/领地扫描等重任务按里程碑进后台分片。
 */
public final class EconomyTicker {
    /** 游戏日长度（真实分钟）——原版昼夜周期常量。 */
    public static final int GAME_DAY_MINUTES = 20;

    private static double lastDurationMs = 0.0;
    private static double maxDurationMs = 0.0;
    private static int lastColonyCount = 0;

    private EconomyTicker() {
    }

    public static void settleAll(MinecraftServer server, boolean force) {
        ColonySavedData data = ColonySavedData.get(server);
        if (data.all().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long startNanos = System.nanoTime();
        for (Colony colony : data.all()) {
            settleColony(colony, now, force);
            // 威胁值收敛 + 档位触发（06 §6，随经济 tick 同频）
            pioneer.colony.raid.RaidManager.computeThreat(colony);
            pioneer.colony.raid.RaidManager.checkTrigger(server, colony);
        }
        double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        lastDurationMs = durationMs;
        maxDurationMs = Math.max(maxDurationMs, durationMs);
        lastColonyCount = data.all().size();
        data.setDirty();
        if (force || Config.DEBUG_LOG_ECONOMY_TICK.get()) {
            PioneerColony.LOGGER.info("[殖民地经营][经济 tick] {} 个殖民地，主线程耗时 {} ms（会话峰值 {} ms）",
                    lastColonyCount, String.format("%.3f", durationMs), String.format("%.3f", maxDurationMs));
        }
    }

    public static double getLastDurationMs() {
        return lastDurationMs;
    }

    public static double getMaxDurationMs() {
        return maxDurationMs;
    }

    public static int getLastColonyCount() {
        return lastColonyCount;
    }

    private static void settleColony(Colony colony, long now, boolean force) {
        if (colony.getLastTickWallTime() == 0L) {
            // 新建殖民地：从现在起算，不补算
            colony.setLastTickWallTime(now);
            return;
        }
        long elapsedMs = now - colony.getLastTickWallTime();
        if (elapsedMs < 0) {
            // 系统时钟回退：不产出负进度，仅对齐时间戳
            colony.setLastTickWallTime(now);
            return;
        }
        long gapMinutes = elapsedMs / 60_000L;
        long capMinutes = (long) Config.OFFLINE_CATCHUP_CAP_HOURS.get() * 60L;
        boolean offlineCatchup = gapMinutes >= 2;
        long minutes;
        if (force) {
            minutes = Math.max(1, Math.min(gapMinutes, capMinutes));
        } else {
            if (gapMinutes < 1) {
                return;
            }
            minutes = Math.min(gapMinutes, capMinutes);
        }
        SettlementResult result = runSettlement(colony, minutes);
        colony.setLastTickWallTime(now);
        if (offlineCatchup) {
            OfflineSummary summary = new OfflineSummary(minutes, gapMinutes > capMinutes, now,
                    result.creditsDelta, result.produced, result.consumed, result.events);
            colony.setPendingSummary(summary);
            PioneerColony.LOGGER.info("[殖民地经营] 殖民地[{}]离线补算 {} 分钟{}：产出 {} 种、消耗 {} 种、事件 {} 条",
                    colony.getName(), minutes, gapMinutes > capMinutes ? "（已按补算上限截断）" : "",
                    result.produced.size(), result.consumed.size(), result.events.size());
        }
    }

    /** 对一个殖民地结算 minutes 分钟（纯公式，可离线批量）。 */
    public static SettlementResult runSettlement(Colony colony, long minutes) {
        SettlementResult result = new SettlementResult();
        if (colony.getHqState() == Colony.HqState.STOPPED) {
            result.events.add("总部停摆，殖民地公式暂停（原地修复后恢复）");
            return result;
        }

        // —— 1) 人口食物消耗（决定食物充足率 → 幸福度 → 乘数） ——
        int population = colony.getPopulation();
        double foodRate = 1.0;
        if (population > 0) {
            double demandPoints = population * Config.FOOD_POINTS_PER_CITIZEN_PER_DAY.get()
                    * minutes / (double) GAME_DAY_MINUTES;
            // M6.5 日程就餐已从缓冲实扣的部分在此抵扣（残留滚存下期），防双重记账
            double offset = Math.min(demandPoints, colony.getFedPointsCarry());
            colony.setFedPointsCarry(colony.getFedPointsCarry() - offset);
            demandPoints -= offset;
            if (demandPoints > 1e-9) {
                foodRate = consumeFood(colony, demandPoints, result);
                if (foodRate < 1.0) {
                    result.events.add(String.format("食物不足（满足率 %.0f%%），生产降效", foodRate * 100));
                    colony.setStarveMinutes(colony.getStarveMinutes() + minutes * (1.0 - foodRate));
                    long loss = (long) (colony.getStarveMinutes() / GAME_DAY_MINUTES);
                    if (loss > 0) {
                        colony.setStarveMinutes(colony.getStarveMinutes() - loss * GAME_DAY_MINUTES);
                        colony.setPopulation(population - (int) loss);
                        result.events.add("持续断粮，人口 -" + loss + "（当前 " + colony.getPopulation() + "）");
                    }
                } else {
                    colony.setStarveMinutes(0);
                }
            }
        }

        // 幸福度（06 §8.3，占位数值；供暖因子未激活按 0.5 中性，不奖不罚）
        int residentSlots = 0;
        for (BuildingInstance b : colony.getBuildings()) {
            if (b.status() == BuildingInstance.Status.ACTIVE) {
                residentSlots += BuildingDefinitions.get(b.definitionId())
                        .map(d -> d.tierResidentSlots(b.tier())).orElse(0);
            }
        }
        double housingRate = colony.getPopulation() > 0
                ? Math.min(1.0, residentSlots / (double) Math.max(1, colony.getPopulation()))
                : 1.0;
        double happiness = Config.HAPPINESS_BASE.get()
                + Config.HAPPINESS_HEATING_WEIGHT.get() * 0.5
                + Config.HAPPINESS_FOOD_WEIGHT.get() * foodRate
                + Config.HAPPINESS_HOUSING_WEIGHT.get() * housingRate;
        colony.setHappiness(happiness);
        double happinessMultiplier = 0.5 + happiness / 100.0;

        // —— 2) 逐建筑：维护 + 生产 ——
        int remainingWorkers = colony.getPopulation();
        for (BuildingInstance building : colony.getBuildings()) {
            BuildingDefinition def = BuildingDefinitions.get(building.definitionId()).orElse(null);
            if (def == null) {
                result.events.add("未知建筑定义：" + building.definitionId() + "（定义被删除？）");
                continue;
            }
            boolean damaged = building.status() == BuildingInstance.Status.DAMAGED;
            if (building.status() != BuildingInstance.Status.ACTIVE && !damaged) {
                continue;
            }
            // 岗位填充率（人口自动分配；GUI 调配 M6.2 起）
            int slots = def.tierWorkerSlots(building.tier());
            int assigned = Math.min(remainingWorkers, slots);
            remainingWorkers -= assigned;
            double fillRate = slots == 0 ? 1.0 : assigned / (double) slots;
            building.setLastFillRate(fillRate);

            // 维护（先结算，断供影响本期效率）
            building.setMaintenanceAccum(building.getMaintenanceAccum() + minutes / 60.0);
            while (building.getMaintenanceAccum() >= 1.0) {
                if (consumeCosts(colony, def.maintenancePerHour(), result)) {
                    building.setMaintenanceAccum(building.getMaintenanceAccum() - 1.0);
                    building.setMaintenanceSatisfied(true);
                } else {
                    building.setMaintenanceSatisfied(false);
                    result.events.add(def.name() + " 维护断供，效率减半");
                    break;
                }
            }
            double maintenanceFactor = building.isMaintenanceSatisfied()
                    ? 1.0 : Config.MAINTENANCE_HALF_EFFICIENCY_FACTOR.get();
            // 全局加成 = 1 + colony 通道研究效果（标签命中，M6.3）；受损状态效率 50%（M6.4）
            double globalBonus = (1.0 + colony.productionBonusFor(def.tags()))
                    * (damaged ? Config.DAMAGED_EFFICIENCY_FACTOR.get() : 1.0);

            // 生产（受损伤 M6.4 后在此并入建筑状态因子）。
            // 幸福度乘数只作用于产出（士气提升单位投入产出比），不放大消耗
            String outputItem = def.outputItem();
            double outputPerMin = def.tierOutputPerMin(building.tier());
            if (outputItem != null && outputPerMin > 0) {
                double inputRatio = inputRatio(colony, def.inputPerMin(), minutes);
                building.setInputShortage(inputRatio < 1.0);
                double baseEfficiency = fillRate * globalBonus * maintenanceFactor * inputRatio;
                if (baseEfficiency > 0) {
                    consumeProportional(colony, def.inputPerMin(), minutes, baseEfficiency, result);
                    double efficiency = baseEfficiency * happinessMultiplier;
                    double producedFrac = outputPerMin * minutes * efficiency + building.getProductionAccum();
                    long produced = (long) producedFrac;
                    building.setProductionAccum(producedFrac - produced);
                    if (produced > 0) {
                        long accepted = colony.insertOutput(outputItem, produced);
                        building.setStorageFull(accepted < produced);
                        if (accepted < produced) {
                            result.events.add(def.name() + " 仓库已满，超出产出丢弃（" + (produced - accepted) + "）");
                        }
                        if (accepted > 0) {
                            result.produced.merge(outputItem, accepted, Long::sum);
                        }
                    }
                }
            }
        }

        // —— 3) 人口自然增长（食物盈余且有空床位；乘幸福度乘数；正式验收 M6.6） ——
        int citizenCap = residentSlots + colony.getResearchCitizenCapBonus();
        if (foodRate >= 1.0 && colony.getPopulation() < citizenCap) {
            colony.setGrowthProgress(colony.getGrowthProgress()
                    + minutes / (double) GAME_DAY_MINUTES * happinessMultiplier);
            int interval = Config.POP_GROWTH_INTERVAL_GAME_DAYS.get();
            if (colony.getGrowthProgress() >= interval) {
                colony.setGrowthProgress(colony.getGrowthProgress() - interval);
                colony.setPopulation(colony.getPopulation() + 1);
                result.events.add("人口自然增长 +1（当前 " + colony.getPopulation() + "）");
            }
        }
        return result;
    }

    /** 满足输入比 = min(各输入 库存/需求)，夹在 [0,1]。 */
    private static double inputRatio(Colony colony, List<ItemCost> inputs, long minutes) {
        if (inputs.isEmpty()) {
            return 1.0;
        }
        double ratio = Double.MAX_VALUE;
        for (ItemCost cost : inputs) {
            double need = cost.count() * (double) minutes;
            if (need <= 0) {
                continue;
            }
            ratio = Math.min(ratio, colony.countOf(cost.item()) / need);
        }
        return ratio == Double.MAX_VALUE ? 1.0 : Math.max(0.0, Math.min(1.0, ratio));
    }

    /** 按效率比例扣减输入（consumed ≤ 库存，保证不透支）。 */
    private static void consumeProportional(Colony colony, List<ItemCost> inputs, long minutes,
                                            double efficiency, SettlementResult result) {
        for (ItemCost cost : inputs) {
            long consume = (long) Math.floor(cost.count() * (double) minutes * efficiency);
            if (consume > 0) {
                long took = colony.takeItems(cost.item(), consume);
                if (took > 0) {
                    result.consumed.merge(cost.item(), took, Long::sum);
                }
            }
        }
    }

    /** 整组扣料（维护费）：全部物品足够才扣，返回是否成功。 */
    private static boolean consumeCosts(Colony colony, List<ItemCost> costs, SettlementResult result) {
        if (costs.isEmpty()) {
            return true;
        }
        for (ItemCost cost : costs) {
            if (colony.countOf(cost.item()) < cost.count()) {
                return false;
            }
        }
        for (ItemCost cost : costs) {
            long took = colony.takeItems(cost.item(), cost.count());
            if (took > 0) {
                result.consumed.merge(cost.item(), took, Long::sum);
            }
        }
        return true;
    }

    /** 消耗食物点；先吃食物点低的（省着吃）。返回满足率 [0,1]。 */
    private static double consumeFood(Colony colony, double demandPoints, SettlementResult result) {
        List<Map.Entry<String, Long>> foods = new ArrayList<>();
        for (Map.Entry<String, Long> e : colony.bufferSnapshot().entrySet()) {
            double value = FoodValues.foodValue(e.getKey());
            if (value > 0 && e.getValue() > 0) {
                foods.add(Map.entry(e.getKey(), e.getValue()));
            }
        }
        foods.sort(Comparator.comparingDouble(e -> FoodValues.foodValue(e.getKey())));
        double remaining = demandPoints;
        for (Map.Entry<String, Long> food : foods) {
            if (remaining <= 0) {
                break;
            }
            double value = FoodValues.foodValue(food.getKey());
            long stock = colony.countOf(food.getKey());
            long take = Math.min(stock, (long) Math.ceil(remaining / value));
            if (take > 0) {
                take = colony.takeItems(food.getKey(), take);
                if (take > 0) {
                    result.consumed.merge(food.getKey(), take, Long::sum);
                    remaining -= take * value;
                }
            }
        }
        return demandPoints <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, (demandPoints - Math.max(0, remaining)) / demandPoints));
    }

    /** 缓冲内食物点总量（GUI「可维持 N 天」指标用）。 */
    public static double bufferFoodPoints(Colony colony) {
        double total = 0;
        for (Map.Entry<String, Long> e : colony.bufferSnapshot().entrySet()) {
            total += FoodValues.foodValue(e.getKey()) * e.getValue();
        }
        return total;
    }

    /** 缓冲食物可维持天数 = 食物点存量 ÷ 日消耗。人口为 0 时返回 -1（无穷）。 */
    public static double foodDaysRemaining(Colony colony) {
        int population = colony.getPopulation();
        if (population <= 0) {
            return -1;
        }
        double dailyDemand = population * Config.FOOD_POINTS_PER_CITIZEN_PER_DAY.get();
        return bufferFoodPoints(colony) / dailyDemand;
    }

    /** 结算结果（不可变快照，用于离线汇总与命令反馈）。 */
    public static final class SettlementResult {
        public final LinkedHashMap<String, Long> produced = new LinkedHashMap<>();
        public final LinkedHashMap<String, Long> consumed = new LinkedHashMap<>();
        public final List<String> events = new ArrayList<>();
        public long creditsDelta = 0;

        public String describe() {
            StringJoiner joiner = new StringJoiner("；");
            if (!produced.isEmpty()) {
                joiner.add("产出 " + produced.size() + " 种");
            }
            if (!consumed.isEmpty()) {
                joiner.add("消耗 " + consumed.size() + " 种");
            }
            if (!events.isEmpty()) {
                joiner.add("事件 " + events.size() + " 条");
            }
            return joiner.length() == 0 ? "无变化" : joiner.toString();
        }
    }
}
