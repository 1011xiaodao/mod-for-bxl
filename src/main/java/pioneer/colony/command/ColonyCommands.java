package pioneer.colony.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import pioneer.colony.colony.BuildingDefinition;
import pioneer.colony.colony.BuildingDefinitions;
import pioneer.colony.colony.BuildingInstance;
import pioneer.colony.colony.Colony;
import pioneer.colony.colony.ColonyManager;
import pioneer.colony.colony.ColonySavedData;
import pioneer.colony.construction.ConstructionService;
import pioneer.colony.config.Config;
import pioneer.colony.economy.EconomyTicker;

/**
 * 调试/管理命令（M6.1 服务端冒烟入口）。真实建造（放地基→施工）M6.2 接入后，
 * building add/remove 保留为 OP 调试通道；数值速调见 docs/数值速调手册.md。
 */
public final class ColonyCommands {
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_DEFS = (context, builder) ->
            SharedSuggestionProvider.suggest(BuildingDefinitions.all().keySet(), builder);

    private ColonyCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("colony")
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> create(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("info").executes(ctx -> info(ctx.getSource())))
                .then(Commands.literal("list").requires(s -> s.hasPermission(2))
                        .executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("tick").requires(s -> s.hasPermission(2))
                        .executes(ctx -> forceTick(ctx.getSource())))
                .then(Commands.literal("backdate").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1))
                                .executes(ctx -> backdate(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "minutes")))))
                .then(Commands.literal("credits").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("add")
                                .then(Commands.argument("amount", LongArgumentType.longArg())
                                        .executes(ctx -> credits(ctx.getSource(),
                                                LongArgumentType.getLong(ctx, "amount"), false))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                        .executes(ctx -> credits(ctx.getSource(),
                                                LongArgumentType.getLong(ctx, "amount"), true)))))
                .then(Commands.literal("building").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("add")
                                .then(Commands.argument("def", StringArgumentType.string()).suggests(SUGGEST_DEFS)
                                        .executes(ctx -> buildingAdd(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "def"), 1))
                                        .then(Commands.argument("tier", IntegerArgumentType.integer(1))
                                                .executes(ctx -> buildingAdd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "def"),
                                                        IntegerArgumentType.getInteger(ctx, "tier"))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("def", StringArgumentType.string()).suggests(SUGGEST_DEFS)
                                        .executes(ctx -> buildingRemove(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "def"))))))
                .then(Commands.literal("buffer").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("add")
                                .then(Commands.argument("item", StringArgumentType.string())
                                        .then(Commands.argument("count", LongArgumentType.longArg(1))
                                                .executes(ctx -> bufferAdd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "item"),
                                                        LongArgumentType.getLong(ctx, "count")))))))
                .then(Commands.literal("summary").requires(s -> s.hasPermission(2))
                        .executes(ctx -> showSummary(ctx.getSource())))
                .then(Commands.literal("chunk").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("price")
                                .executes(ctx -> chunkPrice(ctx.getSource())))
                        .then(Commands.literal("buy")
                                .then(Commands.argument("cx", IntegerArgumentType.integer())
                                        .then(Commands.argument("cz", IntegerArgumentType.integer())
                                                .executes(ctx -> chunkBuy(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "cx"),
                                                        IntegerArgumentType.getInteger(ctx, "cz"))))))
                        .then(Commands.literal("abandon")
                                .then(Commands.argument("cx", IntegerArgumentType.integer())
                                        .then(Commands.argument("cz", IntegerArgumentType.integer())
                                                .executes(ctx -> chunkAbandon(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "cx"),
                                                        IntegerArgumentType.getInteger(ctx, "cz")))))))
                .then(Commands.literal("trust").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", StringArgumentType.string())
                                        .executes(ctx -> trust(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"), true))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", StringArgumentType.string())
                                        .executes(ctx -> trust(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"), false))))
                        .then(Commands.literal("list")
                                .executes(ctx -> trustList(ctx.getSource()))))
                .then(Commands.literal("build").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("def", StringArgumentType.string()).suggests(SUGGEST_DEFS)
                                .executes(ctx -> build(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "def"), 1, false))
                                .then(Commands.argument("tier", IntegerArgumentType.integer(1))
                                        .executes(ctx -> build(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "def"),
                                                IntegerArgumentType.getInteger(ctx, "tier"), false))
                                        .then(Commands.argument("free", BoolArgumentType.bool())
                                                .executes(ctx -> build(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "def"),
                                                        IntegerArgumentType.getInteger(ctx, "tier"),
                                                        BoolArgumentType.getBool(ctx, "free")))))))
                .then(Commands.literal("upgrade").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("def", StringArgumentType.string()).suggests(SUGGEST_DEFS)
                                .executes(ctx -> upgrade(ctx.getSource(), StringArgumentType.getString(ctx, "def")))))
                .then(Commands.literal("dismantle").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("def", StringArgumentType.string()).suggests(SUGGEST_DEFS)
                                .executes(ctx -> dismantle(ctx.getSource(), StringArgumentType.getString(ctx, "def")))))
                .then(Commands.literal("finishbuild").requires(s -> s.hasPermission(2))
                        .executes(ctx -> finishBuild(ctx.getSource())))
                .then(Commands.literal("debugorigin").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("def", StringArgumentType.string()).suggests(SUGGEST_DEFS)
                                .executes(ctx -> debugOrigin(ctx.getSource(), StringArgumentType.getString(ctx, "def")))))
                .then(Commands.literal("dissolve").requires(s -> s.hasPermission(2))
                        .executes(ctx -> dissolve(ctx.getSource())))
                .then(Commands.literal("withdraw").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("item", StringArgumentType.string())
                                .executes(ctx -> withdraw(ctx.getSource(), StringArgumentType.getString(ctx, "item"), Long.MAX_VALUE))
                                .then(Commands.argument("count", LongArgumentType.longArg(1))
                                        .executes(ctx -> withdraw(ctx.getSource(), StringArgumentType.getString(ctx, "item"),
                                                LongArgumentType.getLong(ctx, "count"))))));
        dispatcher.register(root);
    }

    // —— create ——

    private static int create(CommandSourceStack source, String name) {
        MinecraftServer server = source.getServer();
        ServerLevel level = source.getLevel();
        if (level.dimension() != Level.OVERWORLD) {
            source.sendFailure(Component.literal("创建失败：环境不适合殖民（殖民地仅限主世界）。"));
            return 0;
        }
        UUID owner;
        int x;
        int z;
        if (source.getEntity() instanceof ServerPlayer player) {
            owner = player.getUUID();
            x = player.getBlockX();
            z = player.getBlockZ();
        } else {
            owner = ColonyManager.DEBUG_OWNER;
            var spawn = level.getSharedSpawnPos();
            x = spawn.getX();
            z = spawn.getZ();
        }
        Colony colony = ColonyManager.createColony(server, owner, name, level, x, z);
        if (colony == null) {
            source.sendFailure(Component.literal("创建失败：已达殖民地数量上限（"
                    + Config.COLONIES_PER_PLAYER.get() + "）。"));
            return 0;
        }
        long finalCredits = colony.getCredits();
        source.sendSuccess(() -> Component.literal(String.format(
                "殖民地「%s」创建成功！获赠启动资金 %d 信用点，初始人口 %d，已免费认领总部周围 3×3 领地。",
                name, finalCredits, colony.getPopulation())), true);
        return 1;
    }

    // —— info ——

    private static int info(CommandSourceStack source) {
        Colony colony = resolveColony(source);
        if (colony == null) {
            source.sendFailure(Component.literal("没有可查看的殖民地（玩家：/colony create <名称>）。"));
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== 殖民地「").append(colony.getName()).append("」 ===\n");
        sb.append(String.format("维度：%s  领地：%d 区块  状态：%s\n",
                colony.getDimension(), colony.territorySize(),
                colony.getHqState() == Colony.HqState.NORMAL ? "正常" : "停摆待修复"));
        double foodDays = EconomyTicker.foodDaysRemaining(colony);
        sb.append(String.format("人口：%d/%d（床位）  信用点：%d  幸福度：%.0f（增速 ×%.2f）\n",
                colony.getPopulation(), residentSlots(colony), colony.getCredits(),
                colony.getHappiness(), 0.5 + colony.getHappiness() / 100.0));
        sb.append(String.format("食物储备：可维持 %s 天  威胁值：%.0f（M6.4 启用）\n",
                foodDays < 0 ? "∞" : String.format("%.1f", foodDays), colony.getThreatValue()));

        sb.append("建筑 ").append(colony.getBuildings().size()).append(" 栋：\n");
        for (BuildingInstance b : colony.getBuildings()) {
            BuildingDefinition def = BuildingDefinitions.get(b.definitionId()).orElse(null);
            String name = def != null ? def.name() : b.definitionId();
            sb.append(String.format(" · %s Lv.%d [%s] 岗位填充 %.0f%%%s%s\n",
                    name, b.tier(), statusName(b.status()), b.getLastFillRate() * 100,
                    b.isMaintenanceSatisfied() ? "" : " （维护断供）",
                    b.isStorageFull() ? " （仓库已满）" : ""));
        }
        sb.append("缓冲（").append(colony.bufferSnapshot().size()).append('/')
                .append(colony.bufferTypeCapacity()).append(" 类）：");
        if (colony.bufferSnapshot().isEmpty()) {
            sb.append("空");
        } else {
            int i = 0;
            for (Map.Entry<String, Long> e : colony.bufferSnapshot().entrySet()) {
                if (i++ > 0) {
                    sb.append("、");
                }
                sb.append(e.getKey()).append("×").append(e.getValue());
            }
        }
        sb.append('\n');
        sb.append("最后结算：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date(colony.getLastTickWallTime())));
        source.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static String statusName(BuildingInstance.Status status) {
        return switch (status) {
            case CONSTRUCTION -> "施工中";
            case ACTIVE -> "运转中";
            case DAMAGED -> "受损";
            case UNDER_REPAIR -> "维修中";
            case DISMANTLING -> "拆除中";
        };
    }

    private static int residentSlots(Colony colony) {
        int slots = 0;
        for (BuildingInstance b : colony.getBuildings()) {
            if (b.status() == BuildingInstance.Status.ACTIVE) {
                slots += BuildingDefinitions.get(b.definitionId())
                        .map(d -> d.tierResidentSlots(b.tier())).orElse(0);
            }
        }
        return slots;
    }

    // —— list ——

    private static int list(CommandSourceStack source) {
        var all = ColonySavedData.get(source.getServer()).all();
        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.literal("当前存档没有殖民地。"), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("=== 殖民地列表（").append(all.size()).append("） ===\n");
        for (Colony c : all) {
            sb.append(String.format(" · 「%s」 owner=%s pop=%d credits=%d buildings=%d territory=%d\n",
                    c.getName(), c.owner(), c.getPopulation(), c.getCredits(),
                    c.getBuildings().size(), c.territorySize()));
        }
        source.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    // —— tick / backdate ——

    private static int forceTick(CommandSourceStack source) {
        EconomyTicker.settleAll(source.getServer(), true);
        source.sendSuccess(() -> Component.literal(String.format(
                "已强制结算 %d 个殖民地，主线程耗时 %.3f ms（会话峰值 %.3f ms）。",
                EconomyTicker.getLastColonyCount(), EconomyTicker.getLastDurationMs(),
                EconomyTicker.getMaxDurationMs())), false);
        return 1;
    }

    private static int backdate(CommandSourceStack source, int minutes) {
        Colony colony = resolveColony(source);
        if (colony == null) {
            source.sendFailure(Component.literal("没有目标殖民地。"));
            return 0;
        }
        colony.setLastTickWallTime(colony.getLastTickWallTime() - minutes * 60_000L);
        source.sendSuccess(() -> Component.literal(String.format(
                "已将最后结算时间回拨 %d 分钟，下次结算将触发离线补算（上限 %d 小时）。可用 /colony tick 立即触发。",
                minutes, Config.OFFLINE_CATCHUP_CAP_HOURS.get())), false);
        return 1;
    }

    // —— credits ——

    private static int credits(CommandSourceStack source, long amount, boolean absolute) {
        Colony colony = resolveColony(source);
        if (colony == null) {
            source.sendFailure(Component.literal("没有目标殖民地。"));
            return 0;
        }
        long before = colony.getCredits();
        long after;
        if (absolute) {
            colony.setCredits(amount);
            after = colony.getCredits();
        } else {
            after = colony.adjustCredits(amount);
        }
        long finalAfter = after;
        source.sendSuccess(() -> Component.literal(String.format("信用点：%d → %d。", before, finalAfter)), false);
        return 1;
    }

    // —— building ——

    private static int buildingAdd(CommandSourceStack source, String defId, int tier) {
        Colony colony = resolveColony(source);
        if (colony == null) {
            source.sendFailure(Component.literal("没有目标殖民地。"));
            return 0;
        }
        BuildingDefinition def = BuildingDefinitions.get(defId).orElse(null);
        if (def == null) {
            source.sendFailure(Component.literal("未知建筑定义：" + defId
                    + "（可用 " + BuildingDefinitions.all().keySet() + "）"));
            return 0;
        }
        int clampedTier = Math.max(1, Math.min(def.maxTier(), tier));
        long chunk;
        if (source.getEntity() instanceof ServerPlayer player) {
            chunk = new ChunkPos(player.blockPosition()).toLong();
        } else {
            chunk = colony.getHqChunk();
        }
        colony.addBuilding(new BuildingInstance(def.id(), clampedTier, chunk));
        source.sendSuccess(() -> Component.literal(String.format(
                "建筑「%s」已添加至 %d 级（调试通道，未扣料；真实施工 M6.2 接入）。", def.name(), clampedTier)), false);
        return 1;
    }

    private static int buildingRemove(CommandSourceStack source, String defId) {
        Colony colony = resolveColony(source);
        if (colony == null) {
            source.sendFailure(Component.literal("没有目标殖民地。"));
            return 0;
        }
        if (colony.removeBuildingByDefinition(defId)) {
            source.sendSuccess(() -> Component.literal("建筑 " + defId + " 已移除（调试通道，不返还）。"), false);
            return 1;
        }
        source.sendFailure(Component.literal("该殖民地没有 " + defId + " 建筑。"));
        return 0;
    }

    // —— chunk / trust / build（M6.2；与 GUI 按钮同源 ConstructionService/ColonyManager） ——

    private static int chunkPrice(CommandSourceStack source) {
        Colony colony = resolveColony(source);
        if (colony == null) {
            source.sendFailure(Component.literal("没有目标殖民地。"));
            return 0;
        }
        long price = ColonyManager.nextChunkPrice(colony);
        source.sendSuccess(() -> Component.literal(String.format(
                "当前持有 %d 块，下一块价格 %d 信用点（第 N 块 = 基础价 %d × N）。购地冷却 %d 秒。",
                colony.territorySize(), price, Config.CHUNK_PRICE_BASE.get(),
                Config.PURCHASE_COOLDOWN_SECONDS.get())), false);
        return 1;
    }

    private static int chunkBuy(CommandSourceStack source, int cx, int cz) {
        ServerLevel level = source.getLevel();
        Colony colony = resolveColony(source);
        if (colony == null) {
            source.sendFailure(Component.literal("没有目标殖民地。"));
            return 0;
        }
        var result = ColonyManager.buyChunk(level, colony, cx, cz);
        if (result.success()) {
            source.sendSuccess(() -> Component.literal(result.message()), true);
            return 1;
        }
        source.sendFailure(Component.literal(result.message()));
        return 0;
    }

    private static int chunkAbandon(CommandSourceStack source, int cx, int cz) {
        ServerLevel level = source.getLevel();
        Colony colony = resolveColony(source);
        if (colony == null) {
            source.sendFailure(Component.literal("没有目标殖民地。"));
            return 0;
        }
        var result = ColonyManager.abandonChunk(level, colony, cx, cz);
        if (result.success()) {
            source.sendSuccess(() -> Component.literal(result.message()), true);
            return 1;
        }
        source.sendFailure(Component.literal(result.message()));
        return 0;
    }

    private static int trust(CommandSourceStack source, String playerName, boolean add) {
        Colony colony = resolveColony(source);
        if (colony == null) {
            source.sendFailure(Component.literal("没有目标殖民地。"));
            return 0;
        }
        var profile = source.getServer().getProfileCache().get(playerName);
        if (profile.isEmpty()) {
            source.sendFailure(Component.literal("找不到玩家：" + playerName));
            return 0;
        }
        UUID uuid = profile.get().getId();
        if (add) {
            colony.setTrust(uuid, Colony.Trust.TRUSTED);
            source.sendSuccess(() -> Component.literal(playerName + " 已授予「可信」权限（可开建筑 GUI 收货）。"), true);
        } else {
            colony.setTrust(uuid, null);
            source.sendSuccess(() -> Component.literal(playerName + " 的信任授权已移除。"), true);
        }
        return 1;
    }

    private static int trustList(CommandSourceStack source) {
        Colony colony = resolveColony(source);
        if (colony == null) {
            source.sendFailure(Component.literal("没有目标殖民地。"));
            return 0;
        }
        if (colony.trustEntries().isEmpty()) {
            source.sendSuccess(() -> Component.literal("信任名单为空（访客默认可通行与贸易站交互）。"), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("信任名单（可信）:");
        colony.trustEntries().forEach((uuid, level) -> {
            var p = source.getServer().getPlayerList().getPlayer(uuid);
            sb.append(' ').append(p != null ? p.getName().getString() : uuid).append("(可信)");
        });
        source.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int build(CommandSourceStack source, String defId, int tier, boolean free) {
        ServerLevel level = source.getLevel();
        Colony colony = resolveColony(source);
        if (colony == null) {
            source.sendFailure(Component.literal("没有目标殖民地。"));
            return 0;
        }
        BlockPos base;
        if (source.getEntity() instanceof ServerPlayer player) {
            base = player.blockPosition();
        } else {
            base = level.getSharedSpawnPos();
        }
        BuildingDefinition def = BuildingDefinitions.get(defId).orElse(null);
        if (def == null) {
            source.sendFailure(Component.literal("未知建筑定义：" + defId));
            return 0;
        }
        BlockPos origin = ConstructionService.findClearOrigin(level, base,
                def.footprintW(), def.footprintH(), def.footprintD());
        var result = ConstructionService.placeFoundation(level,
                source.getEntity() instanceof ServerPlayer p ? p : null, origin, defId, tier, free);
        if (result.success()) {
            source.sendSuccess(() -> Component.literal(result.message() + " 位置：" + origin.toShortString()), true);
            return 1;
        }
        source.sendFailure(Component.literal(result.message()));
        return 0;
    }

    private static int upgrade(CommandSourceStack source, String defId) {
        ServerLevel level = source.getLevel();
        Colony colony = resolveColony(source);
        if (colony == null) {
            source.sendFailure(Component.literal("没有目标殖民地。"));
            return 0;
        }
        BuildingInstance building = colony.getBuildings().stream()
                .filter(b -> b.definitionId().equals(defId)).findFirst().orElse(null);
        if (building == null) {
            source.sendFailure(Component.literal("该殖民地没有 " + defId + " 建筑。"));
            return 0;
        }
        ServerPlayer player = source.getEntity() instanceof ServerPlayer p ? p : null;
        var result = ConstructionService.tryUpgrade(level, colony, building, player);
        if (result.success()) {
            source.sendSuccess(() -> Component.literal(result.message()), true);
            return 1;
        }
        source.sendFailure(Component.literal(result.message()));
        return 0;
    }

    private static int dismantle(CommandSourceStack source, String defId) {
        ServerLevel level = source.getLevel();
        Colony colony = resolveColony(source);
        if (colony == null) {
            source.sendFailure(Component.literal("没有目标殖民地。"));
            return 0;
        }
        BuildingInstance building = colony.getBuildings().stream()
                .filter(b -> b.definitionId().equals(defId)).findFirst().orElse(null);
        if (building == null) {
            source.sendFailure(Component.literal("该殖民地没有 " + defId + " 建筑。"));
            return 0;
        }
        ServerPlayer player = source.getEntity() instanceof ServerPlayer p ? p : null;
        var result = ConstructionService.tryDismantle(level, colony, building, player);
        if (result.success()) {
            source.sendSuccess(() -> Component.literal(result.message()), true);
            return 1;
        }
        source.sendFailure(Component.literal(result.message()));
        return 0;
    }

    /** OP 调试：将全部施工/拆除倒计时置为到点（冒烟不等待真实时长；到点后由核心方块 tick 完成成形）。 */
    private static int finishBuild(CommandSourceStack source) {
        Colony colony = resolveColony(source);
        if (colony == null) {
            source.sendFailure(Component.literal("没有目标殖民地。"));
            return 0;
        }
        long now = System.currentTimeMillis();
        final int[] count = {0};
        for (BuildingInstance b : colony.getBuildings()) {
            if (b.status() == BuildingInstance.Status.CONSTRUCTION) {
                b.setBuildEndWall(now);
                count[0]++;
            } else if (b.status() == BuildingInstance.Status.DISMANTLING) {
                b.setDismantleEndWall(now);
                count[0]++;
            }
        }
        source.sendSuccess(() -> Component.literal("已将 " + count[0] + " 个施工/拆除倒计时置为到点（区块加载时立即成形/拆除）。"), true);
        return 1;
    }

    /** OP 调试：打印 build 命令将选中的放置点与重叠判定明细。 */
    private static int debugOrigin(CommandSourceStack source, String defId) {
        ServerLevel level = source.getLevel();
        Colony colony = resolveColony(source);
        if (colony == null) {
            return 0;
        }
        BuildingDefinition def = BuildingDefinitions.get(defId).orElse(null);
        if (def == null) {
            return 0;
        }
        BlockPos base = source.getEntity() instanceof ServerPlayer p
                ? p.blockPosition() : level.getSharedSpawnPos();
        BlockPos origin = ConstructionService.findClearOrigin(level, base,
                def.footprintW(), def.footprintH(), def.footprintD());
        StringBuilder sb = new StringBuilder("base=" + base.toShortString() + " chosen=" + origin.toShortString() + "\n");

        for (BuildingInstance b : colony.getBuildings()) {
            sb.append(" · ").append(b.definitionId()).append(" origin=")
              .append(b.origin() < 0 ? "-1" : net.minecraft.core.BlockPos.of(b.origin()).toShortString())
              .append(" fp=").append(b.footprintW()).append('x').append(b.footprintH()).append('x').append(b.footprintD()).append("\n");

        }
        String out = sb.toString();
        source.sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    /** OP：解散殖民地（与 GUI「解散」按钮同源；拆完建筑为前置）。 */
    private static int dissolve(CommandSourceStack source) {
        Colony colony = resolveColony(source);
        if (colony == null) {
            source.sendFailure(Component.literal("没有目标殖民地。"));
            return 0;
        }
        var result = ColonyManager.dissolve(source.getLevel(), colony);
        if (result.success()) {
            source.sendSuccess(() -> Component.literal(result.message()), true);
            return 1;
        }
        source.sendFailure(Component.literal(result.message()));
        return 0;
    }

    private static int withdraw(CommandSourceStack source, String item, long count) {
        Colony colony = resolveColony(source);
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("该命令需要玩家执行。"));
            return 0;
        }
        long moved = ConstructionService.moveBufferToPlayer(colony, item, count, player);
        source.sendSuccess(() -> Component.literal("已取出 " + item + " ×" + moved + " 到背包。"), false);
        return 1;
    }

    // —— helpers ——

    /** OP 调试：向缓冲注入物品（同时是容量上限的冒烟通道）。 */
    private static int bufferAdd(CommandSourceStack source, String item, long count) {
        Colony colony = resolveColony(source);
        if (colony == null) {
            source.sendFailure(Component.literal("没有目标殖民地。"));
            return 0;
        }
        long accepted = colony.insertOutput(item, count);
        long finalAccepted = accepted;
        source.sendSuccess(() -> Component.literal(String.format(
                "缓冲注入 %s ×%d（接收 %d，容量拒收 %d）。",
                item, count, finalAccepted, count - finalAccepted)), false);
        return 1;
    }

    /** OP 调试：查看当前待领取的离线汇总（不消费，登录推送仍会带语）。 */
    private static int showSummary(CommandSourceStack source) {
        Colony colony = resolveColony(source);
        if (colony == null) {
            source.sendFailure(Component.literal("没有目标殖民地。"));
            return 0;
        }
        var summary = colony.getPendingSummary();
        if (summary == null) {
            source.sendSuccess(() -> Component.literal("当前没有待领取的离线汇总。"), false);
            return 1;
        }
        for (var line : summary.displayLines()) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static Colony resolveColony(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return ColonyManager.colonyOf(source.getServer(), player.getUUID());
        }
        return ColonyManager.debugColony(source.getServer());
    }
}
