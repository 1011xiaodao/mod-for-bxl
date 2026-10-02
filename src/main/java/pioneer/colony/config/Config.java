package pioneer.colony.config;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 服务端配置（数值全部参数化，禁止硬编码；建筑逐级数值在 datapack JSON，见 docs/数值速调手册.md）。
 */
public final class Config {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue ECONOMY_TICK_SECONDS;
    public static final ModConfigSpec.IntValue OFFLINE_CATCHUP_CAP_HOURS;
    public static final ModConfigSpec.LongValue STARTING_CREDITS;
    public static final ModConfigSpec.IntValue INITIAL_POPULATION;
    public static final ModConfigSpec.IntValue COLONIES_PER_PLAYER;
    public static final ModConfigSpec.IntValue BUFFER_BASE_TYPES;
    public static final ModConfigSpec.LongValue BUFFER_PER_TYPE_CAPACITY;
    public static final ModConfigSpec.DoubleValue FOOD_POINTS_PER_CITIZEN_PER_DAY;
    public static final ModConfigSpec.DoubleValue FOOD_GLOBAL_MULTIPLIER;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> FOOD_VALUE_OVERRIDES;
    public static final ModConfigSpec.IntValue POP_GROWTH_INTERVAL_GAME_DAYS;

    public static final ModConfigSpec.DoubleValue HAPPINESS_BASE;
    public static final ModConfigSpec.DoubleValue HAPPINESS_HEATING_WEIGHT;
    public static final ModConfigSpec.DoubleValue HAPPINESS_FOOD_WEIGHT;
    public static final ModConfigSpec.DoubleValue HAPPINESS_HOUSING_WEIGHT;

    public static final ModConfigSpec.IntValue TERRITORY_MAX_CHUNKS;
    public static final ModConfigSpec.IntValue CHUNK_PRICE_BASE;
    public static final ModConfigSpec.IntValue PURCHASE_COOLDOWN_SECONDS;
    public static final ModConfigSpec.IntValue ABANDON_REFUND_PERCENT;
    public static final ModConfigSpec.BooleanValue ALLOW_ENCLAVE;

    public static final ModConfigSpec.DoubleValue MAINTENANCE_HALF_EFFICIENCY_FACTOR;

    public static final ModConfigSpec.BooleanValue CONSTRUCTION_FEE_ENABLED;
    public static final ModConfigSpec.IntValue CONSTRUCTION_FEE_BASE;
    public static final ModConfigSpec.IntValue DISMANTLE_SECONDS;
    public static final ModConfigSpec.IntValue DISMANTLE_REFUND_PERCENT;

    public static final ModConfigSpec.BooleanValue RESEARCH_STUB_ENABLED;
    public static final ModConfigSpec.IntValue RESEARCH_ORG_FEE;
    public static final ModConfigSpec.DoubleValue RESEARCH_SPEED_PER_SCIENTIST;
    public static final ModConfigSpec.DoubleValue RESEARCH_SPEED_CAP;

    public static final ModConfigSpec.DoubleValue THREAT_COEF_BUILDING;
    public static final ModConfigSpec.DoubleValue THREAT_BUILDING_WEIGHT;
    public static final ModConfigSpec.DoubleValue THREAT_COEF_BUFFER_VALUE;
    public static final ModConfigSpec.DoubleValue THREAT_COEF_POPULATION;
    public static final ModConfigSpec.DoubleValue THREAT_COEF_CREDITS;
    public static final ModConfigSpec.DoubleValue THREAT_CONVERGENCE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> RAID_TIER_THRESHOLDS;
    public static final ModConfigSpec.IntValue RAID_COUNTDOWN_SECONDS;
    public static final ModConfigSpec.IntValue RAID_POSTPONE_SECONDS;
    public static final ModConfigSpec.IntValue RAID_PLAYER_RANGE;
    public static final ModConfigSpec.IntValue RAID_MOB_CAP;
    public static final ModConfigSpec.IntValue RAID_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue VICTORY_THREAT_REDUCTION_PERCENT;
    public static final ModConfigSpec.DoubleValue CITIZEN_BASE_DAMAGE;
    public static final ModConfigSpec.DoubleValue CITIZEN_GUARD_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue CITIZEN_WORKER_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue DAMAGED_EFFICIENCY_FACTOR;
    public static final ModConfigSpec.IntValue REPAIR_BUILD_SECONDS_FACTOR_PERCENT;
    public static final ModConfigSpec.IntValue REPAIR_COST_PERCENT;
    public static final ModConfigSpec.DoubleValue HQ_MAX_HEALTH;
    public static final ModConfigSpec.DoubleValue RAID_BUILDING_DAMAGE_CHANCE;

