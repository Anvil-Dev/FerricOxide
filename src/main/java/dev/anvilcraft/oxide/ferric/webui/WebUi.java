package dev.anvilcraft.oxide.ferric.webui;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.slf4j.Logger;

/**
 * High-level facade over a native OS WebView for mod developers.
 *
 * <p>Wraps {@link NativeWebView} and adds:
 * <ul>
 *   <li>convenience factories — {@link #embedded(String, String, int, int)} embeds the webview
 *       into the Minecraft window (native HWND child) and hands it keyboard focus and the mouse
 *       cursor; {@link #window(String, String, int, int)} opens a standalone window;</li>
 *   <li>typed message routing — {@link #on(String, Consumer)} dispatches inbound
 *       {@code window.ipc.postMessage(...)} payloads by their {@code type} field;</li>
 *   <li>{@link #readModAsset(String, String)} to load HTML/JS/CSS from mod resources.</li>
 * </ul>
 *
 * <p>Instances are safe to use from any thread. Close via {@link #close()} (or let the
 * garbage collector reclaim it — the native window is then destroyed by a {@code Cleaner}).
 */
public final class WebUi implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final NativeWebView webView;
    private final Map<String, Consumer<WebUiMessage>> handlers;
    private volatile boolean open = true;

    private WebUi(NativeWebView webView, Map<String, Consumer<WebUiMessage>> handlers) {
        this.webView = webView;
        this.handlers = handlers;
    }

    /**
     * Creates an embedded webview filling the Minecraft window and hands input to it.
     *
     * <p>On platforms without native window embedding support this falls back to a standalone
     * window.
     *
     * @param title  window title (used for the standalone fallback)
     * @param html   HTML content to render
     * @param width  initial width in parent client-area pixels
     * @param height initial height in parent client-area pixels
     */
    public static WebUi embedded(String title, String html, int width, int height) {
        return create(true, title, html, width, height);
    }

    /**
     * Creates a standalone WebView window (not attached to the Minecraft window).
     */
    public static WebUi window(String title, String html, int width, int height) {
        return create(false, title, html, width, height);
    }

    private static WebUi create(boolean embedded, String title, String html, int width, int height) {
        if (!NativeWebView.isAvailable()) {
            throw new IllegalStateException("ferric_oxide native library is not loaded");
        }
        Map<String, Consumer<WebUiMessage>> handlers = new ConcurrentHashMap<>();
        NativeWebView.Builder builder = new NativeWebView.Builder()
            .title(title)
            .size(width, height)
            .html(html);
        if (embedded) {
            builder.parent(minecraftWindowHandle());
        }
        NativeWebView[] holder = new NativeWebView[1];
        builder.ipc(raw -> {
            WebUiMessage message = WebUiMessage.parse(raw);
            if (message == null) {
                LOGGER.warn("Ignoring malformed webview message: {}", raw);
                return;
            }
            String type = message.type();
            Consumer<WebUiMessage> handler = type == null ? null : handlers.get(type);
            if (handler == null) {
                LOGGER.debug("No handler registered for webview message type '{}'", type);
            } else {
                // IPC callbacks arrive on the native webview thread. Minecraft APIs are only
                // safe on the render thread, so marshal the dispatch before invoking handlers.
                onRenderThread(() -> handler.accept(message));
            }
        });
        if (embedded) {
            builder.onCreated((id, error) -> {
                if (error != null && !error.isEmpty()) {
                    LOGGER.error("Failed to create embedded webview: {}", error);
                    return;
                }
                // Creation callback runs on the native thread: marshal game access back to
                // the render thread before touching the mouse handler.
                onRenderThread(() -> {
                    if (holder[0] != null) {
                        holder[0].focus();
                        Minecraft.getInstance().mouseHandler.releaseMouse();
                    }
                });
            });
        }
        NativeWebView webView = builder.build();
        holder[0] = webView;
        return new WebUi(webView, handlers);
    }

    /** Routes inbound messages by their {@code type}. Returns {@code this}. */
    public WebUi on(String type, Consumer<WebUiMessage> handler) {
        handlers.put(type, handler);
        return this;
    }

    /** Evaluates a JavaScript snippet in the page context. */
    public void eval(String js) {
        webView.eval(js);
    }

    /** Navigates to a URL. */
    public void loadUrl(String url) {
        webView.loadUrl(url);
    }

    /** Loads an HTML string. */
    public void loadHtml(String html) {
        webView.loadHtml(html);
    }

    /** Shows or hides the webview. */
    public void setVisible(boolean visible) {
        webView.setVisible(visible);
    }

    /**
     * Moves and resizes the webview. Embedded bounds use parent client-area pixels; standalone
     * window bounds use logical pixels.
     */
    public void setBounds(int x, int y, int width, int height) {
        webView.setBounds(x, y, width, height);
    }

    /** Moves keyboard focus to the webview. */
    public void focus() {
        webView.focus();
    }

    /** Whether {@link #close()} has not been called yet. */
    public boolean isOpen() {
        return open;
    }

    /** Releases the wrapped webview; further calls become no-ops. */
    @Override
    public void close() {
        open = false;
        webView.close();
    }

    /** Reads a UTF-8 text asset (HTML/JS/CSS) from a mod's {@code assets/<modId>} tree. */
    public static String readModAsset(String modId, String path) {
        String resource = "/assets/" + modId + "/" + path;
        try (InputStream in = WebUi.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing mod asset " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read mod asset " + resource, e);
        }
    }

    /** Native handle of the Minecraft window (HWND on Windows) or 0 when not supported. */
    public static long minecraftWindowHandle() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return 0L;
        }
        long glfwWindow = Minecraft.getInstance().getWindow().handle();
        return GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
    }

    /** Runs the task on the render thread; executes directly when already on it. */
    static void onRenderThread(Runnable task) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            task.run();
        } else {
            mc.execute(task);
        }
    }
}
