package dev.anvilcraft.oxide.ferric.display;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebDisplayGroup} 的纯算法测试：矩形判定、尺寸上限、四朝向锚点选取。
 */
class WebDisplayGroupTest {
    private static Set<BlockPos> rect(BlockPos origin, Direction facing, int width, int height) {
        Direction left = facing.getCounterClockWise().getOpposite();
        Set<BlockPos> cells = new HashSet<>();
        for (int w = 0; w < width; w++) {
            for (int h = 0; h < height; h++) {
                cells.add(origin.relative(left, w).above(h));
            }
        }
        return cells;
    }

    private static WebDisplayGroup.Group compute(Set<BlockPos> cells, BlockPos start, Direction facing) {
        return WebDisplayGroup.compute(start, facing, cells::contains);
    }

    @Test
    void singleBlockIsOwnAnchor() {
        BlockPos pos = new BlockPos(3, 64, -7);
        Set<BlockPos> cells = Set.of(pos);
        WebDisplayGroup.Group group = compute(cells, pos, Direction.NORTH);
        assertNotNull(group);
        assertEquals(1, group.width());
        assertEquals(1, group.height());
        assertEquals(pos, group.anchor());
    }

    @Test
    void fullRectangleIsGroup() {
        Set<BlockPos> cells = rect(new BlockPos(0, 10, 0), Direction.NORTH, 3, 2);
        // 从组内任意位置出发都应得到同一组合
        for (BlockPos start : cells) {
            WebDisplayGroup.Group group = compute(cells, start, Direction.NORTH);
            assertNotNull(group, "start=" + start);
            assertEquals(3, group.width());
            assertEquals(2, group.height());
            assertEquals(cells, group.members());
        }
    }

    @Test
    void anchorIsBottomRightFromViewerPerspective() {
        // 朝北的面（法线 -z）：观察者站在 -z 侧看 +z，其右为西（-x）。
        // 矩形沿观察者的左侧（东，+x）展开，因此最右的成员是 x 最小的原点
        Set<BlockPos> cells = rect(new BlockPos(0, 10, 0), Direction.NORTH, 3, 2);
        WebDisplayGroup.Group group = compute(cells, new BlockPos(0, 10, 0), Direction.NORTH);
        assertNotNull(group);
        assertEquals(new BlockPos(0, 10, 0), group.anchor());

        // 朝东的面（法线 +x）：观察者站在 +x 侧看 -x，其右为北（-z）。
        // 矩形沿南侧（+z）展开，最右的成员是 z 最小的原点
        Set<BlockPos> cellsEast = rect(new BlockPos(5, 20, 5), Direction.EAST, 2, 2);
        WebDisplayGroup.Group groupEast = compute(cellsEast, new BlockPos(5, 20, 5), Direction.EAST);
        assertNotNull(groupEast);
        assertEquals(new BlockPos(5, 20, 5), groupEast.anchor());
    }

    @Test
    void lShapeJoinsLargestRectangle() {
        // 3x2 挖掉右上角（2,11,0）→ L 形；最大矩形是左侧 2x2，突出的格子不属于该组
        Set<BlockPos> cells = rect(new BlockPos(0, 10, 0), Direction.NORTH, 3, 2);
        cells.remove(new BlockPos(2, 11, 0));
        WebDisplayGroup.Group group = compute(cells, new BlockPos(0, 10, 0), Direction.NORTH);
        assertNotNull(group);
        assertEquals(2, group.width());
        assertEquals(2, group.height());
        assertEquals(4, group.members().size());
        assertTrue(group.members().contains(new BlockPos(0, 10, 0)));
        assertTrue(!group.members().contains(new BlockPos(2, 10, 0)), "突出的格子不应并入 2x2 组");

        // 从突出格子出发：组件划分后它只剩 1x1（(2,11) 已删，(2,10) 归它自己）
        WebDisplayGroup.Group protrusion = compute(cells, new BlockPos(2, 10, 0), Direction.NORTH);
        assertNotNull(protrusion);
        assertEquals(1, protrusion.width());
        assertEquals(1, protrusion.height());
        assertEquals(new BlockPos(2, 10, 0), protrusion.anchor());
    }

    @Test
    void holeSplitsIntoLargestRectangles() {
        // 4x3 中间（1,11,0）挖洞：最大矩形是右侧 2x3；x=0 列从起点看是 1x3 竖条
        Set<BlockPos> cells = rect(new BlockPos(0, 10, 0), Direction.NORTH, 4, 3);
        cells.remove(new BlockPos(1, 11, 0));
        WebDisplayGroup.Group fromLeft = compute(cells, new BlockPos(0, 10, 0), Direction.NORTH);
        assertNotNull(fromLeft);
        assertEquals(1, fromLeft.width());
        assertEquals(3, fromLeft.height());

        WebDisplayGroup.Group fromRight = compute(cells, new BlockPos(3, 11, 0), Direction.NORTH);
        assertNotNull(fromRight);
        assertEquals(2, fromRight.width());
        assertEquals(3, fromRight.height());
    }

    @Test
    void tooWideTakesLargestWindow() {
        // 17 宽 → 取最大的 16 宽窗口（确定性：右轴 u 最小一侧优先）
        Set<BlockPos> cells =
            rect(new BlockPos(0, 10, 0), Direction.NORTH, WebDisplayGroup.MAX_WIDTH + 1, 1);
        WebDisplayGroup.Group group = compute(cells, new BlockPos(5, 10, 0), Direction.NORTH);
        assertNotNull(group);
        assertEquals(WebDisplayGroup.MAX_WIDTH, group.width());
        assertEquals(1, group.height());
        assertTrue(group.members().contains(new BlockPos(5, 10, 0)));
    }

    @Test
    void tooTallTakesLargestWindow() {
        // 10 高 → 取底部 9 高窗口
        Set<BlockPos> cells =
            rect(new BlockPos(0, 10, 0), Direction.NORTH, 1, WebDisplayGroup.MAX_HEIGHT + 1);
        WebDisplayGroup.Group group = compute(cells, new BlockPos(0, 10, 0), Direction.NORTH);
        assertNotNull(group);
        assertEquals(1, group.width());
        assertEquals(WebDisplayGroup.MAX_HEIGHT, group.height());
        assertEquals(new BlockPos(0, 10, 0), group.anchor());
    }

    @Test
    void maxSizeIsAccepted() {
        Set<BlockPos> cells =
            rect(new BlockPos(0, 10, 0), Direction.SOUTH, WebDisplayGroup.MAX_WIDTH, WebDisplayGroup.MAX_HEIGHT);
        WebDisplayGroup.Group group = compute(cells, new BlockPos(0, 10, 0), Direction.SOUTH);
        assertNotNull(group);
        assertEquals(WebDisplayGroup.MAX_WIDTH, group.width());
        assertEquals(WebDisplayGroup.MAX_HEIGHT, group.height());
    }

    @Test
    void predicateFiltersWrongFacing() {
        // 谓词模拟“同朝向才算成员”：只认集合内的位置
        Set<BlockPos> north = rect(new BlockPos(0, 10, 0), Direction.NORTH, 2, 1);
        // 紧贴着一个不属于该组的方块（等价于朝向不同）
        Set<BlockPos> cells = new HashSet<>(north);
        cells.add(new BlockPos(2, 10, 0));
        WebDisplayGroup.Group group =
            WebDisplayGroup.compute(new BlockPos(0, 10, 0), Direction.NORTH, north::contains);
        assertNotNull(group);
        assertEquals(2, group.width());
        assertTrue(group.members().stream().allMatch(north::contains));
    }
}