    public static final ModConfigSpec.BooleanValue DEBUG_LOG_ECONOMY_TICK;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("economy");
        ECONOMY_TICK_SECONDS = b.comment("经济 tick 周期（秒）。经济为每 60s 一次纯数据公式运算（降频纪律）")
                .defineInRange("tickSeconds", 60, 10, 600);
        OFFLINE_CATCHUP_CAP_HOURS = b.comment("离线补算上限（小时），超出部分不结算")
                .defineInRange("offlineCatchupCapHours", 24, 1, 168);
        STARTING_CREDITS = b.comment("创建殖民地赠送的信用点启动资金")
                .defineInRange("startingCredits", 500, 0, 1_000_000_000L);
        INITIAL_POPULATION = b.comment("初始人口（总部自带）")
                .defineInRange("initialPopulation", 5, 0, 10_000);
        COLONIES_PER_PLAYER = b.comment("每玩家殖民地数量上限")
                .defineInRange("coloniesPerPlayer", 1, 1, 10);
        BUFFER_BASE_TYPES = b.comment("资源缓冲基础物品种类上限（总部自带小缓冲）")
                .defineInRange("bufferBaseTypes", 5, 1, 512);
        BUFFER_PER_TYPE_CAPACITY = b.comment("资源缓冲单类物品容量上限")
                .defineInRange("bufferPerTypeCapacity", 256, 1, 10_000_000L);
        FOOD_POINTS_PER_CITIZEN_PER_DAY = b.comment("每市民每游戏日消耗的食物点（待项目主数值定稿）")
                .defineInRange("foodPointsPerCitizenPerDay", 12.0, 0.0, 1000.0);
        FOOD_GLOBAL_MULTIPLIER = b.comment("食物点全局倍率（食物点 = 物品饱和度值 × 倍率，个别物品可覆盖）")
                .defineInRange("foodGlobalMultiplier", 1.0, 0.0, 100.0);
        FOOD_VALUE_OVERRIDES = b.comment("个别物品食物点覆盖表，格式「物品id=数值」，如 minecraft:bread=6.0")
                .defineList("foodValueOverrides", List.of(), o -> o instanceof String s && s.contains("="));
        POP_GROWTH_INTERVAL_GAME_DAYS = b.comment("人口自然增长间隔（游戏日）：食物盈余且有空床位时 +1（乘幸福度乘数）")
                .defineInRange("popGrowthIntervalGameDays", 1, 1, 30);
        b.pop();

        b.push("happiness");
        HAPPINESS_BASE = b.comment("幸福度基础值（0~100 公式占位数值，M6.6 定稿）")
                .defineInRange("base", 25.0, 0.0, 100.0);
        HAPPINESS_HEATING_WEIGHT = b.comment("供暖达标率权重（四季供暖 M6.6 激活；未激活时按 0.5 中性处理，不奖不罚）")
                .defineInRange("heatingWeight", 35.0, 0.0, 100.0);
        HAPPINESS_FOOD_WEIGHT = b.comment("食物充足率权重")
                .defineInRange("foodWeight", 25.0, 0.0, 100.0);
        HAPPINESS_HOUSING_WEIGHT = b.comment("住房人均水平权重")
                .defineInRange("housingWeight", 15.0, 0.0, 100.0);
        b.pop();

