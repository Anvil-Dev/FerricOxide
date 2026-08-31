package dev.anvilcraft.oxide.ferric.client.display;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

/**
 * 网页显示器的渲染状态：仅锚点携带显示参数。
 */
public class WebDisplayRenderState extends BlockEntityRenderState {
    /** 非 null 时本状态对应一个可绘制的大屏（仅锚点）。 */
    public @Nullable Identifier textureId;
    public Direction facing = Direction.NORTH;
    public int width;
    public int height;
}
