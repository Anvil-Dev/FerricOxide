package dev.anvilcraft.oxide.ferric.webui;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Renders a {@link LivingEntity} preview into the webview, mirroring the inventory screen's
 * entity preview (rotatable by the mouse).
 *
 * <p>The first frame is produced synchronously through {@link #renderFrame()} in response to a
 * {@code ferric://entity/...} request. Subsequent frames are driven by mouse rotation: the page
 * posts {@code ferric_oxide.entity_rotate} IPC messages with the angles, the render thread marks
 * the preview dirty, and {@link RenderFrameEvent.Post} re-renders at most once per frame, handing
 * the PNG to the consumer registered via {@link #setPushConsumer}. Render-thread only.
 */
public final class EntityPreviewRenderer {
    static final int DEFAULT_SIZE = 128;
    static final int MIN_SIZE = 32;
    static final int MAX_SIZE = 512;
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Receives each freshly rendered PNG frame (host pushes it to the page as a data URL).
     */
    private static @Nullable Consumer<byte[]> pushConsumer;
    /**
     * Render-thread singleton; {@link #prepare} attaches the current preview entity.
     */
    private static @Nullable EntityPreviewRenderer instance;

    private final EntityRenderDispatcher entityRenderDispatcher;
    private final FeatureRenderDispatcher featureRenderDispatcher;
    private final MultiBufferSource.BufferSource bufferSource;
    private final Projection projection = new Projection();
    private final ProjectionMatrixBuffer projectionMatrixBuffer = new ProjectionMatrixBuffer("ferric_oxide_entity");
    private final PoseStack poseStack = new PoseStack();

    /**
     * Render-thread state; {@code null} while stopped.
     */
    private @Nullable EntityRenderState renderState;
    private int size = DEFAULT_SIZE;
    /**
     * Horizontal/vertical angles in the inventory formula's radians (atan result).
     */
    private float yaw;
    private float pitch;
    private boolean dirty;

    EntityPreviewRenderer() {
        Minecraft mc = Minecraft.getInstance();
        this.entityRenderDispatcher = mc.getEntityRenderDispatcher();
        this.featureRenderDispatcher = mc.gameRenderer.getFeatureRenderDispatcher();
        // Internals exposed via access transformer (META-INF/accesstransformer.cfg).
        GuiRenderer guiRenderer = mc.gameRenderer.guiRenderer;
        this.bufferSource = guiRenderer.bufferSource;
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, this::onRenderFrame);
    }

    /**
     * Registers the consumer that receives every rendered frame (host pushes it to the page).
     */
    public static void setPushConsumer(@Nullable Consumer<byte[]> consumer) {
        pushConsumer = consumer;
    }

    /**
     * The render-thread singleton, created on first use.
     */
    public static EntityPreviewRenderer instance() {
        EntityPreviewRenderer current = instance;
        if (current == null) {
            current = new EntityPreviewRenderer();
            instance = current;
        }
        return current;
    }

    /**
     * Whether a preview entity is currently attached.
     */
    public static boolean isActive() {
        EntityPreviewRenderer current = instance;
        return current != null && current.renderState != null;
    }

    /**
     * Updates the rotation angles of the active preview (render thread only).
     */
    public static void updateRotation(float yaw, float pitch) {
        EntityPreviewRenderer current = instance;
        if (current != null) {
            current.updateRotationInternal(yaw, pitch);
        }
    }

    /**
     * Stops the active preview; further frames are suppressed (render thread only).
     */
    public static void stop() {
        EntityPreviewRenderer current = instance;
        if (current != null) {
            current.stopInternal();
        }
    }

    /**
     * Reads back the texture synchronously and encodes it as PNG (same as item icons).
     */
    private static byte @Nullable [] readPixels(GpuTexture color, int size) {
        GpuDevice device = RenderSystem.getDevice();
        long byteSize = (long) size * size * 4L;
        GpuBuffer buffer = device.createBuffer(null, GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, byteSize);
        try {
            device.createCommandEncoder().copyTextureToBuffer(
                color, buffer, 0, () -> {
                }, 0, 0, 0, size, size
            );
            ByteBuffer data;
            try (GpuBuffer.MappedView view = device.createCommandEncoder().mapBuffer(buffer.slice(), true, false)) {
                data = view.data();
            }
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < size; y++) {
                int srcRow = size - 1 - y;
                data.position(srcRow * size * 4);
                for (int x = 0; x < size; x++) {
                    int r = data.get() & 0xFF;
                    int g = data.get() & 0xFF;
                    int b = data.get() & 0xFF;
                    int a = data.get() & 0xFF;
                    image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            LOGGER.warn("Failed to encode entity preview PNG", e);
            return null;
        } finally {
            buffer.close();
        }
    }

    /**
     * Extracts the render state for the given entity; render thread only.
     */
    void prepare(LivingEntity entity, int size) {
        EntityRenderState state = entityRenderDispatcher.extractEntity(entity, 1.0F);
        state.shadowPieces.clear();
        state.outlineColor = 0;
        // Protocol-created entities have no meaningful world position. Their extracted light
        // would otherwise come from the default coordinates, often producing an almost black GUI preview.
        state.lightCoords = 0x00F000F0;
        this.renderState = state;
        this.size = Math.clamp(size, MIN_SIZE, MAX_SIZE);
        this.dirty = true;
    }

    /**
     * Updates the rotation angles (inventory formula radians); render thread only.
     */
    private void updateRotationInternal(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.dirty = true;
    }

    /**
     * Stops further frames; render thread only.
     */
    private void stopInternal() {
        this.renderState = null;
        this.dirty = false;
    }

    private void onRenderFrame(RenderFrameEvent.Post event) {
        if (!dirty || renderState == null) {
            return;
        }
        dirty = false;
        byte[] png = renderFrame();
        Consumer<byte[]> consumer = pushConsumer;
        if (png != null && consumer != null) {
            consumer.accept(png);
        }
    }

    /**
     * Renders the current preview state to a PNG; render thread only. Returns null on failure.
     */
    byte @Nullable [] renderFrame() {
        EntityRenderState state = this.renderState;
        if (state == null) {
            return null;
        }
        int size = this.size;
        try {
            // Inventory-screen formula: model rotation is rotateZ(pi) * rotateX(pitch*20deg);
            // the camera angle override is the X rotation.
            Quaternionf xRotation = new Quaternionf().rotateX(this.pitch * 20.0F * (float) (Math.PI / 180.0));
            Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).mul(xRotation);
            if (state instanceof LivingEntityRenderState living) {
                living.bodyRot = 180.0F + this.yaw * 20.0F;
                living.yRot = this.yaw * 20.0F;
                living.xRot = living.pose != Pose.FALL_FLYING ? -this.pitch * 20.0F : 0.0F;
                living.boundingBoxWidth = living.boundingBoxWidth / living.scale;
                living.boundingBoxHeight = living.boundingBoxHeight / living.scale;
                living.scale = 1.0F;
            }

            GpuDevice device = RenderSystem.getDevice();
            GpuTexture color = device.createTexture(
                "ferric_oxide_entity_color",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC,
                TextureFormat.RGBA8,
                size,
                size,
                1,
                1
            );
            GpuTextureView colorView = device.createTextureView(color);
            GpuTexture depth = device.createTexture(
                "ferric_oxide_entity_depth",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST,
                TextureFormat.DEPTH32,
                size,
                size,
                1,
                1
            );
            GpuTextureView depthView = device.createTextureView(depth);
            try {
                device.createCommandEncoder().clearColorAndDepthTextures(color, 0, depth, 1.0);
                poseStack.pushPose();
                try {
                    // Match GuiEntityRenderer: center the preview, scale it to fit the texture,
                    // and invert Z. The protocol size is the output size, not the inventory's
                    // independent model-scale argument, so derive a scale from the entity bounds.
                    poseStack.translate(size / 2.0F, size / 2.0F, 0.0F);
                    float modelScale = size * 0.9F / Math.max(state.boundingBoxWidth, state.boundingBoxHeight);
                    poseStack.scale(modelScale, modelScale, -modelScale);
                    Vector3f translation = new Vector3f(0.0F, state.boundingBoxHeight / 2.0F, 0.0F);
                    poseStack.translate(translation.x, translation.y, translation.z);
                    poseStack.mulPose(rotation);
                    RenderSystem.outputColorTextureOverride = colorView;
                    RenderSystem.outputDepthTextureOverride = depthView;
                    projection.setupOrtho(-1000.0F, 1000.0F, size, size, true);
                    RenderSystem.setProjectionMatrix(projectionMatrixBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
                    Minecraft.getInstance().gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
                    CameraRenderState cameraRenderState = new CameraRenderState();
                    cameraRenderState.orientation = xRotation.conjugate(new Quaternionf()).rotateY((float) Math.PI);
                    entityRenderDispatcher.submit(
                        state, cameraRenderState, 0.0, 0.0, 0.0, poseStack, featureRenderDispatcher.getSubmitNodeStorage()
                    );
                    featureRenderDispatcher.renderAllFeatures();
                    bufferSource.endBatch();
                    return readPixels(color, size);
                } finally {
                    RenderSystem.outputColorTextureOverride = null;
                    RenderSystem.outputDepthTextureOverride = null;
                    poseStack.popPose();
                }
            } finally {
                color.close();
                colorView.close();
                depth.close();
                depthView.close();
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to render entity preview", e);
            return null;
        }
    }
}