        b.push("territory");
        TERRITORY_MAX_CHUNKS = b.comment("领地区块数量上限（防一人圈全服）")
                .defineInRange("maxChunks", 256, 9, 100_000);
        CHUNK_PRICE_BASE = b.comment("购地基础价：第 N 块价格 = 基础价 × N（N=含免费块的总持有数+1，待项目主调）")
                .defineInRange("chunkPriceBase", 20, 0, 1_000_000);
        PURCHASE_COOLDOWN_SECONDS = b.comment("购地冷却（秒，防手滑连买）")
                .defineInRange("purchaseCooldownSeconds", 30, 0, 3600);
        ABANDON_REFUND_PERCENT = b.comment("退地退款比例（% 实付购地费）")
                .defineInRange("abandonRefundPercent", 50, 0, 100);
        ALLOW_ENCLAVE = b.comment("是否允许购买不相邻的飞地（定稿默认禁止）")
                .define("allowEnclave", false);
        b.pop();

        b.push("construction");
        CONSTRUCTION_FEE_ENABLED = b.comment("建造/升级收取一次性信用点建设费（定稿默认开启）")
                .define("feeEnabled", true);
        CONSTRUCTION_FEE_BASE = b.comment("一次性建设费 = 基础价 × 建筑等级")
                .defineInRange("feeBase", 10, 0, 1_000_000);
        DISMANTLE_SECONDS = b.comment("拆除倒计时（秒）")
                .defineInRange("dismantleSeconds", 60, 1, 3600);
        DISMANTLE_REFUND_PERCENT = b.comment("拆除返还材料比例（% 建造材料；建设费不退）")
                .defineInRange("dismantleRefundPercent", 50, 0, 100);
        b.pop();

        b.push("maintenance");
        MAINTENANCE_HALF_EFFICIENCY_FACTOR = b.comment("维护断供时建筑效率倍率（定稿：效率减半）")
                .defineInRange("halfEfficiencyFactor", 0.5, 0.0, 1.0);
        b.pop();

        b.push("research");
        RESEARCH_STUB_ENABLED = b.comment("研究桩开关（开发联调用）：[研究台] pioneer_research 在位时自动失效；关闭且无 [研究台] 时研究页隐藏（06 §5 软依赖）")
                .define("stubEnabled", true);
        RESEARCH_ORG_FEE = b.comment("组织化研究一次性信用点组织费（定稿可配）")
                .defineInRange("orgFee", 20, 0, 1_000_000);
        RESEARCH_SPEED_PER_SCIENTIST = b.comment("每在岗科研员研究速度加成（定稿 +15%）")
                .defineInRange("speedPerScientist", 0.15, 0.0, 10.0);
        RESEARCH_SPEED_CAP = b.comment("研究速度加成上限（定稿 +60%）")
                .defineInRange("speedCap", 0.6, 0.0, 10.0);
        b.pop();

