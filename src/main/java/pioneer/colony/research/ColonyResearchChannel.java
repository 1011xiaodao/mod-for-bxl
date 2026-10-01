package pioneer.colony.research;

import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import pioneer.colony.PioneerColony;
import pioneer.colony.colony.BuildingDefinitions;
import pioneer.colony.colony.BuildingInstance;
import pioneer.colony.colony.Colony;
import pioneer.colony.colony.ColonySavedData;
import pioneer.colony.config.Config;

/**
 * colony 通道实现（06 §5 定稿）：
 * 并行槽位随市政厅等级 1~5 → 1/2/2/3/3；速度乘数 = 1 + min(上限, 每科研员加成 × 在岗科研员数)；
 * onCompleted 解释 colony 效果（产量 global_bonus / 市民上限 citizen_cap_bonus；守卫战力 M6.4 挂钩）。
 * 红线：锁整包配方的研究全部走 common 通道（科研终端单人可完成），本通道不参与门禁。
 */
public final class ColonyResearchChannel implements pioneer.colony.research.ChannelContract {
    public static final String ID = "colony";
    public static final ColonyResearchChannel INSTANCE = new ColonyResearchChannel();

    private ColonyResearchChannel() {
    }

    @Override
    public String id() {
        return ID;
    }

    /** 市政厅等级 → 并行槽位。 */
    public int maxParallel(MinecraftServer server, UUID player) {
        Colony colony = ColonySavedData.get(server).colonyOf(player);
        if (colony == null) {
            return 0;
        }
        int hqTier = colony.getBuildings().stream()
                .filter(b -> "hq".equals(b.definitionId()))
                .mapToInt(BuildingInstance::tier)
                .findFirst().orElse(1);
        return switch (Math.max(1, Math.min(5, hqTier))) {
            case 1 -> 1;
            case 2, 3 -> 2;
            default -> 3;
        };
    }

    /** 速度乘数 = 1 + min(cap, per × 在岗科研员数)（研究院在岗 = 岗位填充率 × 槽位）。 */
    public double speedMultiplier(MinecraftServer server, UUID player) {
        Colony colony = ColonySavedData.get(server).colonyOf(player);
        if (colony == null) {
            return 1.0;
        }
        double scientists = 0;
        for (BuildingInstance b : colony.getBuildings()) {
            if ("research_institute".equals(b.definitionId()) && b.status() == BuildingInstance.Status.ACTIVE) {
                scientists += BuildingDefinitions.get(b.definitionId())
                        .map(d -> d.tierWorkerSlots(b.tier()) * b.getLastFillRate())
                        .orElse(0.0);
            }
        }
        double bonus = Math.min(Config.RESEARCH_SPEED_CAP.get(),
                Config.RESEARCH_SPEED_PER_SCIENTIST.get() * scientists);
        return 1.0 + bonus;
    }

    /** 效果实现：colony 内部加成（06 §5：colony_only 效果只允许殖民地内部加成）。 */
    public void onCompleted(MinecraftServer server, UUID player, String researchId, Map<String, Object> effects) {
        Colony colony = ColonySavedData.get(server).colonyOf(player);
        if (colony == null) {
            return;
        }
        Object globalBonus = effects.get("global_bonus");
        if (globalBonus instanceof Map<?, ?> bonus) {
            if ("production".equals(String.valueOf(bonus.get("type")))) {
                Object tagObj = bonus.get("building_tag");
                String tag = tagObj == null ? "" : String.valueOf(tagObj);
                double percent = toDouble(bonus.get("percent"));
                colony.addResearchProductionBonus(tag, percent);
                log(researchId, colony, String.format("产量加成 +%s%%（标签 %s）", percent, tag.isEmpty() ? "全部" : tag));
            }
        }
        Object citizenCap = effects.get("citizen_cap_bonus");
        if (citizenCap instanceof Number n) {
            colony.addResearchCitizenCapBonus(n.intValue());
            log(researchId, colony, "市民上限 +" + n.intValue());
        }
        Object guardPower = effects.get("guard_power_bonus");
        if (guardPower instanceof Number n) {
            // M6.4 袭击系统挂钩位：守卫战力加成登记（占位存储，M6.4 启用）
            colony.addResearchProductionBonus("__guard_power", toDouble(n));
            log(researchId, colony, "守卫战力加成已登记（M6.4 生效）");
        }
        ColonySavedData.get(server).setDirty();
    }

    private static void log(String researchId, Colony colony, String effect) {
        PioneerColony.LOGGER.info("[殖民地经营] 研究完成[{}]：殖民地[{}] {}",
                researchId, colony.getName(), effect);
    }

    private static double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
