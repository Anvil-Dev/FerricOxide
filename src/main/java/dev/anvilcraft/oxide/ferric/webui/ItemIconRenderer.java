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
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Renders {@link ItemStack}s into PNG icons for the webview.
 *
 * <p>Requests are queued from any thread and executed on the render thread during the
 * {@link RenderFrameEvent.Post} window (after Minecraft has submitted its own frame). The item
 * is drawn into a private off-screen texture — reusing the GUI item pipeline that
 * {@code GuiItemAtlas} uses — then read back synchronously via {@code glReadPixels} and encoded
 * as PNG. Missing or unrenderable items resolve to {@code null} (HTTP 404 upstream).
 */
final class ItemIconRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Packed light coordinates used by {@code GuiItemAtlas.drawToSlot}. */
    private static final int PACKED_LIGHT = 15728880;
    static final int DEFAULT_SIZE = 32;
    static final int MIN_SIZE = 8;
    static final int MAX_SIZE = 256;

    private final ItemModelResolver modelResolver;
    private final SubmitNodeCollector submitNodeCollector;
    private final FeatureRenderDispatcher featureRenderDispatcher;
    private final MultiBufferSource.BufferSource bufferSource;
    private final Projection projection = new Projection();
    private final ProjectionMatrixBuffer projectionMatrixBuffer = new ProjectionMatrixBuffer("ferric_oxide_item");
    private final PoseStack poseStack = new PoseStack();
    /** Render-thread only; drained in {@link #onRenderFrame}. */
    private final Queue<Task> pending = new ArrayDeque<>();

    private record Task(Item item, int size, long requestId) {}

    ItemIconRenderer() {
        Minecraft mc = Minecraft.getInstance();
        this.modelResolver = mc.getItemModelResolver();
        // Internals exposed via access transformer (META-INF/accesstransformer.cfg).
        GuiRenderer guiRenderer = mc.gameRenderer.guiRenderer;
        this.submitNodeCollector = guiRenderer.submitNodeCollector;
        this.featureRenderDispatcher = guiRenderer.featureRenderDispatcher;
        this.bufferSource = guiRenderer.bufferSource;
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, this::onRenderFrame);
    }

    /** Queues an icon render; the result is handed back through {@link NativeWebView#respondResource}. */
    void enqueue(Item item, int size, long requestId) {
        pending.add(new Task(item, size, requestId));
    }

    private void onRenderFrame(RenderFrameEvent.Post event) {
        Task task;
        while ((task = pending.poll()) != null) {
            byte[] png = renderIcon(task.item(), task.size());
            NativeWebView.respondResource(task.requestId(), png, "image/png");
        }
    }

    private @Nullable byte[] renderIcon(Item item, int size) {
        try {
            ItemStack stack = item.getDefaultInstance();
            ItemStackRenderState state = new ItemStackRenderState();
            modelResolver.updateForTopItem(state, stack, ItemDisplayContext.GUI, null, null, 0);
            if (state.isEmpty()) {
                LOGGER.warn("Cannot render item icon for '{}': empty render state", item);
                return null;
            }

            GpuDevice device = RenderSystem.getDevice();
            GpuTexture color = device.createTexture(
                "ferric_oxide_item_color",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC,
                TextureFormat.RGBA8,
                size,
                size,
                1,
                1
            );
            GpuTextureView colorView = device.createTextureView(color);
            GpuTexture depth = device.createTexture(
                "ferric_oxide_item_depth",
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
                drawItem(state, colorView, depthView, size);
                return readPixels(color, size);
            } finally {
                color.close();
                colorView.close();
                depth.close();
                depthView.close();
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to render item icon for '{}'", item, e);
            return null;
        }
    }

    /** Draws the item into the given texture, mirroring {@code GuiItemAtlas.drawToSlot}. */
    private void drawItem(ItemStackRenderState state, GpuTextureView colorView, GpuTextureView depthView, int size) {
        poseStack.pushPose();
        poseStack.translate(size / 2.0F, size / 2.0F, 0.0F);
        poseStack.scale(size, -size, size);
        RenderSystem.outputColorTextureOverride = colorView;
        RenderSystem.outputDepthTextureOverride = depthView;
        projection.setupOrtho(-1000.0F, 1000.0F, size, size, true);
        RenderSystem.setProjectionMatrix(projectionMatrixBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
        RenderSystem.enableScissorForRenderTypeDraws(0, 0, size, size);
        Lighting.Entry lighting = state.usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT;
        Minecraft.getInstance().gameRenderer.getLighting().setupFor(lighting);
        state.submit(poseStack, submitNodeCollector, PACKED_LIGHT, OverlayTexture.NO_OVERLAY, 0);
        featureRenderDispatcher.renderAllFeatures();
        bufferSource.endBatch();
        RenderSystem.disableScissorForRenderTypeDraws();
        RenderSystem.outputColorTextureOverride = null;
        RenderSystem.outputDepthTextureOverride = null;
        poseStack.popPose();
    }

    /** Reads back the texture synchronously and encodes it as PNG. */
    private @Nullable byte[] readPixels(GpuTexture color, int size) {
        GpuDevice device = RenderSystem.getDevice();
        long byteSize = (long) size * size * 4L;
        GpuBuffer buffer = device.createBuffer(null, GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, byteSize);
        try {
            // glReadPixels writes the buffer synchronously; the fenced callback is only a
            // completion notification, so the mapped view below is already readable.
            device.createCommandEncoder().copyTextureToBuffer(color, buffer, 0, () -> {}, 0, 0, 0, size, size);
            ByteBuffer data;
            try (GpuBuffer.MappedView view = device.createCommandEncoder().mapBuffer(buffer.slice(), true, false)) {
                data = view.data();
            }
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            // OpenGL rows run bottom-up; flip vertically while copying.
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
            LOGGER.warn("Failed to encode item icon PNG", e);
            return null;
        } finally {
            buffer.close();
        }
    }
}
