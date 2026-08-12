package dev.anvilcraft.oxide.ferric.webui;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Resolves {@code ferric://<namespace>/<path>} requests against Minecraft's resource manager.
 *
 * <p>{@link #resolve} is invoked on the native UI thread; the actual lookup is marshaled to the
 * render thread (where the resource manager is safe to touch) and the bytes are handed back
 * through {@link NativeWebView#respondResource}. Missing resources answer 404.
 */
final class MinecraftResourceHandler implements ResourceHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void resolve(String location, long requestId) {
        LOGGER.debug("Webview requests resource '{}' (request {})", location, requestId);
        // The resource manager must be touched on the render thread.
        WebUi.onRenderThread(() -> {
            @Nullable byte[] bytes = null;
            @Nullable String mime = null;
            try {
                @Nullable Identifier id = Identifier.tryParse(location);
                if (id == null) {
                    LOGGER.warn("Webview requested malformed resource location '{}'", location);
                    NativeWebView.respondResource(requestId, null, null);
                    return;
                }
                Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(id);
                if (resource.isEmpty()) {
                    LOGGER.warn("Webview requested missing resource '{}'", location);
                    NativeWebView.respondResource(requestId, null, null);
                    return;
                }
                try (InputStream in = resource.get().open()) {
                    bytes = in.readAllBytes();
                }
                mime = mimeTypeForPath(id.getPath());
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Failed to resolve webview resource '{}'", location, e);
                bytes = null;
            }
            NativeWebView.respondResource(requestId, bytes, mime);
        });
    }

    private static String mimeTypeForPath(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "css" -> "text/css";
            case "js", "mjs" -> "text/javascript";
            case "html", "htm" -> "text/html";
            case "json" -> "application/json";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            case "txt" -> "text/plain";
            default -> "application/octet-stream";
        };
    }
}
