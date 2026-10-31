package pioneer.colony.market;

import java.util.Comparator;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import pioneer.colony.PioneerColony;
import pioneer.colony.colony.BuildingInstance;
import pioneer.colony.colony.Colony;
import pioneer.colony.colony.ColonyManager;
import pioneer.colony.colony.ColonySavedData;
import pioneer.colony.config.Config;

/**
 * 城市间贸易协定（M6.8）：离线邀约、确认、周期履约、解约通知、路线产量加成和日成交额共用限制。
 * 每次只传递纯数据，不要求加载贸易站所在区块，也不创建城际商队实体。
 */
public final class TradeTreatyManager {
    private static final long GAME_DAY_MILLIS = 20L * 60L * 1000L;

    private TradeTreatyManager() {
    }

    public record Result(boolean success, String message, long treatyId) {
        public static Result ok(String message, long id) { return new Result(true, message, id); }
        public static Result fail(String message) { return new Result(false, message, -1L); }
    }

    public static Result propose(MinecraftServer server, Colony from, ServerPlayer player, Colony to,
                                 String exportItem, long exportPerHour, String returnItem, long returnPerHour) {
        if (server == null || from == null || to == null || player == null) {
            return Result.fail("贸易协定需要两座殖民地和发起玩家。");
        }
        if (!from.isOwner(player.getUUID())) return Result.fail("只有发起方总督可以签署贸易协定。");
        if (from.uuid().equals(to.uuid()) || from.owner().equals(to.owner())) {
            return Result.fail("不能与自己的殖民地签订贸易协定。");
        }
        if (from.getHqState() != Colony.HqState.NORMAL || to.getHqState() != Colony.HqState.NORMAL) {
            return Result.fail("双方总部都必须处于正常状态。");
        }
        if (!hasTradeStation(from) || !hasTradeStation(to)) {
            return Result.fail("双方都必须拥有已建成的贸易站。");
        }
        if (!validItem(exportItem)) return Result.fail("出口物品 ID 无效：" + exportItem);
        if (exportPerHour < 1 || exportPerHour > Config.MARKET_MAX_ORDER_QUANTITY.get()) {
            return Result.fail("每小时出口量必须在 1～" + Config.MARKET_MAX_ORDER_QUANTITY.get() + " 之间。");
        }
        String normalizedReturn = returnItem == null ? "" : returnItem.trim();
        if (returnPerHour < 0 || returnPerHour > Config.MARKET_MAX_ORDER_QUANTITY.get()) {
            return Result.fail("每小时返还量超出配置上限。");
        }
        if (returnPerHour > 0 && !normalizedReturn.equals("credits") && !validItem(normalizedReturn)) {
            return Result.fail("返还物品 ID 无效：" + normalizedReturn);
        }
        if (normalizedReturn.equals(exportItem)) return Result.fail("出口物品与返还物品不能相同。");
        if (returnPerHour == 0) normalizedReturn = "";

        TradeTreatySavedData data = TradeTreatySavedData.get(server);
        long countFrom = data.treaties().stream().filter(t -> involves(t, from.uuid())).count();
        long countTo = data.treaties().stream().filter(t -> involves(t, to.uuid())).count();
        if (countFrom >= Config.TREATY_MAX_PER_COLONY.get() || countTo >= Config.TREATY_MAX_PER_COLONY.get()) {
            return Result.fail("一方已达到贸易协定上限 " + Config.TREATY_MAX_PER_COLONY.get() + " 条。");
        }
        long now = System.currentTimeMillis();
        long id = data.nextId();
        long expires = now + Config.TREATY_INVITE_DAYS.get() * GAME_DAY_MILLIS;
        data.add(new TradeTreatySavedData.Treaty(id, from.uuid(), to.uuid(), from.owner(), to.owner(),
                exportItem, exportPerHour, normalizedReturn, returnPerHour, now, expires));
        ColonySavedData.get(server).setDirty();
        PioneerColony.LOGGER.info("[殖民地经营][贸易协定] 总督 {} 向 {} 发出邀约 #{}：{} {}/小时，返还 {} {}/小时",
                from.owner(), to.owner(), id, exportItem, exportPerHour,
                normalizedReturn.isBlank() ? "无" : normalizedReturn, returnPerHour);
        MarketSystem.auditExternal(server, "协定邀约", "id=" + id + " from=" + from.uuid() + " to="
                + to.uuid() + " export=" + exportItem + " qtyPerHour=" + exportPerHour
                + " return=" + normalizedReturn + " returnPerHour=" + returnPerHour);
        return Result.ok("已发出贸易协定邀约 #" + id + "。对方可在 " + Config.TREATY_INVITE_DAYS.get()
                + " 个游戏日内上线后使用 /colony treaty accept " + id + " 确认。", id);
    }

