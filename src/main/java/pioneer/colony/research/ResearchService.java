package pioneer.colony.research;

import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import pioneer.colony.colony.Colony;
import pioneer.colony.config.Config;
import pioneer.colony.construction.ConstructionService;

/**
 * 组织化研究发起（06 §5 定稿）：市政厅研究页发起 → 材料从殖民地仓库扣（背包兜底，与建造同规则）
 * + 一次性信用点组织费 → 交研究系统启动。GUI 按钮与调试命令共用本入口；启动失败自动全额回滚。
 */
public final class ResearchService {
    private ResearchService() {
    }

    public static ConstructionService.OpResult startOrganized(MinecraftServer server, ServerPlayer player,
                                                              Colony colony, String researchId) {
        ResearchBridge bridge = ResearchIntegration.bridge();
        if (bridge == null) {
            return ConstructionService.OpResult.fail("当前无研究系统（[研究台] 缺席且研究桩未开启）。");
        }
        UUID researcher = player != null ? player.getUUID() : colony.owner();
        ResearchInfo entry = bridge.visibleResearches(server, researcher).stream()
                .filter(r -> r.id().equals(researchId))
                .findFirst().orElse(null);
        if (entry == null) {
            return ConstructionService.OpResult.fail("研究不可见或不存在：" + researchId);
        }
        if (entry.state() != ResearchInfo.State.AVAILABLE) {
            return ConstructionService.OpResult.fail("研究当前不可发起：" + entry.stateText());
        }
        // 仓库扣料（背包兜底）
        ConstructionService.OpResult pay = ConstructionService.payMaterials(colony, player, entry.cost());
        if (!pay.success()) {
            return ConstructionService.OpResult.fail("发起失败：" + pay.message());
        }
        // 组织费
        long fee = Config.RESEARCH_ORG_FEE.get();
        if (colony.getCredits() < fee) {
            rollback(colony, entry.cost(), 0);
            return ConstructionService.OpResult.fail("信用点不足：组织费 " + fee + "（现有 " + colony.getCredits() + "）。");
        }
        colony.adjustCredits(-fee);
        // 启动（失败回滚）
        String error = bridge.startViaColony(server, researcher, researchId);
        if (error != null) {
            rollback(colony, entry.cost(), fee);
            return ConstructionService.OpResult.fail("发起失败：" + error + "（材料与组织费已退回）。");
        }
        double speed = ColonyResearchChannel.INSTANCE.speedMultiplier(server, researcher);
        int slotsMax = ColonyResearchChannel.INSTANCE.maxParallel(server, researcher);
        int slotsUsed = (int) bridge.visibleResearches(server, researcher).stream()
                .filter(r -> r.state() == ResearchInfo.State.ACTIVE).count();
        return ConstructionService.OpResult.ok(String.format(
                "研究「%s」已开始：%d 分钟（加速 ×%.2f，并行 %d/%d，组织费 -%d）。",
                entry.name(), entry.timeMinutes(), speed, slotsUsed, slotsMax, fee));
    }

    private static void rollback(Colony colony, List<pioneer.colony.colony.ItemCost> cost, long fee) {
        for (var c : cost) {
            colony.insertOutput(c.item(), c.count());
        }
        if (fee > 0) {
            colony.adjustCredits(fee);
        }
    }
}
