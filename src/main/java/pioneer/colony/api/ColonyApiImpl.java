package pioneer.colony.api;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import pioneer.colony.colony.BuildingInstance;
import pioneer.colony.colony.Colony;
import pioneer.colony.colony.ColonySavedData;
import pioneer.colony.gauge.GaugeRegistry;
import pioneer.colony.gauge.GaugeState;

/** ColonyApi 的本体实现；句柄不暴露可变 Colony 对象。 */
public final class ColonyApiImpl implements ColonyApi {
    @Override
    public long getMarketPrice(MinecraftServer server, String itemId) {
        return server == null || itemId == null ? 0L
                : pioneer.colony.market.MarketSystem.currentPrice(server, itemId);
    }

    @Override
    public OrderResult submitOrder(MinecraftServer server, net.minecraft.server.level.ServerPlayer player,
                                   pioneer.colony.market.MarketSavedData.Side side, String itemId,
                                   long quantity, long limitPrice, int durationDays) {
        if (server == null || player == null || side == null) return new OrderResult(false, "市场操作参数无效。", -1L);
        Colony colony = ColonySavedData.get(server).colonyOf(player.getUUID());
        var result = pioneer.colony.market.MarketSystem.placeOrder(
                server, colony, player, side, itemId, quantity, limitPrice, durationDays);
        return new OrderResult(result.success(), result.message(), result.orderId());
    }

    @Override
    public void registerResourceType(pioneer.colony.gauge.GaugeDefinition definition) {
        pioneer.colony.gauge.GaugeRegistry.register(definition);
    }

    @Override
    public Optional<ColonyHandle> colonyOf(MinecraftServer server, UUID player) {
        if (server == null || player == null) return Optional.empty();
        Colony colony = ColonySavedData.get(server).colonyOf(player);
        return colony == null ? Optional.empty() : Optional.of(new Handle(server, colony));
    }

    private static final class Handle implements ColonyHandle {
        private final MinecraftServer server;
        private final Colony colony;

        private Handle(MinecraftServer server, Colony colony) {
            this.server = server;
            this.colony = colony;
        }

        private void dirty() {
            ColonySavedData.get(server).setDirty();
        }

        @Override public UUID id() { return colony.uuid(); }
        @Override public UUID owner() { return colony.owner(); }
        @Override public String name() { return colony.getName(); }
        @Override public int population() { return colony.getPopulation(); }
        @Override public long credits() { return colony.getCredits(); }

        @Override
        public boolean spendCredits(long amount) {
            requireMainThread();
            if (amount < 0 || colony.getCredits() < amount) return false;
            colony.adjustCredits(-amount);
            dirty();
            return true;
        }

        @Override
        public void addCredits(long amount) {
            requireMainThread();
            if (amount <= 0) return;
            long next;
            try { next = Math.addExact(colony.getCredits(), amount); }
            catch (ArithmeticException ignored) { next = Long.MAX_VALUE; }
            colony.setCredits(next);
            dirty();
        }

        @Override public double threat() { return colony.getThreatValue(); }

        @Override
        public void addThreat(double amount) {
            requireMainThread();
            if (!Double.isFinite(amount)) return;
            double next = colony.getThreatValue() + amount;
            colony.setThreatValue(Double.isFinite(next) ? Math.max(0.0, next) : Double.MAX_VALUE);
            dirty();
        }

        @Override public Map<String, Long> resourceBuffer() { return colony.bufferSnapshot(); }

        @Override
        public long insertResources(String itemId, long count) {
            requireMainThread();
            if (itemId == null || itemId.isBlank() || count <= 0) return 0;
            long accepted = colony.insertOutput(itemId, count);
            if (accepted > 0) dirty();
            return accepted;
        }

        @Override
        public long extractResources(String itemId, long count) {
            requireMainThread();
            if (itemId == null || itemId.isBlank() || count <= 0) return 0;
            long taken = colony.takeItems(itemId, count);
            if (taken > 0) dirty();
            return taken;
        }

        @Override
        public boolean hasBuilt(String buildingId) {
            return colony.getBuildings().stream().anyMatch(building -> building.definitionId().equals(buildingId)
                    && building.status() == BuildingInstance.Status.ACTIVE);
        }

        @Override
        public double workerFillRate(String buildingId) {
            return colony.getBuildings().stream().filter(building -> building.definitionId().equals(buildingId)
                    && building.status() == BuildingInstance.Status.ACTIVE)
                    .mapToDouble(BuildingInstance::getLastFillRate).findFirst().orElse(0.0);
        }

        @Override
        public double gaugeStored(String gaugeId) {
            requireGauge(gaugeId);
            var state = colony.gaugeSnapshot().get(gaugeId);
            return state == null ? 0.0 : state.stored();
        }

        @Override
        public double gaugeCapacity(String gaugeId) {
            requireGauge(gaugeId);
            var state = colony.gaugeSnapshot().get(gaugeId);
            return state == null ? 0.0 : state.capacity();
        }

        @Override
        public double gaugeSatisfaction(String gaugeId) {
            requireGauge(gaugeId);
            var state = colony.gaugeSnapshot().get(gaugeId);
            return state == null ? 1.0 : state.satisfaction();
        }

        @Override
        public double settleGauge(String gaugeId, double capacity, double productionPerMinute,
                                  double consumptionPerMinute, long minutes) {
            requireMainThread();
            requireGauge(gaugeId);
            double result = colony.gauge(gaugeId).settle(capacity, productionPerMinute, consumptionPerMinute, minutes);
            dirty();
            return result;
        }

        @Override
        public void contributeGauge(String providerId, String gaugeId, double capacity,
                                    double productionPerMinute, double consumptionPerMinute) {
            requireMainThread();
            requireGauge(gaugeId);
            if (!Double.isFinite(capacity) || !Double.isFinite(productionPerMinute)
                    || !Double.isFinite(consumptionPerMinute)
                    || capacity < 0.0 || productionPerMinute < 0.0 || consumptionPerMinute < 0.0) {
                throw new IllegalArgumentException("Gauge contribution 必须是有限非负数");
            }
            colony.gauge(gaugeId).setContribution(providerId, capacity, productionPerMinute, consumptionPerMinute);
        }

        private static void requireGauge(String id) {
            if (GaugeRegistry.get(id).isEmpty()) throw new IllegalArgumentException("未注册 Gauge：" + id);
        }

        private void requireMainThread() {
            if (!server.isSameThread()) {
                throw new IllegalStateException("ColonyApi 写操作必须在服务端主线程执行");
            }
        }
    }
}
