package pioneer.colony.api;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import pioneer.colony.gauge.GaugeDefinition;
import pioneer.colony.market.MarketSavedData;

/**
 * 正式公共 API（06 §9）。附属模组通过此接口访问殖民地账本，不直接依赖内部 SavedData 实现。
 * 所有写操作必须在 Minecraft 服务端主线程执行。
 */
public interface ColonyApi {
    static ColonyApi get() {
        return ColonyApiRegistry.get();
    }

    Optional<ColonyHandle> colonyOf(MinecraftServer server, UUID player);
    long getMarketPrice(MinecraftServer server, String itemId);
    OrderResult submitOrder(MinecraftServer server, ServerPlayer player, MarketSavedData.Side side,
                            String itemId, long quantity, long limitPrice, int durationDays);
    void registerResourceType(GaugeDefinition definition);

    record OrderResult(boolean success, String message, long orderId) { }

    interface ColonyHandle {
        UUID id();
        UUID owner();
        String name();
        int population();
        long credits();
        boolean spendCredits(long amount);
        void addCredits(long amount);
        default long getCredits() { return credits(); }
        default boolean tryWithdrawCredits(long amount) { return spendCredits(amount); }
        default void depositCredits(long amount) { addCredits(amount); }
        double threat();
        void addThreat(double amount);
        Map<String, Long> resourceBuffer();
        default Map<String, Long> getResourceBuffer() { return resourceBuffer(); }
        long insertResources(String itemId, long count);
        long extractResources(String itemId, long count);
        boolean hasBuilt(String buildingId);
        double workerFillRate(String buildingId);
        double gaugeStored(String gaugeId);
        double gaugeCapacity(String gaugeId);
        double gaugeSatisfaction(String gaugeId);
        double settleGauge(String gaugeId, double capacity, double productionPerMinute,
                          double consumptionPerMinute, long minutes);
        /** 设置附属模组对 Gauge 的临时贡献；每个 provider 每 tick 覆盖自己的值，结算后自动消费。 */
        void contributeGauge(String providerId, String gaugeId, double capacity,
                             double productionPerMinute, double consumptionPerMinute);
    }
}
