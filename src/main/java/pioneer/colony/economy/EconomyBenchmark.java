package pioneer.colony.economy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import pioneer.colony.colony.BuildingDefinitions;
import pioneer.colony.colony.BuildingInstance;
import pioneer.colony.colony.Colony;

/** OP 可调用的合成经济负载基准；仅创建内存对象，不写入真实殖民地存档。 */
public final class EconomyBenchmark {
    private static final List<String> BUILDINGS = List.of(
            "hq", "farm", "canteen", "residence", "warehouse",
            "boiler_room", "smelter", "trade_station", "research_institute", "guard_post");
    private static final int COLONIES = 10;
    private static final int BUILDINGS_PER_COLONY = 20;
    private static final int ROUNDS = 50;

    private EconomyBenchmark() {
    }

    public record Result(int colonies, int buildingsPerColony, int rounds,
                         double averageMillis, double p95Millis, double maxMillis) {
        public String describe() {
            return String.format(java.util.Locale.ROOT,
                    "合成经济基准完成：%d 座内存殖民地 × %d 栋建筑，%d 轮；单殖民地结算平均 %.3f ms，P95 %.3f ms，最大 %.3f ms。"
                            + "该测试不写真实存档，也不替代真实玩家/区块/TPS 压测。",
                    colonies, buildingsPerColony, rounds, averageMillis, p95Millis, maxMillis);
        }
    }

    public static Result run(MinecraftServer server) {
        if (server == null) throw new IllegalStateException("需要在服务器环境中执行基准命令");
        if (BuildingDefinitions.get("farm").isEmpty() || BuildingDefinitions.get("residence").isEmpty()) {
            throw new IllegalStateException("建筑数据包尚未加载；请在服务器完成资源加载后再运行基准。");
        }
        List<Colony> samples = new ArrayList<>(COLONIES);
        String dimension = Level.OVERWORLD.location().toString();
        for (int i = 0; i < COLONIES; i++) {
            Colony colony = new Colony(UUID.randomUUID(), UUID.randomUUID(), "benchmark-" + i, dimension,
                    i * 256, i * 256);
            colony.setPopulation(10);
            colony.setCredits(5000);
            for (String item : List.of("minecraft:wheat", "minecraft:potato", "minecraft:beef",
                    "minecraft:chicken", "minecraft:coal", "minecraft:bread")) {
                colony.insertOutput(item, 200);
            }
            for (int j = 0; j < BUILDINGS_PER_COLONY; j++) {
                String id = BUILDINGS.get(j % BUILDINGS.size());
                if (BuildingDefinitions.get(id).isEmpty()) {
                    throw new IllegalStateException("基准所需建筑定义缺失：" + id);
                }
                colony.getBuildings().add(new BuildingInstance(id, 1, 0L));
            }
            samples.add(colony);
        }

        long[] nanos = new long[COLONIES * ROUNDS];
        int index = 0;
        for (int round = 0; round < ROUNDS; round++) {
            for (Colony colony : samples) {
                long start = System.nanoTime();
                // null server 明确禁用四季/协定 SavedData 查询，基准只测纯经济与建筑循环，不碰真实世界数据。
                EconomyTicker.runSettlement(null, colony, 1L);
                nanos[index++] = System.nanoTime() - start;
            }
        }
        Arrays.sort(nanos);
        double sum = 0.0;
        for (long sample : nanos) sum += sample;
        double average = sum / nanos.length / 1_000_000.0;
        double p95 = nanos[(int) Math.ceil(nanos.length * 0.95) - 1] / 1_000_000.0;
        double max = nanos[nanos.length - 1] / 1_000_000.0;
        return new Result(COLONIES, BUILDINGS_PER_COLONY, ROUNDS, average, p95, max);
    }
}
