package dev.anvilcraft.oxide.ferric.client.display;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.nio.ByteBuffer;

/**
 * 网页显示器的动态纹理：RGBA8，内容由离屏 WebView 的 CPU 回读帧填充。
 *
 * <p>与 {@code DynamicTexture} 的区别是上传源是直接 ByteBuffer（离屏帧），
 * 不经过 NativeImage 的逐像素转换。
 */
public final class WebDisplayTexture extends AbstractTexture {
    private final int width;
    private final int height;

    public WebDisplayTexture(String label, int width, int height) {
        this.width = width;
        this.height = height;
        var device = RenderSystem.getDevice();
        // usage 5 = TEXTURE_BINDING | COPY_DST，与 DynamicTexture 一致
        this.texture = device.createTexture(label, 5, TextureFormat.RGBA8, width, height, 1, 1);
        this.sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST);
        this.textureView = device.createTextureView(this.texture);
    }

    /**
     * 上传一整帧 RGBA 像素（行主序、无行距）。
     */
    public void upload(ByteBuffer rgba) {
        if (this.texture == null || rgba.remaining() < this.width * this.height * 4) {
            return;
        }
        RenderSystem.getDevice()
            .createCommandEncoder()
            .writeToTexture(this.texture, rgba, NativeImage.Format.RGBA, 0, 0, 0, 0, this.width, this.height);
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }
}
