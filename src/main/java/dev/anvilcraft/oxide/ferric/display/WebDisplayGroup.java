package dev.anvilcraft.oxide.ferric.display;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 网页显示器的多方块组合判定：相邻且朝向相同的显示器组合为矩形，
 * 单个矩形最大 {@value #MAX_WIDTH}（宽）× {@value #MAX_HEIGHT}（高）。
 *
 * <p>非矩形的连通区域不会整体失效：算法对连通组件做<b>确定性的贪心最大矩形划分</b>——
 * 反复取出当前剩余区域中面积最大的矩形（宽高受限），直到耗尽。划分只取决于组件形状，
 * 与从哪个方块开始计算无关，因此任意成员都会得到一致的分组结果。
 *
 * <p>纯算法类，不直接访问世界；通过谓词查询“某位置是否为本组显示器”（同方块、同朝向），
 * 因此可以用普通单元测试覆盖。
 *
 * <p>坐标约定：观察者面对显示器正面时，右方向为 {@code facing.getCounterClockWise()}；
 * 锚点（数据权威方块）为观察者视角的最右下，即右轴坐标最大、Y 最小的方块。
 */
public final class WebDisplayGroup {
    /** 大屏最大宽度（方块数）。 */
    public static final int MAX_WIDTH = 16;
    /** 大屏最大高度（方块数）。 */
    public static final int MAX_HEIGHT = 9;
    /** 连通组件扫描上限（格数），超出则放弃组合（防御异常大范围）。 */
    private static final int MAX_SCAN = 256;

    private WebDisplayGroup() {
    }

    /**
     * 一个有效的大屏组合。
     *
     * @param facing  正面朝向（水平方向）
     * @param anchor  锚点（观察者视角最右下）位置
     * @param width   宽（方块数）
     * @param height  高（方块数）
     * @param members 组内全部成员位置（含锚点）
     */
    public record Group(Direction facing, BlockPos anchor, int width, int height, Set<BlockPos> members) {
        public boolean isMember(BlockPos pos) {
            return this.members.contains(pos);
        }
    }

    /** 平面矩形（u = 右轴坐标，v = Y 坐标），闭区间。 */
    private record Rect(int minU, int minV, int maxU, int maxV) {
        int area() {
            return (this.maxU - this.minU + 1) * (this.maxV - this.minV + 1);
        }

        boolean contains(int u, int v) {
            return u >= this.minU && u <= this.maxU && v >= this.minV && v <= this.maxV;
        }
    }

    /**
     * 计算包含 {@code start} 的大屏组合。
     *
     * @param start    组内任一方块位置
     * @param facing   正面朝向
     * @param isMember 谓词：位置是否为同朝向的网页显示器
     * @return start 所属的矩形组合（单块组始终有效并以自身为锚点）；组件超出扫描上限时返回 {@code null}
     */
    public static @Nullable Group compute(BlockPos start, Direction facing, Predicate<BlockPos> isMember) {
        if (!isMember.test(start)) {
            return null;
        }
        Direction right = facing.getCounterClockWise();
        Direction left = right.getOpposite();
        // 1. 沿正面平面四方向洪水填充，收集连通组件
        Set<BlockPos> component = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        component.add(start);
        queue.add(start);
        Direction[] plane = {right, left, Direction.UP, Direction.DOWN};
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            for (Direction direction : plane) {
                BlockPos next = pos.relative(direction);
                if (!component.contains(next) && isMember.test(next)) {
                    component.add(next);
                    queue.add(next);
                    if (component.size() > MAX_SCAN) {
                        return null;
                    }
                }
            }
        }

        // 2. 投影到（右轴 u, Y v）平面并建立填充网格
        int startU = axisCoord(start, right);
        int minU = Integer.MAX_VALUE;
        int maxU = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos member : component) {
            int u = axisCoord(member, right);
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minY = Math.min(minY, member.getY());
            maxY = Math.max(maxY, member.getY());
        }
        int gridWidth = maxU - minU + 1;
        int gridHeight = maxY - minY + 1;
        boolean[][] filled = new boolean[gridHeight][gridWidth];
        for (BlockPos member : component) {
            filled[member.getY() - minY][axisCoord(member, right) - minU] = true;
        }

        // 3. 贪心最大矩形划分：反复取剩余区域中面积最大的受限矩形
        boolean[][] remaining = new boolean[gridHeight][gridWidth];
        for (int v = 0; v < gridHeight; v++) {
            System.arraycopy(filled[v], 0, remaining[v], 0, gridWidth);
        }
        Rect containingStart = null;
        int startV = start.getY() - minY;
        int startGridU = startU - minU;
        for (;;) {
            Rect best = largestRectangle(remaining);
            if (best == null) {
                break;
            }
            for (int v = best.minV(); v <= best.maxV(); v++) {
                for (int u = best.minU(); u <= best.maxU(); u++) {
                    remaining[v][u] = false;
                }
            }
            if (best.contains(startGridU, startV)) {
                containingStart = best;
            }
        }
        if (containingStart == null) {
            // 不可达：start 必然属于某个 1×1 矩形
            return null;
        }

        // 4. 矩形 → 组：锚点 = 观察者视角最右下（u 最大、v 最小）
        Set<BlockPos> members = new HashSet<>();
        for (int v = containingStart.minV(); v <= containingStart.maxV(); v++) {
            for (int u = containingStart.minU(); u <= containingStart.maxU(); u++) {
                members.add(posAt(start, right, startU, minU + u, minY + v));
            }
        }
        BlockPos anchor = posAt(start, right, startU, minU + containingStart.maxU(), minY + containingStart.minV());
        return new Group(
            facing, anchor,
            containingStart.maxU() - containingStart.minU() + 1,
            containingStart.maxV() - containingStart.minV() + 1,
            Set.copyOf(members)
        );
    }

    /**
     * 在剩余填充网格中找面积最大的矩形（宽 ≤ {@value #MAX_WIDTH}、高 ≤ {@value #MAX_HEIGHT}）。
     * 平局时取 v 最小、其次 u 最小者，保证与起点无关的确定性。没有剩余格时返回 {@code null}。
     */
    private static @Nullable Rect largestRectangle(boolean[][] remaining) {
        int gridHeight = remaining.length;
        int gridWidth = remaining[0].length;
        // 每轮划分后网格有变化，重建剩余格的前缀和（网格 ≤ 256 格，开销可忽略）
        int[][] prefix = new int[gridHeight + 1][gridWidth + 1];
        for (int v = 0; v < gridHeight; v++) {
            for (int u = 0; u < gridWidth; u++) {
                prefix[v + 1][u + 1] =
                    prefix[v][u + 1] + prefix[v + 1][u] - prefix[v][u] + (remaining[v][u] ? 1 : 0);
            }
        }
        Rect best = null;
        int bestArea = 0;
        for (int v1 = 0; v1 < gridHeight; v1++) {
            for (int v2 = v1; v2 < Math.min(gridHeight, v1 + MAX_HEIGHT); v2++) {
                int height = v2 - v1 + 1;
                for (int u1 = 0; u1 < gridWidth; u1++) {
                    for (int u2 = u1; u2 < Math.min(gridWidth, u1 + MAX_WIDTH); u2++) {
                        int area = (u2 - u1 + 1) * height;
                        if (area <= bestArea) {
                            continue;
                        }
                        int sum = prefix[v2 + 1][u2 + 1] - prefix[v1][u2 + 1]
                            - prefix[v2 + 1][u1] + prefix[v1][u1];
                        if (sum != area) {
                            continue;
                        }
                        bestArea = area;
                        best = new Rect(u1, v1, u2, v2);
                    }
                }
            }
        }
        return best;
    }

    /**
     * 由（u, v）平面坐标还原世界坐标：以 start 为基准沿右轴与 Y 轴平移。
     */
    private static BlockPos posAt(BlockPos start, Direction right, int startU, int u, int y) {
        return start.relative(right, u - startU).atY(y);
    }

    /**
     * 位置在右轴上的坐标（右轴是单位轴向量，取点积即可）。
     */
    private static int axisCoord(BlockPos pos, Direction axis) {
        return pos.getX() * axis.getStepX() + pos.getZ() * axis.getStepZ();
    }
}