        b.push("raid");
        THREAT_COEF_BUILDING = b.comment("威胁值：建筑总等级 × 此系数（再乘建筑权重，默认 50%——避免多建多挨打）")
                .defineInRange("threatCoefBuilding", 2.0, 0.0, 1000.0);
        THREAT_BUILDING_WEIGHT = b.comment("威胁值：建筑项权重（定稿 50%）")
                .defineInRange("threatBuildingWeight", 0.5, 0.0, 1.0);
        THREAT_COEF_BUFFER_VALUE = b.comment("威胁值：缓冲物资价值（按市场基准价折算）× 此系数")
                .defineInRange("threatCoefBufferValue", 0.05, 0.0, 10.0);
        THREAT_COEF_POPULATION = b.comment("威胁值：人口 × 此系数")
                .defineInRange("threatCoefPopulation", 5.0, 0.0, 1000.0);
        THREAT_COEF_CREDITS = b.comment("威胁值：金库 × 此系数（最低权重）")
                .defineInRange("threatCoefCredits", 0.02, 0.0, 10.0);
        THREAT_CONVERGENCE = b.comment("威胁值向财富推导值收敛速率（每经济 tick 比例；胜利 -30% 后自然回升）")
                .defineInRange("threatConvergence", 0.1, 0.0, 1.0);
        RAID_TIER_THRESHOLDS = b.comment("袭击档位阈值（威胁值达到即触发倒计时），格式「档位:阈值」逗号分隔")
                .defineList("raidTierThresholds",
                        List.of("1:100", "2:250", "3:500", "4:800", "5:1200"),
                        o -> o instanceof String s && s.contains(":"));
        RAID_COUNTDOWN_SECONDS = b.comment("袭击倒计时（秒）+ 警报与方向预警")
                .defineInRange("raidCountdownSeconds", 600, 30, 86400);
        RAID_POSTPONE_SECONDS = b.comment("无人时推迟时长（秒，零成本重排队）")
                .defineInRange("raidPostponeSeconds", 60, 10, 86400);
        RAID_PLAYER_RANGE = b.comment("开打条件：总部 N 格内有在线玩家才生成袭击波（定稿 64）")
                .defineInRange("raidPlayerRange", 64, 8, 512);
        RAID_MOB_CAP = b.comment("袭击怪实体上限（每殖民地，可配）")
                .defineInRange("raidMobCap", 20, 1, 200);
        RAID_TIMEOUT_SECONDS = b.comment("袭击怪存活超时自动撤退 = 防守成功（定稿 5 分钟）")
                .defineInRange("raidTimeoutSeconds", 300, 30, 86400);
        VICTORY_THREAT_REDUCTION_PERCENT = b.comment("胜利后威胁值削减百分比（定稿 30%，随财富自然回升）")
                .defineInRange("victoryThreatReductionPercent", 30, 0, 100);
        CITIZEN_BASE_DAMAGE = b.comment("市民参战基础攻击力（守卫 100%，其他 25%）")
                .defineInRange("citizenBaseDamage", 4.0, 0.0, 100.0);
        CITIZEN_GUARD_MULTIPLIER = b.comment("守卫战力倍率（定稿 100%，受研究加成）")
                .defineInRange("citizenGuardMultiplier", 1.0, 0.0, 10.0);
        CITIZEN_WORKER_MULTIPLIER = b.comment("非守卫市民战力倍率（定稿 25%）")
                .defineInRange("citizenWorkerMultiplier", 0.25, 0.0, 10.0);
        DAMAGED_EFFICIENCY_FACTOR = b.comment("建筑「受损」状态效率倍率（定稿 50%，需维修）")
                .defineInRange("damagedEfficiencyFactor", 0.5, 0.0, 1.0);
        REPAIR_BUILD_SECONDS_FACTOR_PERCENT = b.comment("维修倒计时 = 施工时长 × 此百分比")
                .defineInRange("repairBuildSecondsFactorPercent", 50, 5, 100);
        REPAIR_COST_PERCENT = b.comment("维修材料费用 = 该建筑当前等级建造成本 × 此百分比（另收一次性建设费）")
                .defineInRange("repairCostPercent", 50, 0, 100);
        HQ_MAX_HEALTH = b.comment("总部耐久上限（打空 → 停摆待修复，原地修复恢复）")
                .defineInRange("hqMaxHealth", 200.0, 1.0, 100000.0);
        RAID_BUILDING_DAMAGE_CHANCE = b.comment("袭击期间每 30 秒随机一座建筑受损的概率")
                .defineInRange("buildingDamageChance", 0.15, 0.0, 1.0);
        b.pop();

        b.push("debug");
        DEBUG_LOG_ECONOMY_TICK = b.comment("每次经济 tick 向日志输出主线程耗时（验收证据）")
                .define("logEconomyTick", false);
        b.pop();

        SPEC = b.build();
    }

    private Config() {
    }
}
