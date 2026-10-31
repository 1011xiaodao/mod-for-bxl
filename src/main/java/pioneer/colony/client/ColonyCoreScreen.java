package pioneer.colony.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import pioneer.colony.colony.BuildingInstance;
import pioneer.colony.menu.ColonyCoreMenu;

/**
 * 核心菜单界面（M6.2 功能版）：无自定义底图（视觉验收 DEFERRED 整合包统一验收，美术底图后置），
 * 标签+按钮+领地地图网格；按钮走原版 button 包，无自定义网络层。
 */
public class ColonyCoreScreen extends AbstractContainerScreen<ColonyCoreMenu> {
    private static final int MAP_CELL = 12;
    private static final int MAP_SIZE = ColonyCoreMenu.MAP_SIZE;

    private int lastPage = -1;
    private int lastWidgetFlags = -1;
    private EditBox marketItemInput;
    private EditBox marketCountInput;
    private EditBox marketPriceInput;
    private EditBox marketDaysInput;
    private EditBox marketOrderIdInput;
    private EditBox trustPlayerInput;
    private EditBox treatyTargetInput;
    private EditBox treatyExportItemInput;
    private EditBox treatyExportRateInput;
    private EditBox treatyReturnItemInput;
    private EditBox treatyReturnRateInput;
    private EditBox treatyIdInput;

    public ColonyCoreScreen(ColonyCoreMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 196;
        this.imageHeight = 220;
    }

    @Override
    protected void init() {
        super.init();
        refreshPageWidgets();
    }

