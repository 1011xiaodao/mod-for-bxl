package pioneer.colony.item;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import pioneer.colony.construction.ConstructionService;

/**
 * 建筑地基物品：右键放置 → 校验（主世界/自有领地/空地/无重叠）→ 扣料（仓库优先，背包兜底）
 * → 建筑进入施工状态（围挡+倒计时，完成一次成形）。
 */
public class FoundationItem extends Item {
    private final String definitionId;

    public FoundationItem(String definitionId, Properties properties) {
        super(properties);
        this.definitionId = definitionId;
    }

    public String definitionId() {
        return definitionId;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        BlockPos placePos = context.getClickedPos().relative(context.getClickedFace());
        if (player == null || !(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        ConstructionService.OpResult result = ConstructionService.placeFoundation(serverLevel, sp, placePos, definitionId, 1, false);
        if (result.success()) {
            if (!sp.getAbilities().instabuild) {
                context.getItemInHand().shrink(1);
            }
        } else {
            sp.displayClientMessage(Component.literal(result.message()), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
