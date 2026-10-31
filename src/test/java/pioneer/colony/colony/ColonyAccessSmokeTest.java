package pioneer.colony.colony;

import java.util.UUID;

/**
 * 无测试框架的权限矩阵冒烟测试，由 Gradle 的 colonyAccessSmokeTest 调用。
 * 验证总督、访客、可信、未授权玩家的服务端权限边界。
 */
public final class ColonyAccessSmokeTest {
    private ColonyAccessSmokeTest() {
    }

    public static void main(String[] args) {
        UUID owner = UUID.randomUUID();
        UUID visitor = UUID.randomUUID();
        UUID trusted = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();

        Colony colony = new Colony(UUID.randomUUID(), owner, "permission-smoke", "minecraft:overworld", 0, 0);
        colony.setTrust(visitor, Colony.Trust.VISITOR);
        colony.setTrust(trusted, Colony.Trust.TRUSTED);

        require("总督可开建筑 GUI", colony.canOpenGui(owner));
        require("总督可使用贸易站", colony.canUseTradeStation(owner));
        require("总督是可信权限", colony.isTrusted(owner));

        require("访客不能开普通建筑 GUI", !colony.canOpenGui(visitor));
        require("访客可以使用贸易站", colony.canUseTradeStation(visitor));
        require("访客不具有可信权限", !colony.isTrusted(visitor));

        require("可信可以开建筑 GUI", colony.canOpenGui(trusted));
        require("可信可以使用贸易站", colony.canUseTradeStation(trusted));
        require("可信权限正确识别", colony.isTrusted(trusted));

        require("陌生人不能开建筑 GUI", !colony.canOpenGui(stranger));
        require("陌生人不能使用贸易站", !colony.canUseTradeStation(stranger));
        require("陌生人不具有可信权限", !colony.isTrusted(stranger));

        colony.setTrust(visitor, null);
        require("移除访客授权后立即失效", !colony.canUseTradeStation(visitor));

        System.out.println("殖民地权限矩阵冒烟测试通过：总督、访客、可信、陌生人及撤销授权均正常。");
    }

    private static void require(String name, boolean condition) {
        if (!condition) {
            throw new IllegalStateException("权限检查失败：" + name);
        }
    }
}
