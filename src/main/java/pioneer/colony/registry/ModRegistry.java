package pioneer.colony.registry;

import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import pioneer.colony.PioneerColony;
import pioneer.colony.block.ColonyCoreBlock;
import pioneer.colony.block.ConstructionBarrierBlock;
import pioneer.colony.blockentity.ColonyCoreBlockEntity;
import pioneer.colony.entity.CitizenEntity;
import pioneer.colony.item.FoundationItem;
import pioneer.colony.menu.ColonyCoreMenu;

/**
 * M6.2 注册中心：核心方块（地基/逻辑核心）、施工围挡、9 种建筑地基物品、
 * 核心方块 BlockEntity、核心菜单、创造模式标签页。
 * 占位贴图由 tools/gen_textures.py 程序化生成（美术资产到位后替换）。
 */
public final class ModRegistry {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(PioneerColony.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(PioneerColony.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, PioneerColony.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, PioneerColony.MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, PioneerColony.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, PioneerColony.MODID);

    // —— 方块 ——

    /** 建筑地基 = 逻辑核心方块；建筑结构对玩家不可挖掘（06 §3.2），仅 GUI 拆除，故不可破坏（创造可挖）。 */
    public static final DeferredBlock<ColonyCoreBlock> COLONY_CORE = BLOCKS.register("colony_core",
            () -> new ColonyCoreBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(-1.0F, 3_600_000.0F)
                    .noLootTable()
                    .sound(SoundType.METAL)));

    /** 施工围挡（警示条纹占位）：施工期间环绕 footprint，完成/拆除时自动清除。 */
    public static final DeferredBlock<ConstructionBarrierBlock> CONSTRUCTION_BARRIER = BLOCKS.register("construction_barrier",
            () -> new ConstructionBarrierBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_YELLOW)
                    .strength(0.5F)
                    .noLootTable()
                    .sound(SoundType.WOOD)));

    /** 市民参战实体（M6.4 袭击；M6.5 演化为常驻市民）。 */
    public static final DeferredHolder<EntityType<?>, EntityType<CitizenEntity>> CITIZEN_ENTITY =
            ENTITIES.register("citizen", () -> EntityType.Builder
                    .of(CitizenEntity::new, net.minecraft.world.entity.MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(10)
                    .build("pioneer_colony:citizen"));

    // —— BlockEntity / Menu ——

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ColonyCoreBlockEntity>> COLONY_CORE_BE =
            BLOCK_ENTITIES.register("colony_core",
                    () -> BlockEntityType.Builder.of(ColonyCoreBlockEntity::new, COLONY_CORE.get()).build(null));

    /** 客户端工厂不读 buf——全部动态状态经 data slots 同步（零自定义网络包）。 */
    public static final DeferredHolder<MenuType<?>, MenuType<ColonyCoreMenu>> COLONY_CORE_MENU =
            MENUS.register("colony_core",
                    () -> IMenuTypeExtension.create((id, inv, buf) -> new ColonyCoreMenu(id, inv)));

    // —— 建筑地基物品（每建筑一种；GUI 选类型以物品选型代替，见回报 §5） ——

    public static final DeferredItem<FoundationItem> FOUNDATION_WAREHOUSE = registerFoundation("warehouse");
    public static final DeferredItem<FoundationItem> FOUNDATION_RESIDENCE = registerFoundation("residence");
    public static final DeferredItem<FoundationItem> FOUNDATION_CANTEEN = registerFoundation("canteen");
    public static final DeferredItem<FoundationItem> FOUNDATION_FARM = registerFoundation("farm");
    public static final DeferredItem<FoundationItem> FOUNDATION_SMELTER = registerFoundation("smelter");
    public static final DeferredItem<FoundationItem> FOUNDATION_GUARD_POST = registerFoundation("guard_post");
    public static final DeferredItem<FoundationItem> FOUNDATION_TRADE_STATION = registerFoundation("trade_station");
    public static final DeferredItem<FoundationItem> FOUNDATION_RESEARCH_INSTITUTE = registerFoundation("research_institute");
    public static final DeferredItem<FoundationItem> FOUNDATION_BOILER_ROOM = registerFoundation("boiler_room");

    public static final List<DeferredItem<FoundationItem>> FOUNDATIONS = List.of(
            FOUNDATION_WAREHOUSE, FOUNDATION_RESIDENCE, FOUNDATION_CANTEEN, FOUNDATION_FARM, FOUNDATION_SMELTER,
            FOUNDATION_GUARD_POST, FOUNDATION_TRADE_STATION, FOUNDATION_RESEARCH_INSTITUTE, FOUNDATION_BOILER_ROOM);

    private static DeferredItem<FoundationItem> registerFoundation(String defId) {
        return ITEMS.register("foundation_" + defId,
                () -> new FoundationItem(defId, new net.minecraft.world.item.Item.Properties().stacksTo(16)));
    }

    // —— 创造模式标签页 ——

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = CREATIVE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.pioneer_colony"))
                    .icon(() -> new ItemStack(FOUNDATION_FARM.get()))
                    .displayItems((params, output) -> FOUNDATIONS.forEach(output::accept))
                    .build());

    private ModRegistry() {
    }
}
