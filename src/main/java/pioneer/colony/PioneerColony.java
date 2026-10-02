package pioneer.colony;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;
import pioneer.colony.config.Config;

/**
 * [殖民地经营] Colony Sim（pioneer_colony）——策略经营式抽象模拟模组（06 文档）。
 * 基地运营 = 纯数据公式运算（60s 经济 tick），AI 实体仅作表现层（后续里程碑）。
 */
@Mod(PioneerColony.MODID)
public final class PioneerColony {
    public static final String MODID = "pioneer_colony";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PioneerColony(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        pioneer.colony.registry.ModRegistry.BLOCKS.register(modEventBus);
        pioneer.colony.registry.ModRegistry.ITEMS.register(modEventBus);
        pioneer.colony.registry.ModRegistry.BLOCK_ENTITIES.register(modEventBus);
        pioneer.colony.registry.ModRegistry.MENUS.register(modEventBus);
        pioneer.colony.registry.ModRegistry.CREATIVE_TABS.register(modEventBus);
        pioneer.colony.registry.ModRegistry.ENTITIES.register(modEventBus);
    }
}
