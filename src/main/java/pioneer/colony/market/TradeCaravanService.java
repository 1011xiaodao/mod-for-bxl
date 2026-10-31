package pioneer.colony.market;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import pioneer.colony.PioneerColony;
import pioneer.colony.colony.BuildingInstance;
import pioneer.colony.colony.Colony;

/** v1 商队表现：原版悬浮物品展示实体沿贸易站间直线移动，不影响经济结算。 */
public final class TradeCaravanService {
    private static final long COOLDOWN_MS = 300_000L;
    private static final int MAX_ACTIVE_CARAVANS = 16;
    private static final Map<UUID, Route> ACTIVE = new HashMap<>();
    private static final Map<String, Long> LAST_SPAWN = new HashMap<>();

    private TradeCaravanService() { }

    private record Route(ServerLevel level, ArmorStand display, BlockPos from, BlockPos to,
                         long started, long duration) { }

    public static void spawn(MinecraftServer server, Colony from, Colony to, String item, long quantity) {
        if (server == null || from == null || to == null || from.uuid().equals(to.uuid())) return;
        if (!Level.OVERWORLD.location().toString().equals(from.getDimension())
                || !from.getDimension().equals(to.getDimension())) return;
        long now = System.currentTimeMillis();
        String key = from.uuid() + "->" + to.uuid();
        if (ACTIVE.size() >= MAX_ACTIVE_CARAVANS
                || now - LAST_SPAWN.getOrDefault(key, 0L) < COOLDOWN_MS) return;
        BlockPos source = stationPos(from);
        BlockPos target = stationPos(to);
        ServerLevel level = server.overworld();
        if (source == null || target == null || !level.isLoaded(source) || !level.isLoaded(target)) return;
        double distance = Math.sqrt(source.distToCenterSqr(target.getX() + 0.5,
                target.getY() + 0.5, target.getZ() + 0.5));
        if (!Double.isFinite(distance) || distance < 8.0) return;

        ArmorStand display = EntityType.ARMOR_STAND.create(level);
        if (display == null) return;
        display.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.BARREL));
        display.setInvisible(true);
        display.setPos(source.getX() + 0.5, source.getY() + 2.5, source.getZ() + 0.5);
        display.setNoGravity(true);
        display.setInvulnerable(true);
        display.setSilent(true);
        display.setCustomName(Component.literal("星联商队 · " + quantity + " × " + item));
        display.setCustomNameVisible(true);
        display.addTag("pioneer_colony_caravan");
        if (!level.addFreshEntity(display)) return;
        long duration = Math.max(10_000L, Math.min(120_000L, (long) (distance * 180.0)));
        ACTIVE.put(display.getUUID(), new Route(level, display, source, target, now, duration));
        LAST_SPAWN.put(key, now);
        PioneerColony.LOGGER.debug("[殖民地经营][商队] 视觉商队：{} → {}，{} ×{}", from.getName(), to.getName(), item, quantity);
    }

    public static void tickServer(MinecraftServer server) {
        long now = System.currentTimeMillis();
        Iterator<Route> iterator = ACTIVE.values().iterator();
        while (iterator.hasNext()) {
            Route route = iterator.next();
            ArmorStand display = route.display();
            if (!display.isAlive() || display.isRemoved() || display.level() != route.level()) {
                iterator.remove();
                continue;
            }
            double progress = Math.min(1.0, Math.max(0.0,
                    (now - route.started()) / (double) route.duration()));
            double x = route.from().getX() + 0.5 + (route.to().getX() - route.from().getX()) * progress;
            double y = route.from().getY() + 2.5 + (route.to().getY() - route.from().getY()) * progress;
            double z = route.from().getZ() + 0.5 + (route.to().getZ() - route.from().getZ()) * progress;
            display.setPos(x, y, z);
            if (progress >= 1.0) {
                display.discard();
                iterator.remove();
            }
        }
        LAST_SPAWN.entrySet().removeIf(e -> now - e.getValue() > COOLDOWN_MS * 4);
    }

    public static void onServerStopped() {
        for (Route route : ACTIVE.values()) if (route.display().isAlive()) route.display().discard();
        ACTIVE.clear();
        LAST_SPAWN.clear();
    }

    private static BlockPos stationPos(Colony colony) {
        return colony.getBuildings().stream()
                .filter(b -> "trade_station".equals(b.definitionId())
                        && b.status() == BuildingInstance.Status.ACTIVE && b.hasOrigin())
                .map(b -> BlockPos.of(b.origin())).findFirst().orElse(null);
    }
}
