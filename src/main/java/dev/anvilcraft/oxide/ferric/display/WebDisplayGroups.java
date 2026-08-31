package dev.anvilcraft.oxide.ferric.display;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * 组同步工具：组结构变化时把锚点地址广播到所有成员。
 *
 * <p>成员各自持久化最后广播的地址，因此锚点被破坏后剩余组合的新锚点仍持有正确地址。
 * 所有方法仅在服务端调用。
 */
public final class WebDisplayGroups {
    private WebDisplayGroups() {
    }

    /** 位置是否为同朝向的网页显示器。 */
    public static boolean isMember(BlockGetter level, BlockPos pos, Direction facing) {
        return level.getBlockState(pos).getBlock() instanceof WebDisplayBlock
            && level.getBlockState(pos).getValue(WebDisplayBlock.FACING) == facing;
    }

    /** 计算包含某位置的组合（客户端/服务端通用）。 */
    public static WebDisplayGroup.@Nullable Group computeAt(BlockGetter level, BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof WebDisplayBlock)) {
            return null;
        }
        Direction facing = level.getBlockState(pos).getValue(WebDisplayBlock.FACING);
        return WebDisplayGroup.compute(pos, facing, p -> isMember(level, p, facing));
    }

    /**
     * 重新计算包含 {@code pos} 的组合，并把锚点地址广播到全部成员（服务端）。
     *
     * <p>非矩形区域会按最大矩形划分成多个组；仅当连通组件超出扫描上限时不做同步，
     * 成员各自保留自己的地址。
     */
    public static void syncGroupAt(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            return;
        }
        WebDisplayGroup.Group group = computeAt(level, pos);
        if (group == null) {
            return;
        }
        if (level.getBlockEntity(group.anchor()) instanceof WebDisplayBlockEntity anchorEntity) {
            String url = anchorEntity.getUrl();
            for (BlockPos member : group.members()) {
                if (!member.equals(group.anchor())
                    && level.getBlockEntity(member) instanceof WebDisplayBlockEntity memberEntity
                    && !memberEntity.getUrl().equals(url)) {
                    memberEntity.setUrlAndSync(url);
                }
            }
        }
    }

    /**
     * 方块放置/变更后调用：以变更点为中心同步组合。
     */
    public static void onMemberChanged(Level level, BlockPos pos) {
        syncGroupAt(level, pos);
    }

    /**
     * 方块被移除后调用：原组合的每个平面邻居可能各自裂变成独立组合，逐一同步。
     */
    public static void onMemberRemoved(Level level, BlockPos pos, Direction facing) {
        Direction right = facing.getCounterClockWise();
        for (Direction direction : new Direction[]{right, right.getOpposite(), Direction.UP, Direction.DOWN}) {
            syncGroupAt(level, pos.relative(direction));
        }
    }

    /**
     * 处理编辑请求（服务端）：以玩家点击的方块为基准重算组合，地址写入锚点并广播。
     */
    public static void applyUrlEdit(Level level, BlockPos clicked, String url) {
        if (level.isClientSide()) {
            return;
        }
        WebDisplayGroup.Group group = computeAt(level, clicked);
        BlockPos anchor = group != null ? group.anchor() : clicked;
        if (level.getBlockEntity(anchor) instanceof WebDisplayBlockEntity entity) {
            entity.setUrlAndSync(url);
        }
        if (group != null) {
            syncGroupAt(level, anchor);
        }
    }
}
