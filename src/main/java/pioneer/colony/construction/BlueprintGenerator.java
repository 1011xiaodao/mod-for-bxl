package pioneer.colony.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import pioneer.colony.colony.BuildingDefinition;

/**
 * 程序化结构蓝图 v1（06 §3.2 定稿：殖民舱风格框架 + 功能方块占位）。
 * 地板抛光安山岩、铁块四角柱、浅灰混凝土墙+玻璃窗带、玻璃穹顶、室内吊灯与职业功能方块。
 * 正式蓝图（结构方块/蓝图工具）由项目主后续替换，不阻塞开发。
 */
public final class BlueprintGenerator {
    private BlueprintGenerator() {
    }

    /** 按定义与等级整栋一次成形（origin = 逻辑核心方块位置，位于地板层）。 */
    public static void materialize(ServerLevel level, BuildingDefinition def, int tier, BlockPos origin) {
        int w = def.footprintW();
        int h = def.footprintH();
        int d = def.footprintD();
        clearArea(level, origin, w, h, d, false);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // 地板（跳过核心位）
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                if (x != 0 || z != 0) {
                    set(level, cursor.set(origin.getX() + x, origin.getY(), origin.getZ() + z), Blocks.POLISHED_ANDESITE.defaultBlockState());
                }
            }
        }
        // 四角立柱（铁块）
        for (int y = 1; y < h; y++) {
            int[][] corners = {{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}};
            for (int[] c : corners) {
                set(level, cursor.set(origin.getX() + c[0], origin.getY() + y, origin.getZ() + c[1]), Blocks.IRON_BLOCK.defaultBlockState());
            }
        }
        // 墙体（浅灰混凝土）+ 窗带（玻璃）+ 南向门洞
        int wallTop = h - 1;
        for (int y = 1; y <= wallTop; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    boolean perimeter = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                    if (!perimeter) {
                        continue;
                    }
                    boolean corner = (x == 0 || x == w - 1) && (z == 0 || z == d - 1);
                    if (corner) {
                        continue;
                    }
                    boolean southWall = z == d - 1;
                    boolean doorway = southWall && y <= 2 && x == w / 2;
                    boolean window = y == Math.max(2, wallTop - 2) && (x % 2 == 1 || z % 2 == 1) && !southWall;
                    BlockState wall = window ? Blocks.GLASS.defaultBlockState() : Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
                    set(level, cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z), doorway ? Blocks.AIR.defaultBlockState() : wall);
                }
            }
        }
        // 穹顶（玻璃）+ 铁块边框
        int roofY = h;
        for (int x = -1; x <= w; x++) {
            for (int z = -1; z <= d; z++) {
                boolean frame = x == -1 || z == -1 || x == w || z == d || x == 0 || z == 0 || x == w - 1 || z == d - 1;
                set(level, cursor.set(origin.getX() + x, origin.getY() + roofY, origin.getZ() + z),
                        frame ? Blocks.IRON_BLOCK.defaultBlockState() : Blocks.GLASS.defaultBlockState());
            }
        }
        // 室内：清空 + 吊灯（屋顶下中央）
        for (int x = 1; x < w - 1; x++) {
            for (int y = 1; y < wallTop; y++) {
                for (int z = 1; z < d - 1; z++) {
                    set(level, cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        BlockPos lanternPos = origin.offset(w / 2, h - 1, d / 2);
        set(level, lanternPos, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, Boolean.TRUE));
        // 职业功能方块（占位）
        BlockPos workPos = origin.offset(w / 2, 1, d / 2);
        set(level, workPos, professionBlock(def.profession()));
    }

    private static BlockState professionBlock(String profession) {
        if (profession == null) {
            return Blocks.BARREL.defaultBlockState();
        }
        return switch (profession) {
            case "farmer" -> Blocks.COMPOSTER.defaultBlockState();
            case "cook" -> Blocks.SMOKER.defaultBlockState();
            case "technician" -> Blocks.BLAST_FURNACE.defaultBlockState();
            case "scientist" -> Blocks.LECTERN.defaultBlockState();
            case "guard" -> Blocks.BELL.defaultBlockState();
            case "stoker" -> Blocks.FURNACE.defaultBlockState();
            default -> Blocks.BARREL.defaultBlockState();
        };
    }

    /**
     * 清空区域（含外墙与穹顶范围；includeCore=true 时连逻辑核心方块一起移除）。
     * 不动地板下方的地形（y 从 0 起）；围挡环由 removeBarriers 单独清理。
     */
    public static void clearArea(ServerLevel level, BlockPos origin, int w, int h, int d, boolean includeCore) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -1; x <= w; x++) {
            for (int y = 0; y <= h + 1; y++) {
                for (int z = -1; z <= d; z++) {
                    boolean corePos = x == 0 && y == 0 && z == 0;
                    if (corePos && !includeCore) {
                        continue;
                    }
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (!level.getBlockState(cursor).isAir()) {
                        level.removeBlockEntity(cursor.immutable());
                        level.setBlock(cursor.immutable(), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        if (!level.getBlockState(pos).equals(state)) {
            level.setBlock(pos, state, 3);
        }
    }
}
