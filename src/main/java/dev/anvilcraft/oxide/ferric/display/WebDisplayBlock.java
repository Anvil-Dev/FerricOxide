package dev.anvilcraft.oxide.ferric.display;

import com.mojang.serialization.MapCodec;
import dev.anvilcraft.oxide.ferric.client.display.WebDisplayScreens;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.jspecify.annotations.Nullable;

/**
 * 网页显示器：放置时正面朝向玩家；Shift+右键编辑地址，右键捕获键鼠与网页交互。
 *
 * <p>除正面外所有面使用白色混凝土纹理（正面方块模型同为白色混凝土，网页内容以
 * 世界内几何体的形式覆盖在正面上）。
 */
public class WebDisplayBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<WebDisplayBlock> CODEC = simpleCodec(WebDisplayBlock::new);

    public WebDisplayBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected MapCodec<? extends WebDisplayBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WebDisplayBlockEntity(pos, state);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 正面朝向玩家
        return this.defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }

    @Override
    protected InteractionResult useWithoutItem(
        BlockState state,
        Level level,
        BlockPos pos,
        Player player,
        net.minecraft.world.phys.BlockHitResult hitResult
    ) {
        if (level.isClientSide()) {
            // 客户端类只在客户端加载，此处方法体引用不会被服务端类加载触发
            if (player.isShiftKeyDown()) {
                WebDisplayScreens.openEditScreen(pos);
            } else {
                WebDisplayScreens.openCaptureScreen(pos);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (state.getBlock() != oldState.getBlock()) {
            WebDisplayGroups.onMemberChanged(level, pos);
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(
        BlockState state,
        net.minecraft.server.level.ServerLevel level,
        BlockPos pos,
        boolean movedByPiston
    ) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        WebDisplayGroups.onMemberRemoved(level, pos, state.getValue(FACING));
    }
}
