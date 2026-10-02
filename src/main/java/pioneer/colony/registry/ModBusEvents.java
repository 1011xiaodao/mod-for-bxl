package pioneer.colony.registry;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import pioneer.colony.PioneerColony;
import pioneer.colony.entity.CitizenEntity;

/** 模组总线事件（common）：实体属性注册。 */
@EventBusSubscriber(modid = PioneerColony.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModBusEvents {
    private ModBusEvents() {
    }

    @SubscribeEvent
    public static void onRegisterAttributes(EntityAttributeCreationEvent event) {
        event.put(ModRegistry.CITIZEN_ENTITY.get(), CitizenEntity.createAttributes().build());
    }
}
