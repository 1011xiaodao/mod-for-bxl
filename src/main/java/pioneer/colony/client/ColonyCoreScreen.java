package pioneer.colony.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
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

    public ColonyCoreScreen(ColonyCoreMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 196;
        this.imageHeight = 166;
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
                    b -> sendButton(ColonyCoreMenu.BTN_PAGE_OVERVIEW)).bounds(x, y, 40, 16).build());
            addRenderableWidget(Button.builder(Component.literal("领地"),
                    b -> sendButton(ColonyCoreMenu.BTN_PAGE_TERRITORY)).bounds(x + 44, y, 40, 16).build());
            addRenderableWidget(Button.builder(Component.literal("管理"),
                    b -> sendButton(ColonyCoreMenu.BTN_PAGE_CITY)).bounds(x + 88, y, 40, 16).build());
            y += 20;
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
    }

    private void sendButton(int id) {
        if (this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
        }
    }

    @Override
    public void containerTick() {
        super.containerTick();
        if (menu.page() != lastPage) {
            refreshPageWidgets();
        }
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
        } else if (page == 2) {
            graphics.drawString(font, "城市管理", x, y + 12, 0xFFE2E8F0, false);
            graphics.drawString(font, "解散前置：先拆除全部建筑", x, y + 24, 0xFFA0AEC0, false);
            graphics.drawString(font, "解散将清除总部结构，退购地费 50%", x, y + 36, 0xFFA0AEC0, false);
            graphics.drawString(font, "（随下次创建殖民地发放）", x, y + 48, 0xFFA0AEC0, false);
            if (menu.clientData(ColonyCoreMenu.SLOT_CONFIRMED) == 1) {
                graphics.drawString(font, "已武装确认，再次点击生效", x, y + 64, 0xFFFC8181, false);
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