    public static Result accept(MinecraftServer server, Colony colony, ServerPlayer player, long id) {
        if (colony == null || player == null || !colony.isOwner(player.getUUID())) {
            return Result.fail("只有目标殖民地总督可以确认邀约。");
        }
        TradeTreatySavedData data = TradeTreatySavedData.get(server);
        TradeTreatySavedData.Treaty treaty = data.treaty(id);
        if (treaty == null || treaty.status() != TradeTreatySavedData.Status.INVITED
                || !treaty.toColony().equals(colony.uuid()) || !treaty.toOwner().equals(player.getUUID())) {
            return Result.fail("没有找到发给本殖民地的待确认邀约 #" + id + "。");
        }
        long now = System.currentTimeMillis();
        if (now >= treaty.expiresAt()) {
            data.remove(id);
            return Result.fail("邀约已过期。");
        }
        Colony from = ColonySavedData.get(server).colonyByUuid(treaty.fromColony());
        if (from == null || !hasTradeStation(from) || !hasTradeStation(colony)) {
            data.remove(id);
            return Result.fail("发起方殖民地或贸易站已失效，邀约取消。");
        }
        long countFrom = data.treaties().stream().filter(t -> involves(t, from.uuid())
                && t.status() != TradeTreatySavedData.Status.INVITED).count();
        long countTo = data.treaties().stream().filter(t -> involves(t, colony.uuid())
                && t.status() != TradeTreatySavedData.Status.INVITED).count();
        if (countFrom >= Config.TREATY_MAX_PER_COLONY.get() || countTo >= Config.TREATY_MAX_PER_COLONY.get()) {
            return Result.fail("双方至少一方已达到活跃协定上限。");
        }
        treaty.activate();
        data.setDirty();
        PioneerColony.LOGGER.info("[殖民地经营][贸易协定] 总督 {} 确认协定 #{}", player.getUUID(), id);
        MarketSystem.auditExternal(server, "协定确认", "id=" + id + " owner=" + player.getUUID());
        return Result.ok("已确认贸易协定 #" + id + "，自下个经济周期开始履约。", id);
    }

