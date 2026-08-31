package dev.anvilcraft.oxide.ferric.client.display;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 大屏正面四边形的世界空间几何计算。
 *
 * <p>渲染器与捕获屏共用同一套角点定义：以锚点方块角落为原点，正面平面沿朝向外推
 * {@value #FACE_OFFSET} 以防止 z-fighting。
 */
public final class WebDisplayGeometry {
    /** 正面外推量（方块）。 */
    public static final double FACE_OFFSET = 0.002;

    private WebDisplayGeometry() {
    }

    /** 观察者视角的右方向。 */
    public static Direction rightOf(Direction facing) {
        return facing.getCounterClockWise();
    }

    /**
     * 计算大屏正面四角（以锚点方块角落为原点的相对坐标）。
     *
     * @return [bottomRight, bottomLeft, topLeft, topRight]（观察者视角）
     */
    public static Vec3[] corners(Direction facing, int width, int height) {
        Direction right = rightOf(facing);
        Vec3 f = facing.getUnitVec3();
        Vec3 r = right.getUnitVec3();
        // 正面平面位于朝向侧的方块边界，再向外推 FACE_OFFSET
        Vec3 faceOffset = new Vec3(Math.max(0, f.x), Math.max(0, f.y), Math.max(0, f.z))
            .add(f.scale(FACE_OFFSET));
        // 锚点是观察者视角最右下：其右边缘在右轴正方向的远端
        Vec3 bottomRight = new Vec3(Math.max(0, r.x), 0, Math.max(0, r.z)).add(faceOffset);
        Vec3 bottomLeft = bottomRight.add(r.scale(-width));
        Vec3 topLeft = bottomLeft.add(0, height, 0);
        Vec3 topRight = bottomRight.add(0, height, 0);
        return new Vec3[]{bottomRight, bottomLeft, topLeft, topRight};
    }

    /**
     * 计算四角的世界坐标（锚点绝对位置 + 相对角点）。
     */
    public static Vec3[] worldCorners(BlockPos anchor, Direction facing, int width, int height) {
        Vec3 base = Vec3.atLowerCornerOf(anchor);
        Vec3[] local = corners(facing, width, height);
        for (int i = 0; i < local.length; i++) {
            local[i] = local[i].add(base);
        }
        return local;
    }
}
