package pioneer.colony.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 施工围挡（警示条纹占位）：施工期间环绕建筑 footprint 摆放，完成/拆除时由核心方块逻辑统一清除。
 * 不参与掉落与合成；玩家不可破坏（受领地/footprint 保护覆盖）。
 */
public class ConstructionBarrierBlock extends Block {
    /** 施工围挡为纯装饰占位，无状态属性。 */
    public ConstructionBarrierBlock(Properties properties) {
        super(properties);
    }
}