    private void refreshPageWidgets() {
        clearWidgets();
        int page = menu.page();
        int x = leftPos + 6;
        int y = topPos + 4;
        if (menu.isHq()) {
            addRenderableWidget(Button.builder(Component.literal("概览"),
                    b -> sendButton(ColonyCoreMenu.BTN_PAGE_OVERVIEW)).bounds(x, y, 34, 16).build());
            addRenderableWidget(Button.builder(Component.literal("领地"),
                    b -> sendButton(ColonyCoreMenu.BTN_PAGE_TERRITORY)).bounds(x + 36, y, 34, 16).build());
            addRenderableWidget(Button.builder(Component.literal("管理"),
                    b -> sendButton(ColonyCoreMenu.BTN_PAGE_CITY)).bounds(x + 72, y, 34, 16).build());
            if (menu.data(ColonyCoreMenu.SLOT_RESEARCH_AVAILABLE) == 1) {
                addRenderableWidget(Button.builder(Component.literal("研究"),
                        b -> sendButton(ColonyCoreMenu.BTN_PAGE_RESEARCH)).bounds(x + 108, y, 34, 16).build());
            }
            if (menu.data(ColonyCoreMenu.SLOT_IS_OWNER) == 1) {
                addRenderableWidget(Button.builder(Component.literal("协定"),
                        b -> sendButton(ColonyCoreMenu.BTN_PAGE_TREATY)).bounds(x + 144, y, 34, 16).build());
            }
            y += 20;
        } else if (menu.isTradeStation()) {
            addRenderableWidget(Button.builder(Component.literal("星联市场"),
                    b -> sendButton(ColonyCoreMenu.BTN_PAGE_MARKET)).bounds(x, y, 72, 16).build());
            y += 20;
        }

        if (page == 3) {
            var payload = pioneer.colony.network.ClientResearchCache.latest;
            if (payload != null) {
                int ey = y + 16;
                for (int i = 0; i < payload.entries().size() && i < 8; i++) {
                    var e = payload.entries().get(i);
                    if (e.startable()) {
                        final int idx = i;
                        addRenderableWidget(Button.builder(Component.literal("发起"),
                                b -> sendButton(ColonyCoreMenu.BTN_RESEARCH_BASE + idx))
                                .bounds(leftPos + 150, ey, 40, 14).build());
                    }
                    ey += 16;
                }
            }
        } else if (page == 2 && menu.isHq() && menu.data(ColonyCoreMenu.SLOT_IS_OWNER) == 1) {
            trustPlayerInput = new EditBox(font, leftPos + 8, topPos + 94, 180, 14, Component.literal("玩家名"));
            trustPlayerInput.setMaxLength(16);
            addRenderableWidget(trustPlayerInput);
            addRenderableWidget(Button.builder(Component.literal("访客"),
                    b -> submitTrustCommand("add", "visitor")).bounds(leftPos + 8, topPos + 112, 42, 14).build());
            addRenderableWidget(Button.builder(Component.literal("可信"),
                    b -> submitTrustCommand("add", "trusted")).bounds(leftPos + 54, topPos + 112, 42, 14).build());
            addRenderableWidget(Button.builder(Component.literal("移除"),
                    b -> submitTrustCommand("remove", "")).bounds(leftPos + 100, topPos + 112, 42, 14).build());
            addRenderableWidget(Button.builder(Component.literal("查看名单"),
                    b -> submitTrustList()).bounds(leftPos + 146, topPos + 112, 44, 14).build());
        } else if (page == 5 && menu.isHq() && menu.data(ColonyCoreMenu.SLOT_IS_OWNER) == 1) {
            treatyTargetInput = new EditBox(font, leftPos + 8, topPos + 58, 72, 14, Component.literal("目标总督"));
            treatyTargetInput.setMaxLength(16);
            treatyExportItemInput = new EditBox(font, leftPos + 84, topPos + 58, 104, 14, Component.literal("出口物品 ID"));
            treatyExportItemInput.setMaxLength(128);
            treatyExportItemInput.setValue("minecraft:wheat");
            treatyExportRateInput = new EditBox(font, leftPos + 8, topPos + 78, 54, 14, Component.literal("出口/小时"));
            treatyExportRateInput.setMaxLength(12);
            treatyExportRateInput.setValue("64");
            treatyReturnItemInput = new EditBox(font, leftPos + 66, topPos + 78, 88, 14, Component.literal("返还物品 ID"));
            treatyReturnItemInput.setMaxLength(128);
            treatyReturnRateInput = new EditBox(font, leftPos + 158, topPos + 78, 30, 14, Component.literal("返还/小时"));
            treatyReturnRateInput.setMaxLength(12);
            treatyReturnRateInput.setValue("0");
            treatyIdInput = new EditBox(font, leftPos + 8, topPos + 140, 64, 14, Component.literal("协定 ID"));
            treatyIdInput.setMaxLength(18);
            addRenderableWidget(treatyTargetInput);
            addRenderableWidget(treatyExportItemInput);
            addRenderableWidget(treatyExportRateInput);
            addRenderableWidget(treatyReturnItemInput);
            addRenderableWidget(treatyReturnRateInput);
            addRenderableWidget(treatyIdInput);
            addRenderableWidget(Button.builder(Component.literal("发起邀约"),
                    b -> submitTreatyProposal()).bounds(leftPos + 8, topPos + 98, 64, 16).build());
            addRenderableWidget(Button.builder(Component.literal("查看协定"),
                    b -> sendTreatyCommand("list", "")).bounds(leftPos + 76, topPos + 98, 64, 16).build());
            addRenderableWidget(Button.builder(Component.literal("确认"),
                    b -> sendTreatyCommand("accept", treatyIdInput.getValue())).bounds(leftPos + 76, topPos + 140, 48, 14).build());
            addRenderableWidget(Button.builder(Component.literal("解约"),
                    b -> sendTreatyCommand("terminate", treatyIdInput.getValue())).bounds(leftPos + 128, topPos + 140, 48, 14).build());
        } else if (page == 4 && menu.isTradeStation()) {
            marketItemInput = new EditBox(font, leftPos + 8, topPos + 142, 180, 14, Component.literal("物品 ID"));
            marketItemInput.setMaxLength(256);
            marketItemInput.setValue("minecraft:wheat");
            marketCountInput = new EditBox(font, leftPos + 8, topPos + 160, 46, 14, Component.literal("数量"));
            marketCountInput.setValue("1");
            marketCountInput.setMaxLength(12);
            marketPriceInput = new EditBox(font, leftPos + 58, topPos + 160, 46, 14, Component.literal("价格"));
            marketPriceInput.setValue("0");
            marketPriceInput.setMaxLength(12);
            marketDaysInput = new EditBox(font, leftPos + 108, topPos + 160, 28, 14, Component.literal("天数"));
            marketDaysInput.setValue("1");
            marketDaysInput.setMaxLength(2);
            marketOrderIdInput = new EditBox(font, leftPos + 8, topPos + 181, 62, 14, Component.literal("订单 ID"));
            marketOrderIdInput.setMaxLength(12);
            addRenderableWidget(marketItemInput);
            addRenderableWidget(marketCountInput);
            addRenderableWidget(marketPriceInput);
            addRenderableWidget(marketDaysInput);
            addRenderableWidget(marketOrderIdInput);
            addRenderableWidget(Button.builder(Component.literal("买"), b -> submitMarketAction(
                    pioneer.colony.network.MarketActionPayload.Action.BUY)).bounds(leftPos + 140, topPos + 160, 24, 14).build());
            addRenderableWidget(Button.builder(Component.literal("卖"), b -> submitMarketAction(
                    pioneer.colony.network.MarketActionPayload.Action.SELL)).bounds(leftPos + 166, topPos + 160, 24, 14).build());
            addRenderableWidget(Button.builder(Component.literal("撤销订单"), b -> submitMarketAction(
                    pioneer.colony.network.MarketActionPayload.Action.CANCEL)).bounds(leftPos + 74, topPos + 181, 70, 14).build());
        }
        int status = menu.data(ColonyCoreMenu.SLOT_STATUS);
        if (page == 0) {
            addRenderableWidget(Button.builder(Component.literal("收取产出"),
                    b -> sendButton(ColonyCoreMenu.BTN_WITHDRAW)).bounds(leftPos + 8, y + 76, 88, 18).build());
            addRenderableWidget(Button.builder(Component.literal("升级"),
                    b -> sendButton(ColonyCoreMenu.BTN_UPGRADE)).bounds(leftPos + 100, y + 76, 40, 18).build());
            addRenderableWidget(Button.builder(Component.literal("拆除"),
                    b -> sendButton(ColonyCoreMenu.BTN_DISMANTLE)).bounds(leftPos + 144, y + 76, 40, 18).build());
            if (menu.data(ColonyCoreMenu.SLOT_IS_WAREHOUSE) == 1) {
                addRenderableWidget(Button.builder(Component.literal("存入全部"),
                        b -> sendButton(ColonyCoreMenu.BTN_DEPOSIT_ALL)).bounds(leftPos + 8, y + 96, 88, 18).build());
                addRenderableWidget(Button.builder(Component.literal("取出全部"),
                        b -> sendButton(ColonyCoreMenu.BTN_WITHDRAW_ALL)).bounds(leftPos + 100, y + 96, 88, 18).build());
            }
        } else if (page == 1 && menu.isHq()) {
            addRenderableWidget(Button.builder(Component.literal("确认退地"),
                    b -> sendButton(ColonyCoreMenu.BTN_CONFIRM_ABANDON)).bounds(leftPos + 8, y + 118, 80, 18).build());
        } else if (page == 2 && menu.isHq()) {
            addRenderableWidget(Button.builder(Component.literal("解散殖民地（二次确认）"),
                    b -> sendButton(ColonyCoreMenu.BTN_DISSOLVE)).bounds(leftPos + 8, y + 118, 180, 18).build());
        }
        this.lastPage = page;
        this.lastWidgetFlags = widgetFlags();
    }

