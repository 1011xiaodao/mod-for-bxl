package pioneer.colony.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import pioneer.colony.blockentity.ColonyCoreBlockEntity;
import pioneer.colony.registry.ModRegistry;

import javax.annotation.Nullable;

/** 建筑地基/逻辑核心方块：右键打开核心菜单（建筑信息/收取/升级/拆除；总部含领地与城市管理）。 */
public class ColonyCoreBlock extends BaseEntityBlock {
    public static final MapCodec<ColonyCoreBlock> CODEC = simpleCodec(ColonyCoreBlock::new);

    public ColonyCoreBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ColonyCoreBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, ModRegistry.COLONY_CORE_BE.get(), ColonyCoreBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof ColonyCoreBlockEntity core)) {
            return InteractionResult.PASS;
        }
        MenuProvider provider = core;
        if (player instanceof ServerPlayer sp) {
            // 开 GUI 权限：总督本人或可信（06 §4 两档权限）
            if (!core.canPlayerOpen(sp)) {
                sp.displayClientMessage(Component.literal("需要「可信」权限才能操作该建筑 GUI。"), true);
                return InteractionResult.CONSUME;
            }
            sp.openMenu(provider, pos);
        }
        return InteractionResult.CONSUME;
    }
}
