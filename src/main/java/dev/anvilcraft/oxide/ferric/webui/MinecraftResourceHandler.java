package dev.anvilcraft.oxide.ferric.webui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves {@code ferric://<namespace>/<path>} requests against Minecraft's resource manager,
 * and {@code ferric://item/<注册名>?size=N} requests by rendering the item's GUI icon.
 *
 * <p>{@link #resolve} is invoked on the native UI thread; the actual lookup is marshaled to the
 * render thread (where the resource manager is safe to touch) and the bytes are handed back
 * through {@link NativeWebView#respondResource}. Missing resources answer 404.
 */
final class MinecraftResourceHandler implements ResourceHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ITEM_PREFIX = "item:";
    private static final String ENTITY_PREFIX = "entity:";
    /**
     * Render-thread-safe; created lazily on the first item/entity request.
     */
    private static @Nullable ItemIconRenderer itemIconRenderer;
    private static @Nullable EntityPreviewRenderer entityPreviewRenderer;

    /**
     * Looks up a living entity by name, or the local player for {@code player}.
     */
    private static @Nullable LivingEntity resolveEntityInstance(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (name.equals("player")) {
            return mc.player;
        }
        @Nullable Identifier id = Identifier.tryParse(name);
        if (id == null) {
            return null;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
        if (type == null) {
            return null;
        }
        net.minecraft.world.entity.Entity entity = type.create(mc.level, net.minecraft.world.entity.EntitySpawnReason.LOAD);
        return entity instanceof LivingEntity living ? living : null;
    }

    /**
     * Parses {@code size=N} from the query string; returns {@code -1} on malformed input.
     */
    private static int parseSize(String query, int defaultValue, int min, int max) {
        for (String pair : query.split("&")) {
            if (pair.startsWith("size=")) {
                try {
                    int size = Integer.parseInt(pair.substring("size=".length()));
                    return Math.clamp(size, min, max);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return defaultValue;
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

    @Override
    public void resolve(String location, long requestId) {
        LOGGER.debug("Webview requests resource '{}' (request {})", location, requestId);
        if (location.startsWith(ITEM_PREFIX)) {
            resolveItem(location, requestId);
            return;
        }
        if (location.startsWith(ENTITY_PREFIX)) {
            resolveEntity(location, requestId);
            return;
        }
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

    /**
     * Resolves {@code item:<注册名>?size=N} by rendering the item's GUI icon on the render
     * thread. {@code size} defaults to {@link ItemIconRenderer#DEFAULT_SIZE}.
     *
     * <p>Item data components are only bound once the datapack has loaded, i.e. after joining
     * a world; requests before that (e.g. on the title screen) answer 404.
     */
    private void resolveItem(String location, long requestId) {
        WebUi.onRenderThread(() -> {
            if (Minecraft.getInstance().level == null) {
                LOGGER.warn(
                    "Webview item request '{}' before joining a world; item data is not loaded yet",
                    location
                );
                NativeWebView.respondResource(requestId, null, null);
                return;
            }
            String body = location.substring(ITEM_PREFIX.length());
            String[] parts = body.split("\\?", 2);
            @Nullable Identifier id = Identifier.tryParse(parts[0]);
            if (id == null) {
                LOGGER.warn("Webview requested malformed item location '{}'", location);
                NativeWebView.respondResource(requestId, null, null);
                return;
            }
            int size = parseSize(
                parts.length == 2 ? parts[1] : "", ItemIconRenderer.DEFAULT_SIZE,
                ItemIconRenderer.MIN_SIZE, ItemIconRenderer.MAX_SIZE
            );
            if (size < 0) {
                LOGGER.warn("Webview requested invalid item size in '{}'", location);
                NativeWebView.respondResource(requestId, null, null);
                return;
            }
            Item item = BuiltInRegistries.ITEM.getValue(id);
            if (item == null || item == Items.AIR) {
                LOGGER.warn("Webview requested unknown item '{}'", parts[0]);
                NativeWebView.respondResource(requestId, null, null);
                return;
            }
            if (itemIconRenderer == null) {
                itemIconRenderer = new ItemIconRenderer();
            }
            itemIconRenderer.enqueue(item, size, requestId);
        });
    }

    /**
     * Resolves {@code entity:<注册名>?size=N} by rendering a preview of the entity. The first
     * frame is answered here; mouse-driven rotation frames are produced by
     * {@link EntityPreviewRenderer} on {@code ferric_oxide.entity_rotate} IPC messages.
     *
     * <p>Requires a loaded world, like {@link #resolveItem}.
     */
    private void resolveEntity(String location, long requestId) {
        WebUi.onRenderThread(() -> {
            if (Minecraft.getInstance().level == null) {
                LOGGER.warn(
                    "Webview entity request '{}' before joining a world; entity data is not loaded yet",
                    location
                );
                NativeWebView.respondResource(requestId, null, null);
                return;
            }
            String body = location.substring(ENTITY_PREFIX.length());
            String[] parts = body.split("\\?", 2);
            int size = parseSize(
                parts.length == 2 ? parts[1] : "", EntityPreviewRenderer.DEFAULT_SIZE,
                EntityPreviewRenderer.MIN_SIZE, EntityPreviewRenderer.MAX_SIZE
            );
            if (size < 0) {
                LOGGER.warn("Webview requested invalid entity size in '{}'", location);
                NativeWebView.respondResource(requestId, null, null);
                return;
            }
            @Nullable LivingEntity entity = resolveEntityInstance(parts[0]);
            if (entity == null) {
                LOGGER.warn("Webview requested unknown or non-living entity '{}'", parts[0]);
                NativeWebView.respondResource(requestId, null, null);
                return;
            }
            if (entityPreviewRenderer == null) {
                entityPreviewRenderer = EntityPreviewRenderer.instance();
            }
            entityPreviewRenderer.prepare(entity, size);
            NativeWebView.respondResource(requestId, entityPreviewRenderer.renderFrame(), "image/png");
        });
    }
}
