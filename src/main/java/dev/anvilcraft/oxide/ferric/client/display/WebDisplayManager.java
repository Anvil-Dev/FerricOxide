package dev.anvilcraft.oxide.ferric.client.display;

import com.mojang.logging.LogUtils;
import dev.anvilcraft.oxide.ferric.display.WebDisplayBlockEntity;
import dev.anvilcraft.oxide.ferric.display.WebDisplayGroup;
import dev.anvilcraft.oxide.ferric.display.WebDisplayGroups;
import dev.anvilcraft.oxide.ferric.webui.OffscreenWebView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 客户端大屏管理器：把世界中的显示器组映射到离屏 WebView + 动态纹理。
 *
 * <p>渲染器对每个可见成员方块实体调用 {@link #resolve(Level, BlockPos)}（带节流的重算），
 * 管理器按锚点位置聚合出活动大屏，惰性创建离屏 WebView 并在地址/尺寸变化时同步。
 * 超过 {@value #KEEP_ALIVE_TICKS} tick 未被任何成员触达的条目被销毁（含原生 WebView 与
 * 纹理）。同一世界允许任意多个大屏并存。
 */
public final class WebDisplayManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 网页布局视口宽度（CSS 像素）：无论大屏多大都按 1920px 宽排版，
     * 高度随组的宽高比变化（16x9 → 1920x1080）。
     */
    public static final int VIEWPORT_WIDTH = 1920;
    /** 纹理任一边的上限，极端宽高比（如 1x9）时整体缩小。 */
    private static final int MAX_TEXTURE_SIDE = 8192;
    /** 触达超时（tick)，超时销毁大屏。 */
    private static final long KEEP_ALIVE_TICKS = 100;
    /** 同一成员位置的组重算最小间隔（tick)。 */
    private static final long RECOMPUTE_INTERVAL = 10;

    private static final Map<BlockPos, ActiveDisplay> ACTIVE = new HashMap<>();
    private static final Map<BlockPos, RecomputeCache> RECOMPUTE_CACHE = new HashMap<>();
    private static long clientTick;
    /** 原生创建一旦失败（缺运行时/平台不支持等全局原因），本会话内不再尝试。 */
    private static boolean globallyUnavailable;

    private WebDisplayManager() {
    }

    /**
     * 一块活动大屏。
     */
    public static final class ActiveDisplay {
        public WebDisplayGroup.Group group;
        public final Identifier textureId;
        public WebDisplayTexture texture;
        public @Nullable OffscreenWebView webView;
        /** 原生创建失败（如非 Windows/无 WebView2）后不再重试。 */
        public boolean creationFailed;
        public String url = "";
        public long lastSeenTick;
        public long textureGeneration;
        private int textureWidth;
        private int textureHeight;
        private @Nullable ByteBuffer frameBuffer;

        ActiveDisplay(WebDisplayGroup.Group group, Identifier textureId) {
            this.group = group;
            this.textureId = textureId;
            int[] dims = dimensionsOf(group);
            this.textureWidth = dims[0];
            this.textureHeight = dims[1];
            this.texture = createTexture(group);
        }

        private WebDisplayTexture createTexture(WebDisplayGroup.Group group) {
            return new WebDisplayTexture(
                "web_display/" + group.anchor().toShortString(),
                this.textureWidth,
                this.textureHeight
            );
        }

        public int textureWidth() {
            return this.textureWidth;
        }

        public int textureHeight() {
            return this.textureHeight;
        }

        /**
         * 组结构变化但锚点不变时原地更新：只重建纹理并缩放 WebView，
         * 不销毁浏览器实例（避免放置/拆分时的卡顿）。
         */
        void resizeTo(WebDisplayGroup.Group newGroup) {
            boolean sizeChanged =
                newGroup.width() != this.group.width() || newGroup.height() != this.group.height();
            this.group = newGroup;
            if (!sizeChanged) {
                return;
            }
            int[] dims = dimensionsOf(newGroup);
            if (dims[0] == this.textureWidth && dims[1] == this.textureHeight) {
                return;
            }
            this.textureWidth = dims[0];
            this.textureHeight = dims[1];
            this.texture.close();
            this.texture = createTexture(newGroup);
            Minecraft.getInstance().getTextureManager().register(this.textureId, this.texture);
            this.frameBuffer = null;
            if (this.webView != null) {
                this.webView.resize(this.textureWidth, this.textureHeight);
            }
        }

        void close() {
            if (this.webView != null) {
                this.webView.close();
                this.webView = null;
            }
            Minecraft.getInstance().getTextureManager().release(this.textureId);
            this.texture.close();
        }
    }

    /** 视口固定 1920px 宽；高度按组的宽高比，超限时等比缩小。 */
    private static int[] dimensionsOf(WebDisplayGroup.Group group) {
        int width = VIEWPORT_WIDTH;
        int height = (int) Math.round(VIEWPORT_WIDTH * (double) group.height() / group.width());
        if (height > MAX_TEXTURE_SIDE) {
            double scale = MAX_TEXTURE_SIDE / (double) height;
            height = MAX_TEXTURE_SIDE;
            width = Math.max(1, (int) Math.round(width * scale));
        }
        return new int[]{width, height};
    }

    private record RecomputeCache(long tick, WebDisplayGroup.@Nullable Group group) {
    }

    /**
     * 解析某位置所属的活动大屏：必要时重算组合、创建/更新离屏 WebView。
     * 每次调用都刷新触达时间。
     */
    public static @Nullable ActiveDisplay resolve(Level level, BlockPos pos) {
        WebDisplayGroup.Group group = cachedGroupAt(level, pos);
        if (group == null) {
            // 单块退化组
            if (!(level.getBlockState(pos).getBlock()
                instanceof dev.anvilcraft.oxide.ferric.display.WebDisplayBlock)) {
                return null;
            }
            group = new WebDisplayGroup.Group(
                level.getBlockState(pos).getValue(dev.anvilcraft.oxide.ferric.display.WebDisplayBlock.FACING),
                pos, 1, 1, java.util.Set.of(pos)
            );
        }
        BlockPos anchor = group.anchor();
        ActiveDisplay entry = ACTIVE.get(anchor);
        if (entry == null) {
            entry = migrateOrCreate(level, group);
        } else if (!entry.group.equals(group)) {
            // 锚点不变、组结构变化：原地缩放，保留浏览器实例
            entry.resizeTo(group);
        }
        entry.lastSeenTick = clientTick;

        if (level.getBlockEntity(anchor) instanceof WebDisplayBlockEntity anchorEntity) {
            String url = anchorEntity.getUrl();
            if (!url.equals(entry.url)) {
                entry.url = url;
                if (entry.webView != null) {
                    entry.webView.loadUrl(url);
                }
            }
        }
        ensureWebView(entry);
        return entry;
    }

    /**
     * 为新组创建条目。若存在同朝向且成员相交的旧条目（锚点移动导致换键），
     * 把它的离屏 WebView 迁移过来，避免销毁重建浏览器进程。
     */
    private static ActiveDisplay migrateOrCreate(Level level, WebDisplayGroup.Group group) {
        BlockPos anchor = group.anchor();
        Identifier textureId = Identifier.fromNamespaceAndPath(
            "ferric_oxide",
            "web_display/" + anchor.getX() + "_" + anchor.getY() + "_" + anchor.getZ()
        );
        ActiveDisplay donor = null;
        BlockPos donorKey = null;
        for (Map.Entry<BlockPos, ActiveDisplay> e : ACTIVE.entrySet()) {
            ActiveDisplay candidate = e.getValue();
            if (candidate.group.facing() == group.facing()
                && !java.util.Collections.disjoint(candidate.group.members(), group.members())) {
                donor = candidate;
                donorKey = e.getKey();
                break;
            }
        }
        ActiveDisplay entry = new ActiveDisplay(group, textureId);
        // 注册进 TextureManager，否则 entitySolid 渲染时拿到缺失纹理（紫黑方块）
        Minecraft.getInstance().getTextureManager().register(textureId, entry.texture);
        if (donor != null) {
            entry.webView = donor.webView;
            donor.webView = null;
            entry.url = donor.url;
            entry.creationFailed = donor.creationFailed;
            donor.close();
            ACTIVE.remove(donorKey);
            if (entry.webView != null) {
                entry.webView.resize(entry.textureWidth(), entry.textureHeight());
            }
        }
        ACTIVE.put(anchor, entry);
        return entry;
    }

    /**
     * 节流的组查询（渲染包围盒等高频路径用）：同一位置每 {@value #RECOMPUTE_INTERVAL}
     * tick 最多重算一次。
     */
    public static WebDisplayGroup.@Nullable Group cachedGroupAt(Level level, BlockPos pos) {
        RecomputeCache cached = RECOMPUTE_CACHE.get(pos);
        if (cached != null && clientTick - cached.tick() < RECOMPUTE_INTERVAL) {
            return cached.group();
        }
        WebDisplayGroup.Group group = WebDisplayGroups.computeAt(level, pos);
        RECOMPUTE_CACHE.put(pos, new RecomputeCache(clientTick, group));
        return group;
    }

    private static void ensureWebView(ActiveDisplay entry) {
        if (entry.webView != null || entry.creationFailed || globallyUnavailable || entry.url.isEmpty()) {
            return;
        }
        if (!OffscreenWebView.isAvailable()) {
            globallyUnavailable = true;
            LOGGER.warn("Offscreen webview unavailable (Windows + WebView2 required); web display at {} stays blank",
                entry.group.anchor().toShortString());
            return;
        }
        try {
            entry.webView = new OffscreenWebView.Builder()
                .size(entry.textureWidth(), entry.textureHeight())
                .url(entry.url)
                .onCreated((id, error) -> {
                    if (error != null && !error.isEmpty()) {
                        entry.creationFailed = true;
                        globallyUnavailable = true;
                        LOGGER.error("Failed to create offscreen webview: {}", error);
                    }
                })
                .build();
        } catch (Throwable t) {
            entry.creationFailed = true;
            globallyUnavailable = true;
            LOGGER.error("Failed to create offscreen webview", t);
        }
    }

    /**
     * 按锚点查询活动大屏。
     */
    public static @Nullable ActiveDisplay get(BlockPos anchor) {
        return ACTIVE.get(anchor);
    }

    /**
     * 每客户端 tick：拉取新帧上传纹理、清理超时条目。
     */
    public static void tick() {
        clientTick++;
        Iterator<Map.Entry<BlockPos, ActiveDisplay>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, ActiveDisplay> e = it.next();
            ActiveDisplay display = e.getValue();
            if (clientTick - display.lastSeenTick > KEEP_ALIVE_TICKS) {
                display.close();
                it.remove();
                continue;
            }
            pullFrame(display);
        }
        RECOMPUTE_CACHE.values().removeIf(c -> clientTick - c.tick() > KEEP_ALIVE_TICKS);
    }

    private static void pullFrame(ActiveDisplay display) {
        OffscreenWebView webView = display.webView;
        if (webView == null) {
            return;
        }
        OffscreenWebView.Frame frame = webView.getFrame();
        if (frame == null) {
            return;
        }
        int size = display.textureWidth() * display.textureHeight() * 4;
        if (frame.rgba().length < size) {
            return;
        }
        if (display.frameBuffer == null || display.frameBuffer.capacity() < size) {
            display.frameBuffer = ByteBuffer.allocateDirect(size);
        }
        ByteBuffer buffer = display.frameBuffer;
        buffer.clear();
        buffer.put(frame.rgba(), 0, size);
        buffer.flip();
        display.texture.upload(buffer);
    }

    /**
     * 换维度/断开世界时清空全部大屏。
     */
    public static void clear() {
        ACTIVE.values().forEach(ActiveDisplay::close);
        ACTIVE.clear();
        RECOMPUTE_CACHE.clear();
    }
}