    private int widgetFlags() {
        return (menu.isHq() ? 1 : 0)
                | (menu.isTradeStation() ? 1 << 1 : 0)
                | (menu.data(ColonyCoreMenu.SLOT_RESEARCH_AVAILABLE) == 1 ? 1 << 2 : 0)
                | (menu.data(ColonyCoreMenu.SLOT_IS_OWNER) == 1 ? 1 << 3 : 0);
    }

    private void submitTrustCommand(String action, String role) {
        if (minecraft == null || minecraft.player == null || trustPlayerInput == null) return;
        String playerName = trustPlayerInput.getValue().trim();
        if (!playerName.matches("[A-Za-z0-9_]{1,16}")) {
            minecraft.player.displayClientMessage(Component.literal("请输入有效的 Minecraft 玩家名（1～16 位字母、数字或下划线）。"), true);
            return;
        }
        String command = "colony trust " + action + " " + playerName;
        if (!role.isBlank()) command += " " + role;
        minecraft.player.connection.sendCommand(command);
    }

    private void submitTrustList() {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.connection.sendCommand("colony trust list");
        }
    }

    private void submitTreatyProposal() {
        if (minecraft == null || minecraft.player == null) return;
        String target = treatyTargetInput.getValue().trim();
        String exportItem = treatyExportItemInput.getValue().trim();
        String returnItem = treatyReturnItemInput.getValue().trim();
        if (!target.matches("[A-Za-z0-9_]{1,16}") || !validItemId(exportItem)
                || (!returnItem.isBlank() && !returnItem.equals("credits") && !validItemId(returnItem))) {
            minecraft.player.displayClientMessage(Component.literal("请检查目标玩家名和物品 ID 格式。"), true);
            return;
        }
        try {
            long exportRate = Long.parseLong(treatyExportRateInput.getValue().trim());
            long returnRate = Long.parseLong(treatyReturnRateInput.getValue().trim());
            if (exportRate < 1 || returnRate < 0) throw new NumberFormatException();
            String returnArg = returnRate == 0 ? "none" : (returnItem.isBlank() ? "credits" : returnItem);
            minecraft.player.connection.sendCommand("colony treaty propose " + target + " " + exportItem
                    + " " + exportRate + " " + returnArg + " " + returnRate);
        } catch (NumberFormatException error) {
            minecraft.player.displayClientMessage(Component.literal("出口/返还速率必须是有效整数（出口至少 1，返还不小于 0）。"), true);
        }
    }

    private boolean validItemId(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_./-]+");
    }

    private void sendTreatyCommand(String action, String rawId) {
        if (minecraft == null || minecraft.player == null) return;
        if ("list".equals(action)) {
            minecraft.player.connection.sendCommand("colony treaty list");
            return;
        }
        try {
            long id = Long.parseLong(rawId.trim());
            if (id < 1) throw new NumberFormatException();
            minecraft.player.connection.sendCommand("colony treaty " + action + " " + id);
        } catch (NumberFormatException error) {
            minecraft.player.displayClientMessage(Component.literal("请输入有效的正整数协定 ID。"), true);
        }
    }

    private void submitMarketAction(pioneer.colony.network.MarketActionPayload.Action action) {
        if (minecraft == null || minecraft.player == null) return;
        try {
            if (action == pioneer.colony.network.MarketActionPayload.Action.CANCEL) {
                long orderId = Long.parseLong(marketOrderIdInput.getValue().trim());
                PacketDistributor.sendToServer(new pioneer.colony.network.MarketActionPayload(action, "", 0, 0, 0, orderId));
                return;
            }
            String item = marketItemInput.getValue().trim();
            long count = Long.parseLong(marketCountInput.getValue().trim());
            long price = Long.parseLong(marketPriceInput.getValue().trim());
            int days = Integer.parseInt(marketDaysInput.getValue().trim());
            PacketDistributor.sendToServer(new pioneer.colony.network.MarketActionPayload(action, item, count, price, days, 0));
        } catch (NumberFormatException error) {
            minecraft.player.displayClientMessage(Component.literal("市场输入格式错误：数量/价格/天数/订单 ID 必须是整数。"), true);
        }
    }

    private void sendButton(int id) {
        if (this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
        }
    }

    private Object lastCacheRef = null;

    @Override
    public void containerTick() {
        super.containerTick();
        if (menu.page() != lastPage || widgetFlags() != lastWidgetFlags || ClientResearchCacheChanged()) {
            refreshPageWidgets();
        }
    }

    private boolean ClientResearchCacheChanged() {
        var current = pioneer.colony.network.ClientResearchCache.latest;
        if (current != lastCacheRef) {
            lastCacheRef = current;
            return menu.page() == 3;
        }
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 无贴图底：纯色面板（正式 GUI 底图由项目主美术交付后替换）
        graphics.fill(leftPos - 6, topPos - 6, leftPos + imageWidth + 6, topPos + imageHeight + 6, 0xF0202028);
        graphics.renderOutline(leftPos - 6, topPos - 6, imageWidth + 12, imageHeight + 12, 0xFF4A5568);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // 面板已在 render() 绘制
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = 8;
        int y = menu.isHq() ? 26 : 8;
        graphics.drawString(this.font, this.title, x, y - 2, 0xFF9AE6B4, false);
        int page = menu.page();
        if (page == 0) {
            int status = menu.clientData(ColonyCoreMenu.SLOT_STATUS);
            graphics.drawString(font, "等级：" + menu.clientData(ColonyCoreMenu.SLOT_TIER)
                    + "   状态：" + statusName(status), x, y + 12, 0xFFE2E8F0, false);
            if (status == BuildingInstance.Status.CONSTRUCTION.ordinal()
                    || status == BuildingInstance.Status.DISMANTLING.ordinal()) {
                graphics.drawString(font, "剩余 " + menu.clientData(ColonyCoreMenu.SLOT_REMAIN_SECONDS) + " 秒", x, y + 24, 0xFFF6E05E, false);
            } else {
                graphics.drawString(font, "岗位填充 " + menu.clientData(ColonyCoreMenu.SLOT_FILL) + "%", x, y + 24, 0xFFE2E8F0, false);
                if (menu.clientData(ColonyCoreMenu.SLOT_INPUT_SHORT) == 1) {
                    graphics.drawString(font, "缺料降效：请补充输入材料", x + 90, y + 24, 0xFFFC8181, false);
                }
                if (menu.clientData(ColonyCoreMenu.SLOT_MAINT_OK) == 0) {
                    graphics.drawString(font, "维护断供（效率减半）", x, y + 36, 0xFFFC8181, false);
                }
                if (menu.clientData(ColonyCoreMenu.SLOT_STORAGE_FULL) == 1) {
                    graphics.drawString(font, "仓库已满，产出丢弃中", x + 90, y + 36, 0xFFFC8181, false);
                }
            }
            graphics.drawString(font, "可收产出：" + menu.clientData(ColonyCoreMenu.SLOT_OUTPUT_COUNT), x, y + 50, 0xFFA0AEC0, false);
            graphics.drawString(font, "信用点：" + menu.clientData(ColonyCoreMenu.SLOT_CREDITS), x, y + 62, 0xFFFFD700, false);
        } else if (page == 1) {
            renderMap(graphics, x, y + 14, mouseX, mouseY);
            graphics.drawString(font, "领地 " + menu.clientData(ColonyCoreMenu.SLOT_CHUNKS) + "/"
                    + menu.clientData(ColonyCoreMenu.SLOT_MAX_CHUNKS) + "  下块价格 "
                    + menu.clientData(ColonyCoreMenu.SLOT_PRICE) + "  冷却 "
                    + menu.clientData(ColonyCoreMenu.SLOT_COOLDOWN) + "s", x, y + 14 + MAP_SIZE * MAP_CELL + 6, 0xFFE2E8F0, false);
        } else if (page == 3) {
            var payload = pioneer.colony.network.ClientResearchCache.latest;
            if (payload == null) {
                graphics.drawString(font, "等待研究数据同步……", x, y + 12, 0xFFA0AEC0, false);
            } else {
                graphics.drawString(font, String.format("并行 %d/%d  加成 +%d%%  组织费 %d",
                        payload.slotsUsed(), payload.slotsMax(), payload.speedPercent(), payload.orgFee()),
                        x, y + 12, 0xFF9AE6B4, false);
                int ey = y + 28;
                for (var e : payload.entries()) {
                    int color = switch (e.stateText()) {
                        case "可发起" -> 0xFF9AE6B4;
                        case "进行中" -> 0xFFF6E05E;
                        case "已完成" -> 0xFF63B3ED;
                        default -> 0xFF718096;
                    };
                    String line = e.name() + " [" + e.channel() + "] " + e.stateText();
                    graphics.drawString(font, line, x, ey, color, false);
                    graphics.drawString(font, e.costText(), x + 100, ey, 0xFFA0AEC0, false);
                    if (e.stateText().equals("进行中")) {
                        graphics.drawString(font, "剩余 " + e.remainingSeconds() + "s", x + 100, ey + 8, 0xFFF6E05E, false);
                    }
                    ey += 16;
                }
            }
        } else if (page == 4) {
            graphics.drawString(font, "星联市场 · 信用点/件", x, y + 12, 0xFF9AE6B4, false);
            var payload = pioneer.colony.network.ClientMarketCache.latest;
            if (payload == null) {
                graphics.drawString(font, "等待市场数据同步……", x, y + 24, 0xFFA0AEC0, false);
            } else {
                int py = y + 23;
                for (var entry : payload.prices()) {
                    String itemLabel = entry.item().replace("minecraft:", "");
                    if (itemLabel.length() > 18) itemLabel = itemLabel.substring(0, 17) + "…";
                    String minLabel = String.format(java.util.Locale.ROOT, "%.1f", entry.min());
                    String maxLabel = entry.max() >= 1_000_000.0 ? "∞"
                            : String.format(java.util.Locale.ROOT, "%.1f", entry.max());
                    graphics.drawString(font, itemLabel + " " + entry.price()
                            + " [" + minLabel + "-" + maxLabel + "]", x, py, 0xFFE2E8F0, false);
                    py += 9;
                }
                graphics.drawString(font, "我的挂单（price=0 为市价单）", x, y + 96, 0xFFF6E05E, false);
                int oy = y + 107;
                for (var order : payload.orders().stream().limit(3).toList()) {
                    String price = order.limitPrice() == 0 ? "市价" : Long.toString(order.limitPrice());
                    graphics.drawString(font, "#" + order.id() + " " + order.side() + " " + order.item()
                            + " ×" + order.remaining() + " @" + price, x, oy, 0xFFA0AEC0, false);
                    oy += 9;
                }
            }
            graphics.drawString(font, "物品 ID", x, y + 132, 0xFFA0AEC0, false);
            graphics.drawString(font, "数量", x, y + 151, 0xFFA0AEC0, false);
            graphics.drawString(font, "限价", x + 50, y + 151, 0xFFA0AEC0, false);
            graphics.drawString(font, "天数", x + 100, y + 151, 0xFFA0AEC0, false);
            graphics.drawString(font, "订单 ID", x, y + 172, 0xFFA0AEC0, false);
        } else if (page == 5) {
            graphics.drawString(font, "城市间贸易协定", x, y + 12, 0xFF9AE6B4, false);
            graphics.drawString(font, "目标总督 / 出口物品 ID", x, y + 28, 0xFFA0AEC0, false);
            graphics.drawString(font, "出口/小时 / 返还物品 / 返还/小时", x, y + 42, 0xFFA0AEC0, false);
            graphics.drawString(font, "协定 ID", x, y + 100, 0xFFA0AEC0, false);
        } else if (page == 2) {
            graphics.drawString(font, "城市管理", x, y + 12, 0xFFE2E8F0, false);
            graphics.drawString(font, "解散前置：先拆除全部建筑", x, y + 24, 0xFFA0AEC0, false);
            graphics.drawString(font, "解散将清除总部结构，退购地费 50%", x, y + 36, 0xFFA0AEC0, false);
            graphics.drawString(font, "（随下次创建殖民地发放）", x, y + 48, 0xFFA0AEC0, false);
            if (menu.clientData(ColonyCoreMenu.SLOT_IS_OWNER) == 1) {
                graphics.drawString(font, "信任名单：输入玩家名后授予访客/可信权限", x, y + 64, 0xFF9AE6B4, false);
            } else {
                graphics.drawString(font, "只有总督可以管理信任名单和解散殖民地", x, y + 64, 0xFFA0AEC0, false);
            }
            if (menu.clientData(ColonyCoreMenu.SLOT_CONFIRMED) == 1) {
                graphics.drawString(font, "已武装确认，再次点击生效", x, y + 100, 0xFFFC8181, false);
            }
        }
    }

    private void renderMap(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        for (int offset = 0; offset < MAP_SIZE * MAP_SIZE; offset++) {
            int cellX = x + (offset % MAP_SIZE) * MAP_CELL;
            int cellY = y + (offset / MAP_SIZE) * MAP_CELL;
            int state = menu.data(ColonyCoreMenu.SLOT_MAP_BASE + offset);
            int color = switch (state) {
                case ColonyCoreMenu.MAP_OWN -> 0xFF38A169;
                case ColonyCoreMenu.MAP_FOREIGN -> 0xFF9B2C2C;
                case ColonyCoreMenu.MAP_BUYABLE -> 0xFF2D3748;
                case ColonyCoreMenu.MAP_TOO_EXPENSIVE -> 0xFF1A202C;
                case ColonyCoreMenu.MAP_NOT_ADJACENT -> 0xFF171923;
                default -> 0xFF0F1015;
            };
            if (menu.data(ColonyCoreMenu.SLOT_SELECTED) == offset) {
                color = 0xFFF6E05E;
            }
            int cx = cellX - leftPos;
            int cy = cellY - topPos;
            graphics.fill(cx, cy, cx + MAP_CELL - 1, cy + MAP_CELL - 1, color);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (menu.page() == 1 && menu.isHq()) {
            int gridX = 8 + leftPos;
            int gridY = (menu.isHq() ? 26 : 8) + 14 + topPos;
            if (mouseX >= gridX && mouseY >= gridY
                    && mouseX < gridX + MAP_SIZE * MAP_CELL && mouseY < gridY + MAP_SIZE * MAP_CELL) {
                int dx = Mth.floor((mouseX - gridX) / MAP_CELL);
                int dz = Mth.floor((mouseY - gridY) / MAP_CELL);
                int offset = dz * MAP_SIZE + dx;
                sendButton(ColonyCoreMenu.BTN_MAP_BASE + offset);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static String statusName(int ordinal) {
        return switch (ordinal) {
            case 0 -> "施工中";
            case 1 -> "运转中";
            case 2 -> "受损";
            case 3 -> "维修中";
            case 4 -> "拆除中";
            default -> "未知";
        };
    }
}