    public static Result terminate(MinecraftServer server, Colony colony, ServerPlayer player, long id) {
        if (colony == null || player == null || !colony.isOwner(player.getUUID())) {
            return Result.fail("只有殖民地总督可以解约。");
        }
        TradeTreatySavedData data = TradeTreatySavedData.get(server);
        TradeTreatySavedData.Treaty treaty = data.treaty(id);
        if (treaty == null || !involves(treaty, colony.uuid())
                || treaty.status() == TradeTreatySavedData.Status.INVITED) {
            return Result.fail("找不到该殖民地的活跃贸易协定 #" + id + "。");
        }
        if (treaty.status() == TradeTreatySavedData.Status.TERMINATING) {
            return Result.fail("该协定已经进入解约通知期，预计结束时间："
                    + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(treaty.terminationAt())));
        }
        long end = System.currentTimeMillis() + Config.TREATY_TERMINATION_NOTICE_DAYS.get() * GAME_DAY_MILLIS;
        treaty.terminateAt(end);
        data.setDirty();
        MarketSystem.auditExternal(server, "协定解约通知", "id=" + id + " owner=" + player.getUUID()
                + " terminationAt=" + end);
        return Result.ok("已发出解约通知；协定继续履约 " + Config.TREATY_TERMINATION_NOTICE_DAYS.get()
                + " 个游戏日，之后自动终止。", id);
    }

    public static List<TradeTreatySavedData.Treaty> treatiesFor(MinecraftServer server, Colony colony) {
        if (colony == null) return List.of();
        return TradeTreatySavedData.get(server).treaties().stream()
                .filter(t -> involves(t, colony.uuid()))
                .sorted(Comparator.comparingLong(TradeTreatySavedData.Treaty::id)).toList();
    }

    /** 每经济周期调用一次；使用实际经过时间处理停服期间的履约，最长受离线补算上限约束。 */
    public static void tickServer(MinecraftServer server) {
        TradeTreatySavedData data = TradeTreatySavedData.get(server);
        long now = System.currentTimeMillis();
        if (data.lastTickWallTime() == 0L) {
            data.setLastTickWallTime(now);
            return;
        }
        long elapsed = now - data.lastTickWallTime();
        if (elapsed < 60_000L) return;
        long minutes = Math.min(elapsed / 60_000L, Config.OFFLINE_CATCHUP_CAP_HOURS.get() * 60L);
        data.setLastTickWallTime(now);
        long marketDayLimit = Config.MARKET_DAILY_VOLUME_LIMIT.get();

        for (TradeTreatySavedData.Treaty treaty : data.treaties()) {
            if (treaty.exportPerHour() <= 0 || treaty.exportItem().isBlank()) {
                data.remove(treaty.id());
                MarketSystem.auditExternal(server, "无效协定清理", "id=" + treaty.id());
                continue;
            }
            if (treaty.status() == TradeTreatySavedData.Status.INVITED) {
                if (now >= treaty.expiresAt()) {
                    data.remove(treaty.id());
                    MarketSystem.auditExternal(server, "协定邀约过期", "id=" + treaty.id());
                }
                continue;
            }
            if (treaty.status() == TradeTreatySavedData.Status.TERMINATING && now >= treaty.terminationAt()) {
                data.remove(treaty.id());
                MarketSystem.auditExternal(server, "协定正式终止", "id=" + treaty.id());
                continue;
            }
            Colony from = ColonySavedData.get(server).colonyByUuid(treaty.fromColony());
            Colony to = ColonySavedData.get(server).colonyByUuid(treaty.toColony());
            if (from == null || to == null) {
                data.remove(treaty.id());
                continue;
            }
            if (from.getHqState() != Colony.HqState.NORMAL || to.getHqState() != Colony.HqState.NORMAL
                    || !hasTradeStation(from) || !hasTradeStation(to)) continue;

            double expectedExport = treaty.exportPerHour() * (minutes / 60.0) + treaty.exportCarry();
            long due = expectedExport >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.floor(expectedExport);
            double exportCarry = expectedExport - Math.floor(expectedExport);
            if (due <= 0) {
                treaty.setCarries(exportCarry, treaty.returnCarry());
                continue;
            }

            long allowedByDailyLimit = Long.MAX_VALUE;
            if (marketDayLimit > 0) {
                MarketSavedData market = MarketSavedData.get(server);
                long used = Math.max(market.dailyVolume(from.uuid(), now), market.dailyVolume(to.uuid(), now));
                long remaining = Math.max(0L, marketDayLimit - used);
                long valuePerExport = valuePerExportUnit(treaty);
                allowedByDailyLimit = valuePerExport <= 0 ? due : remaining / valuePerExport;
            }
            long actual = Math.min(due, allowedByDailyLimit);
            actual = Math.min(actual, from.countOf(treaty.exportItem()));
            actual = Math.min(actual, to.remainingCapacityFor(treaty.exportItem()));

            double returnCarry = treaty.returnCarry();
            if (actual > 0 && treaty.returnPerHour() > 0) {
                double ratio = treaty.returnPerHour() / (double) treaty.exportPerHour();
                if ("credits".equals(treaty.returnItem())) {
                    actual = Math.min(actual, (long) Math.floor(to.getCredits() / ratio));
                } else {
                    actual = Math.min(actual, (long) Math.floor(to.countOf(treaty.returnItem()) / ratio));
                    actual = Math.min(actual, (long) Math.floor(from.remainingCapacityFor(treaty.returnItem()) / ratio));
                }
            }
            if (actual <= 0) {
                treaty.setCarries(exportCarry, returnCarry);
                continue;
            }

            long accepted = to.insertOutput(treaty.exportItem(), actual);
            long exported = from.takeItems(treaty.exportItem(), accepted);
            if (exported < accepted) {
                long rollback = accepted - exported;
                to.takeItems(treaty.exportItem(), rollback);
            }
            actual = exported;
            if (actual <= 0) {
                treaty.setCarries(exportCarry, returnCarry);
                continue;
            }

            long returnAmount = 0L;
            if (treaty.returnPerHour() > 0) {
                double expectedReturn = actual * (treaty.returnPerHour() / (double) treaty.exportPerHour()) + returnCarry;
                returnAmount = expectedReturn >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.floor(expectedReturn);
                returnCarry = expectedReturn - Math.floor(expectedReturn);
                if ("credits".equals(treaty.returnItem())) {
                    returnAmount = Math.min(returnAmount, to.getCredits());
                    to.adjustCredits(-returnAmount);
                    from.adjustCredits(returnAmount);
                } else if (returnAmount > 0) {
                    long moved = from.insertOutput(treaty.returnItem(), returnAmount);
                    long removed = to.takeItems(treaty.returnItem(), moved);
                    if (removed < moved) {
                        long rollback = moved - removed;
                        from.takeItems(treaty.returnItem(), rollback);
                    }
                    returnAmount = removed;
                }
            }
            treaty.setCarries(exportCarry, returnCarry);
            data.setDirty();
            ColonySavedData.get(server).setDirty();

            long tradeValue = marketValue(treaty.exportItem(), actual);
            if (treaty.returnPerHour() > 0 && !"credits".equals(treaty.returnItem())) {
                tradeValue = safeAdd(tradeValue, marketValue(treaty.returnItem(), returnAmount));
            } else if ("credits".equals(treaty.returnItem())) {
                tradeValue = safeAdd(tradeValue, returnAmount);
            }
            if (tradeValue > 0) {
                MarketSavedData market = MarketSavedData.get(server);
                market.addDailyVolume(from.uuid(), tradeValue, now);
                market.addDailyVolume(to.uuid(), tradeValue, now);
            }
            TradeCaravanService.spawn(server, from, to, treaty.exportItem(), actual);
            MarketSystem.auditExternal(server, "协定履约", "id=" + treaty.id() + " from=" + from.uuid()
                    + " to=" + to.uuid() + " export=" + treaty.exportItem() + " qty=" + actual
                    + " return=" + treaty.returnItem() + " returnQty=" + returnAmount);
            PioneerColony.LOGGER.info("[殖民地经营][城际贸易] 协定 #{}：{} → {}，{} ×{}，返还 {} {}",
                    treaty.id(), from.getName(), to.getName(), treaty.exportItem(), actual,
                    treaty.returnItem().isBlank() ? "无" : treaty.returnItem(), returnAmount);
        }
    }

    /** 路线商品加成：双方的出口/返还商品都能受路线加成。 */
    public static double productionBonusFor(MinecraftServer server, java.util.UUID colonyId, String item) {
        if (server == null || item == null) return 0.0;
        long routes = TradeTreatySavedData.get(server).treaties().stream()
                .filter(t -> t.status() != TradeTreatySavedData.Status.INVITED && involves(t, colonyId))
                .filter(t -> t.exportItem().equals(item)
                        || (t.returnPerHour() > 0 && t.returnItem().equals(item) && !"credits".equals(item)))
                .count();
        return Math.min(1.0, routes * Config.TRADE_ROUTE_PRODUCTION_BONUS_PER_TREATY.get());
    }

    public static void removeForColony(MinecraftServer server, Colony colony) {
        if (colony == null) return;
        TradeTreatySavedData data = TradeTreatySavedData.get(server);
        for (TradeTreatySavedData.Treaty treaty : data.treaties().stream()
                .filter(value -> involves(value, colony.uuid())).toList()) {
            data.remove(treaty.id());
            MarketSystem.auditExternal(server, "殖民地解散移除协定", "id=" + treaty.id() + " colony=" + colony.uuid());
        }
    }

    private static boolean hasTradeStation(Colony colony) {
        return colony.getBuildings().stream().anyMatch(building -> "trade_station".equals(building.definitionId())
                && building.status() == BuildingInstance.Status.ACTIVE);
    }

    private static boolean involves(TradeTreatySavedData.Treaty treaty, java.util.UUID colonyId) {
        return treaty.fromColony().equals(colonyId) || treaty.toColony().equals(colonyId);
    }

    private static boolean validItem(String item) {
        if (item == null || item.isBlank()) return false;
        try {
            return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(item)).isPresent();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static long valuePerExportUnit(TradeTreatySavedData.Treaty treaty) {
        double value = MarketPrices.basePrice(treaty.exportItem());
        if (treaty.returnPerHour() > 0) {
            if ("credits".equals(treaty.returnItem())) {
                value += treaty.returnPerHour() / (double) treaty.exportPerHour();
            } else {
                value += MarketPrices.basePrice(treaty.returnItem())
                        * treaty.returnPerHour() / (double) treaty.exportPerHour();
            }
        }
        return !Double.isFinite(value) || value <= 0 ? 1L : Math.max(1L, (long) Math.ceil(value));
    }

    private static long marketValue(String item, long quantity) {
        if (quantity <= 0) return 0L;
        double base = MarketPrices.basePrice(item);
        if (!Double.isFinite(base) || base <= 0.0) base = 1.0;
        double value = base * quantity;
        return !Double.isFinite(value) || value >= Long.MAX_VALUE
                ? Long.MAX_VALUE : Math.max(1L, (long) Math.ceil(value));
    }

    private static long safeAdd(long a, long b) {
        try { return Math.addExact(a, b); } catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }
}
