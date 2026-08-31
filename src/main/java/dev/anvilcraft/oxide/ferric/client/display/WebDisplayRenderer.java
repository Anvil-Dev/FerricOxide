package dev.anvilcraft.oxide.ferric.client.display;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.anvilcraft.oxide.ferric.display.WebDisplayBlockEntity;
import dev.anvilcraft.oxide.ferric.display.WebDisplayGroup;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 网页显示器渲染器：把离屏 WebView 的纹理绘制为贴合大屏正面的世界内四边形。
 *
 * <p>四边形带深度测试（{@code entitySolid}），因此会被前景方块正确遮挡。只有锚点方块
 * 实体提交几何体；成员不绘制（其正面是白色混凝土方块模型，被四边形覆盖）。
 */
public class WebDisplayRenderer implements BlockEntityRenderer<WebDisplayBlockEntity, WebDisplayRenderState> {
    /** RenderType 按纹理 id 缓存，避免每帧重建。 */
    private static final Map<Identifier, RenderType> RENDER_TYPES = new HashMap<>();

    @Override
    public WebDisplayRenderState createRenderState() {
        return new WebDisplayRenderState();
    }

    /**
     * 渲染包围盒扩到整个组合：默认只有锚点自身 1x1x1，锚点方块一离开视锥，
     * 覆盖整组的大屏四边形就会被整体剔除。走管理器的节流缓存，避免每帧重算。
     */
    @Override
    public AABB getRenderBoundingBox(WebDisplayBlockEntity blockEntity) {
        if (blockEntity.getLevel() != null) {
            WebDisplayGroup.Group group =
                WebDisplayManager.cachedGroupAt(blockEntity.getLevel(), blockEntity.getBlockPos());
            if (group != null && !group.members().isEmpty()) {
                int minX = Integer.MAX_VALUE;
                int minY = Integer.MAX_VALUE;
                int minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE;
                int maxY = Integer.MIN_VALUE;
                int maxZ = Integer.MIN_VALUE;
                for (net.minecraft.core.BlockPos member : group.members()) {
                    minX = Math.min(minX, member.getX());
                    minY = Math.min(minY, member.getY());
                    minZ = Math.min(minZ, member.getZ());
                    maxX = Math.max(maxX, member.getX());
                    maxY = Math.max(maxY, member.getY());
                    maxZ = Math.max(maxZ, member.getZ());
                }
                // inflate 覆盖正面外推的 FACE_OFFSET
                return new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0).inflate(1.0);
            }
        }
        return new AABB(blockEntity.getBlockPos());
    }

    @Override
    public void extractRenderState(
        WebDisplayBlockEntity blockEntity,
        WebDisplayRenderState state,
        float partialTicks,
        Vec3 cameraPosition,
        ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress
    ) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
        if (blockEntity.getLevel() == null) {
            return;
        }
        WebDisplayManager.ActiveDisplay display =
            WebDisplayManager.resolve(blockEntity.getLevel(), blockEntity.getBlockPos());
        // 只有锚点绘制整个大屏
        if (display != null && display.group.anchor().equals(blockEntity.getBlockPos())) {
            state.textureId = display.textureId;
            state.facing = display.group.facing();
            state.width = display.group.width();
            state.height = display.group.height();
            // 屏幕自发光：不受环境光照影响（否则实体渲染会被所在方块格的光照压暗）
            state.lightCoords = LightCoordsUtil.FULL_BRIGHT;
        }
    }

    @Override
    public void submit(
        WebDisplayRenderState state,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        CameraRenderState camera
    ) {
        if (state.textureId == null) {
            return;
        }
        RenderType renderType =
            RENDER_TYPES.computeIfAbsent(state.textureId, RenderTypes::entitySolid);
        Vec3[] corners = WebDisplayGeometry.corners(state.facing, state.width, state.height);
        int light = state.lightCoords;
        float nx = state.facing.getStepX();
        float ny = state.facing.getStepY();
        float nz = state.facing.getStepZ();
        submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
            // 角点顺序（观察者视角）：bottomRight(1,1) bottomLeft(0,1) topLeft(0,0) topRight(1,0)
            // 发射顺序保证从正面看为逆时针（背面剔除安全）
            addVertex(pose, buffer, corners[1], 0, 1, light, nx, ny, nz);
            addVertex(pose, buffer, corners[0], 1, 1, light, nx, ny, nz);
            addVertex(pose, buffer, corners[3], 1, 0, light, nx, ny, nz);
            addVertex(pose, buffer, corners[2], 0, 0, light, nx, ny, nz);
        });
    }

    private static void addVertex(
        PoseStack.Pose pose,
        com.mojang.blaze3d.vertex.VertexConsumer buffer,
        Vec3 pos,
        float u,
        float v,
        int light,
        float nx,
        float ny,
        float nz
    ) {
        buffer.addVertex(pose, (float) pos.x, (float) pos.y, (float) pos.z)
            .setColor(-1)
            .setUv(u, v)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(pose, nx, ny, nz);
    }
}
