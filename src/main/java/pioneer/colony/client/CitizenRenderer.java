package pioneer.colony.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import pioneer.colony.PioneerColony;
import pioneer.colony.entity.CitizenEntity;

/** 市民实体渲染（占位皮肤：程序化生成的 64×64 玩家布局贴图，美术后置替换）。 */
public class CitizenRenderer extends HumanoidMobRenderer<CitizenEntity, HumanoidModel<CitizenEntity>> {
    private static final ResourceLocation SKIN =
            ResourceLocation.fromNamespaceAndPath(PioneerColony.MODID, "textures/entity/citizen.png");

    public CitizenRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(CitizenEntity entity) {
        return SKIN;
    }
}
