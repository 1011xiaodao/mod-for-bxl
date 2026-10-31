package pioneer.colony.market;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import pioneer.colony.PioneerColony;
import pioneer.colony.colony.BuildingInstance;
import pioneer.colony.colony.Colony;
import pioneer.colony.colony.ColonySavedData;
import pioneer.colony.config.Config;

/**
 * 星联市场（M6.7）：服务端持久订单簿、价格/时间优先、部分成交、预托管、过期退还和动态行情。
 * 交易只修改殖民地纯数据，不接触玩家背包，也不依赖区块加载或在线状态。
 */
public final class MarketSystem {
    private static final long GAME_DAY_MILLIS = 20L * 60L * 1000L;
    private static final ExecutorService AUDIT_IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pioneer-colony-market-audit");
        thread.setDaemon(true);
        return thread;
    });

    private MarketSystem() {
    }

    public record Result(boolean success, String message, long orderId) {
        public static Result ok(String message, long orderId) { return new Result(true, message, orderId); }
        public static Result fail(String message) { return new Result(false, message, -1L); }
    }

    public static long currentPrice(MinecraftServer server, String item) {
        if (!MarketPrices.isListed(item) || MarketPrices.basePrice(item) <= 0.0
                || MarketPrices.maxPrice(item) < 1.0) return 0L;
        MarketSavedData data = MarketSavedData.get(server);
        double value = MarketPrices.price(item, 0.0, data.score(item));
        if (!Double.isFinite(value)) return 0L;
        long minimum = Math.max(1L, (long) Math.ceil(MarketPrices.minPrice(item)));
        long maximum = (long) Math.floor(MarketPrices.maxPrice(item));
        if (maximum < minimum) return 0L;
        long rounded = Math.max(1L, Math.round(value));
        return Math.max(minimum, Math.min(maximum, rounded));
    }

    public static Result placeOrder(MinecraftServer server, Colony colony, ServerPlayer player,
                                    MarketSavedData.Side side, String item, long quantity,
                                    long limitPrice, int durationDays) {
        if (colony == null || player == null) return Result.fail("市场操作需要玩家和殖民地。");
        if (!colony.isOwner(player.getUUID())) return Result.fail("只有殖民地总督可以挂单。");
        if (colony.getHqState() != Colony.HqState.NORMAL) return Result.fail("总部停摆，市场暂不可用。");
        if (colony.getBuildings().stream().noneMatch(b -> "trade_station".equals(b.definitionId())
                && b.status() == BuildingInstance.Status.ACTIVE)) {
            return Result.fail("需要先建成并启用贸易站，才能访问星联市场。");
        }
        if (!MarketPrices.isListed(item)) return Result.fail("该物品不在市场白名单中：" + item);
        if (MarketPrices.basePrice(item) <= 0.0 || MarketPrices.maxPrice(item) < 1.0) {
            return Result.fail("该物品基准价格/价格区间不足以进行信用点整数交易。");
        }
        if (quantity < 1 || quantity > Config.MARKET_MAX_ORDER_QUANTITY.get()) {
            return Result.fail("订单数量必须在 1～" + Config.MARKET_MAX_ORDER_QUANTITY.get() + " 之间。");
        }
        if (limitPrice < 0) return Result.fail("价格不能为负数；价格填 0 表示市价单。");
        if (!List.of(1, 3, 7, 14).contains(durationDays)) return Result.fail("挂单时长只支持 1、3、7、14 个游戏日。");
        if (limitPrice > 0 && (limitPrice < MarketPrices.minPrice(item) || limitPrice > MarketPrices.maxPrice(item))) {
            return Result.fail(String.format("限价必须在市场区间 %.2f～%.2f 内。",
                    MarketPrices.minPrice(item), MarketPrices.maxPrice(item)));
        }

        MarketSavedData data = MarketSavedData.get(server);
        long colonyOrders = data.orders().stream().filter(order -> order.colonyId().equals(colony.uuid())).count();
        if (colonyOrders >= Config.MARKET_MAX_ORDERS.get()) {
            return Result.fail("已达每殖民地同时挂单上限 " + Config.MARKET_MAX_ORDERS.get() + "。");
        }

        long now = System.currentTimeMillis();
        long referencePrice = limitPrice > 0 ? limitPrice : currentPrice(server, item);
        if (referencePrice < 1) return Result.fail("该物品当前没有可用市场价格。");
        long reserveUnit = side == MarketSavedData.Side.BUY
                ? (limitPrice > 0 ? limitPrice : Math.max(1L, (long) Math.ceil(MarketPrices.maxPrice(item))))
                : 0L;
        long reserve;
        long fee;
        try {
            reserve = side == MarketSavedData.Side.BUY ? Math.multiplyExact(reserveUnit, quantity) : 0L;
            double feeValue = (double) referencePrice * quantity
                    * Config.MARKET_FEE_PER_GAME_DAY.get() * durationDays;
            if (!Double.isFinite(feeValue) || feeValue > Long.MAX_VALUE) return Result.fail("订单金额超出安全范围。");
            fee = (long) Math.ceil(feeValue);
        } catch (ArithmeticException error) {
            return Result.fail("订单金额超出安全范围。");
        }
        long requiredCredits;
        try {
            requiredCredits = Math.addExact(reserve, fee);
        } catch (ArithmeticException error) {
            return Result.fail("订单金额超出安全范围。");
        }
        if (colony.getCredits() < requiredCredits) {
            return Result.fail("信用点不足：托管 " + reserve + "，手续费 " + fee
                    + "，需要 " + requiredCredits + "，当前 " + colony.getCredits() + "。");
        }
        if (side == MarketSavedData.Side.SELL && colony.countOf(item) < quantity) {
            return Result.fail("殖民地仓库库存不足：" + item + " ×" + quantity
                    + "（现有 " + colony.countOf(item) + "）。");
        }

        if (side == MarketSavedData.Side.SELL && colony.takeItems(item, quantity) != quantity) {
            return Result.fail("物品预托管失败，订单未创建。");
        }
        colony.adjustCredits(-requiredCredits);
        long id = data.nextId();
        long expiresAt = now + durationDays * GAME_DAY_MILLIS;
        MarketSavedData.Order order = new MarketSavedData.Order(id, colony.uuid(), player.getUUID(),
                item, side, quantity, limitPrice, reserveUnit, reserve, now, expiresAt);
        data.addOrder(order);
        data.recordFlow(item, side, quantity);
        ColonySavedData.get(server).setDirty();
        audit(server, "挂单", String.format("order=%d player=%s colony=%s side=%s item=%s qty=%d limit=%d fee=%d",
                id, player.getUUID(), colony.uuid(), side, item, quantity, limitPrice, fee));
        return Result.ok("挂单成功：#" + id + " " + sideName(side) + " " + item + " ×" + quantity
                + (limitPrice == 0 ? " 市价" : " 限价 " + limitPrice)
                + "，托管 " + reserve + " 信用点，手续费 " + fee + "（手续费不退）。", id);
    }

    public static Result cancelOrder(MinecraftServer server, Colony colony, ServerPlayer player, long orderId) {
        if (colony == null || player == null) return Result.fail("市场操作需要玩家和殖民地。");
        MarketSavedData data = MarketSavedData.get(server);
        MarketSavedData.Order order = data.order(orderId);
        if (order == null) return Result.fail("订单不存在或已结束：" + orderId);
        if (!colony.isOwner(player.getUUID()) || !order.colonyId().equals(colony.uuid())) {
            return Result.fail("只能撤销自己殖民地的订单。");
        }
        refundOrder(server, data, order, "主动撤单");
        return Result.ok("订单 #" + orderId + " 已撤销；剩余托管已退回，手续费不退。", orderId);
    }

    public static List<MarketSavedData.Order> ordersForColony(MinecraftServer server, Colony colony) {
        if (colony == null) return List.of();
        return MarketSavedData.get(server).orders().stream()
                .filter(order -> order.colonyId().equals(colony.uuid()))
                .sorted(Comparator.comparingLong(MarketSavedData.Order::createdAt))
                .toList();
    }

    public static List<MarketSavedData.Order> ordersForItem(MinecraftServer server, String item) {
        return MarketSavedData.get(server).orders().stream()
                .filter(order -> order.item().equals(item))
                .sorted(Comparator.comparingLong(MarketSavedData.Order::createdAt))
                .toList();
    }

    /** 每秒调用；撮合与价格更新按独立周期降频，过期/市价单清理有界。 */
    public static void tickServer(MinecraftServer server) {
        MarketSavedData data = MarketSavedData.get(server);
        long now = System.currentTimeMillis();
        data.rollDailyVolume(now);

        long matchInterval = Config.MARKET_MATCH_INTERVAL_SECONDS.get() * 1000L;
        if (data.lastMatch() == 0L) {
            data.setLastMatch(now);
        } else if (now - data.lastMatch() >= matchInterval) {
            matchOrders(server, data, now);
            data.setLastMatch(now);
        }

        expireOrders(server, data, now);

        long priceInterval = Config.MARKET_PRICE_UPDATE_SECONDS.get() * 1000L;
        if (data.lastPriceUpdate() == 0L) {
            data.setLastPriceUpdate(now);
        } else if (now - data.lastPriceUpdate() >= priceInterval) {
            data.updateScores();
            data.setLastPriceUpdate(now);
            PioneerColony.LOGGER.debug("[殖民地经营][市场] 已更新全服行情价格压力。");
        }
    }

    private static void expireOrders(MinecraftServer server, MarketSavedData data, long now) {
        long marketDeadline = Config.MARKET_MATCH_INTERVAL_SECONDS.get() * 1000L;
        List<MarketSavedData.Order> expired = data.orders().stream()
                .filter(order -> now >= order.expiresAt()
                        || (order.limitPrice() == 0L && now - order.createdAt() >= marketDeadline))
                .toList();
        for (MarketSavedData.Order order : expired) {
            refundOrder(server, data, order, now >= order.expiresAt() ? "挂单到期" : "市价单撮合周期结束");
        }
    }

    private static void matchOrders(MinecraftServer server, MarketSavedData data, long now) {
        Set<String> items = new LinkedHashSet<>();
        for (MarketSavedData.Order order : data.orders()) items.add(order.item());
        int matches = 0;
        for (String item : items) {
            while (matches < Config.MARKET_MAX_MATCHES_PER_TICK.get()) {
                Pair pair = findPair(server, data, item, now);
                if (pair == null) break;
                if (!executePair(server, data, pair, now)) break;
                matches++;
            }
            if (matches >= Config.MARKET_MAX_MATCHES_PER_TICK.get()) break;
        }
        if (matches > 0) {
            ColonySavedData.get(server).setDirty();
            PioneerColony.LOGGER.info("[殖民地经营][市场] 本轮完成 {} 笔成交。", matches);
        }
    }

    private record Pair(MarketSavedData.Order buy, MarketSavedData.Order sell) {
    }

    private static Pair findPair(MinecraftServer server, MarketSavedData data, String item, long now) {
        List<MarketSavedData.Order> buys = data.orders().stream()
                .filter(order -> order.item().equals(item) && order.side() == MarketSavedData.Side.BUY)
                .sorted(Comparator.comparingLong((MarketSavedData.Order order) ->
                        order.limitPrice() == 0L ? Long.MAX_VALUE : order.limitPrice()).reversed()
                        .thenComparingLong(MarketSavedData.Order::createdAt))
                .toList();
        List<MarketSavedData.Order> sells = data.orders().stream()
                .filter(order -> order.item().equals(item) && order.side() == MarketSavedData.Side.SELL)
                .sorted(Comparator.comparingLong((MarketSavedData.Order order) ->
                        order.limitPrice() == 0L ? Long.MIN_VALUE : order.limitPrice())
                        .thenComparingLong(MarketSavedData.Order::createdAt))
                .toList();
        long market = currentPrice(server, item);
        for (MarketSavedData.Order buy : buys) {
            Colony buyer = ColonySavedData.get(server).colonyByUuid(buy.colonyId());
            if (buyer == null || buyer.getHqState() != Colony.HqState.NORMAL
                    || buyer.remainingCapacityFor(item) <= 0
                    || data.dailyVolume(buy.colonyId(), now) >= Config.MARKET_DAILY_VOLUME_LIMIT.get()
                        && Config.MARKET_DAILY_VOLUME_LIMIT.get() > 0) continue;
            for (MarketSavedData.Order sell : sells) {
                if (buy.colonyId().equals(sell.colonyId()) || buy.ownerId().equals(sell.ownerId())) continue;
                Colony seller = ColonySavedData.get(server).colonyByUuid(sell.colonyId());
                if (seller == null || seller.getHqState() != Colony.HqState.NORMAL) continue;
                boolean crosses = buy.limitPrice() == 0L || sell.limitPrice() == 0L
                        || buy.limitPrice() >= sell.limitPrice();
                if (!crosses) continue;
                long price = executionPrice(buy, sell, market);
                if (price < 1L || price > buy.reserveUnitPrice()) continue;
                long dailyLimit = Config.MARKET_DAILY_VOLUME_LIMIT.get();
                if (dailyLimit > 0) {
                    long buyRemaining = dailyLimit - data.dailyVolume(buy.colonyId(), now);
                    long sellRemaining = dailyLimit - data.dailyVolume(sell.colonyId(), now);
                    if (Math.min(buyRemaining, sellRemaining) < price) continue;
                }
                if (data.dailyVolume(sell.colonyId(), now) >= Config.MARKET_DAILY_VOLUME_LIMIT.get()
                        && Config.MARKET_DAILY_VOLUME_LIMIT.get() > 0) continue;
                return new Pair(buy, sell);
            }
        }
        return null;
    }

    private static long executionPrice(MarketSavedData.Order buy, MarketSavedData.Order sell, long marketPrice) {
        if (buy.limitPrice() == 0L && sell.limitPrice() == 0L) return marketPrice;
        if (buy.limitPrice() == 0L) return sell.limitPrice();
        if (sell.limitPrice() == 0L) return buy.limitPrice();
        return buy.createdAt() <= sell.createdAt() ? buy.limitPrice() : sell.limitPrice();
    }

    private static boolean executePair(MinecraftServer server, MarketSavedData data, Pair pair, long now) {
        MarketSavedData.Order buy = pair.buy();
        MarketSavedData.Order sell = pair.sell();
        Colony buyer = ColonySavedData.get(server).colonyByUuid(buy.colonyId());
        Colony seller = ColonySavedData.get(server).colonyByUuid(sell.colonyId());
        if (buyer == null || seller == null) return false;
        long price = executionPrice(buy, sell, currentPrice(server, buy.item()));
        long quantity = Math.min(buy.remainingQuantity(), sell.remainingQuantity());
        quantity = Math.min(quantity, buyer.remainingCapacityFor(buy.item()));
        long dailyLimit = Config.MARKET_DAILY_VOLUME_LIMIT.get();
        if (dailyLimit > 0) {
            long remaining = Math.min(dailyLimit - data.dailyVolume(buy.colonyId(), now),
                    dailyLimit - data.dailyVolume(sell.colonyId(), now));
            if (remaining <= 0) return false;
            quantity = Math.min(quantity, remaining / price);
        }
        if (quantity <= 0) return false;
        long reserveSpent;
        long tradeValue;
        try {
            reserveSpent = Math.multiplyExact(buy.reserveUnitPrice(), quantity);
            tradeValue = Math.multiplyExact(price, quantity);
        } catch (ArithmeticException ignored) {
            return false;
        }
        if (tradeValue > reserveSpent || buy.escrowCredits() < reserveSpent) return false;

        long accepted = buyer.insertOutput(buy.item(), quantity);
        if (accepted <= 0) return false;
        if (accepted != quantity) {
            quantity = accepted;
            reserveSpent = buy.reserveUnitPrice() * quantity;
            tradeValue = price * quantity;
        }
        buy.fill(quantity, reserveSpent);
        sell.fill(quantity, 0L);
        data.removeFlow(buy.item(), MarketSavedData.Side.BUY, quantity);
        data.removeFlow(sell.item(), MarketSavedData.Side.SELL, quantity);
        buyer.adjustCredits(reserveSpent - tradeValue);
        seller.adjustCredits(tradeValue);
        data.addDailyVolume(buy.colonyId(), tradeValue, now);
        data.addDailyVolume(sell.colonyId(), tradeValue, now);
        audit(server, "成交", String.format("buyOrder=%d sellOrder=%d buyer=%s seller=%s item=%s qty=%d unitPrice=%d total=%d",
                buy.id(), sell.id(), buy.ownerId(), sell.ownerId(), buy.item(), quantity, price, tradeValue));
        TradeCaravanService.spawn(server, seller, buyer, buy.item(), quantity);
        if (buy.remainingQuantity() <= 0) data.removeOrder(buy.id());
        if (sell.remainingQuantity() <= 0) data.removeOrder(sell.id());
        ColonySavedData.get(server).setDirty();
        return true;
    }

    private static void refundOrder(MinecraftServer server, MarketSavedData data,
                                    MarketSavedData.Order order, String reason) {
        Colony colony = ColonySavedData.get(server).colonyByUuid(order.colonyId());
        if (colony != null) {
            if (order.side() == MarketSavedData.Side.BUY) {
                colony.adjustCredits(order.escrowCredits());
            } else if (order.remainingQuantity() > 0) {
                long accepted = colony.insertOutput(order.item(), order.remainingQuantity());
                long overflow = order.remainingQuantity() - accepted;
                if (overflow > 0) colony.depositPseudoStorage(order.item(), overflow);
            }
            ColonySavedData.get(server).setDirty();
        }
        data.removeFlow(order.item(), order.side(), order.remainingQuantity());
        data.removeOrder(order.id());
        audit(server, reason, String.format("order=%d colony=%s item=%s remaining=%d escrow=%d",
                order.id(), order.colonyId(), order.item(), order.remainingQuantity(), order.escrowCredits()));
    }

    public static void cancelAllForColony(MinecraftServer server, Colony colony) {
        if (colony == null) return;
        MarketSavedData data = MarketSavedData.get(server);
        for (MarketSavedData.Order order : data.orders().stream()
                .filter(value -> value.colonyId().equals(colony.uuid())).toList()) {
            refundOrder(server, data, order, "殖民地解散撤单");
        }
    }

    private static String sideName(MarketSavedData.Side side) {
        return side == MarketSavedData.Side.BUY ? "买入" : "卖出";
    }

    public static void auditExternal(MinecraftServer server, String action, String detail) {
        audit(server, action, detail);
    }

    private static void audit(MinecraftServer server, String action, String detail) {
        String line = Instant.now() + " [" + action + "] " + detail + System.lineSeparator();
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("logs").resolve("pioneer_colony_market.log");
        AUDIT_IO.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND);
            } catch (IOException error) {
                PioneerColony.LOGGER.warn("[殖民地经营][市场] 审计日志写入失败：{}", error.getMessage());
            }
        });
    }

}
