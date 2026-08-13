package dev.anvilcraft.oxide.ferric.webui;

import com.mojang.logging.LogUtils;
import dev.anvilcraft.oxide.ferric.webui.bridge.WebBridge;
import dev.anvilcraft.oxide.ferric.webui.bridge.WebBridgeImpl;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * High-level facade over a native OS WebView for mod developers.
 *
 * <p>Wraps {@link NativeWebView} and adds:
 * <ul>
 *   <li>convenience factories — {@link #embedded(String, String, int, int)} embeds the webview
 *       into the Minecraft window (an HWND child on Windows or an NSView child on macOS) and
 *       hands it keyboard focus and the mouse cursor; {@link #window(String, String, int, int)}
 *       opens a standalone window where supported;</li>
 *   <li>a two-way {@link WebBridge} — {@link #bridge()} exchanges events and queries with the
 *       page, which sees the same API as {@code ferric.emit / on / call / handle};</li>
 *   <li>the {@code ferric} resource protocol, so pages can reference resource-pack files and
 *       rendered game icons through {@code ferric.resource(...)};</li>
 *   <li>{@link #readModAsset(String, String)} to load HTML/JS/CSS from mod resources.</li>
 * </ul>
 *
 * <p>Instances are safe to use from any thread. Close via {@link #close()} (or let the
 * garbage collector reclaim it — the native window is then destroyed by a {@code Cleaner}).
 */
public final class WebUi implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Custom URL protocol serving game resources to pages. */
    private static final String RESOURCE_PROTOCOL = "ferric";

    /** Bridge runtime installed into every page before its own scripts run. */
    private static final String BRIDGE_RUNTIME = "webui/bridge.js";

    private final NativeWebView webView;
    private final WebBridgeImpl bridge;
    private volatile boolean open = true;

    private WebUi(NativeWebView webView, WebBridgeImpl bridge) {
        this.webView = webView;
        this.bridge = bridge;
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
     * Creates an embedded webview that loads a mod asset page and can reference resource-pack
     * files.
     *
     * <p>The page is read from {@code assets/<modId>/<path>} and a {@code <base>} element is
     * injected so that relative references resolve against the mod's {@code webui/} directory
     * (for example {@code img.png} in {@code webui/test/exp1.html} resolves to
     * {@code webui/img.png}). Game resources are addressable from the page through
     * {@code ferric.resource("<namespace>/<path>")}.
     *
     * @param title  window title (used for the standalone fallback)
     * @param modId  mod id owning the page (also the default resource namespace)
     * @param path   page path inside the mod's assets, must start with {@code webui/}
     * @param width  initial width in parent client-area pixels
     * @param height initial height in parent client-area pixels
     */
    public static WebUi embedded(String title, String modId, String path, int width, int height) {
        if (!path.startsWith("webui/")) {
            throw new IllegalArgumentException("embedded pages must live under webui/, got: " + path);
        }
        String html = injectBase(
            readModAsset(modId, path), "<base href=\"" + resourceBase() + modId + "/webui/\">");
        return create(true, title, html, width, height);
    }

    /**
     * Creates a standalone WebView window (not attached to the Minecraft window).
     */
    public static WebUi window(String title, String html, int width, int height) {
        return create(false, title, html, width, height);
    }

    /**
     * Resource URL prefix for the current platform, ending in {@code /}.
     *
     * <p>WebView2 (Windows) only intercepts http(s) URLs, so custom-scheme requests there use the
     * {@code http://{protocol}.localhost/...} workaround; other platforms use the native
     * {@code {protocol}://} scheme.
     */
    private static String resourceBase() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return windows ? "http://" + RESOURCE_PROTOCOL + ".localhost/" : RESOURCE_PROTOCOL + "://";
    }

    /**
     * Inserts {@code <base>} right after the opening {@code <head>} tag, or at the top when absent.
     */
    private static String injectBase(String html, String base) {
        int headStart = html.toLowerCase(Locale.ROOT).indexOf("<head");
        if (headStart >= 0) {
            int gt = html.indexOf('>', headStart);
            if (gt >= 0) {
                return html.substring(0, gt + 1) + base + html.substring(gt + 1);
            }
        }
        return base + html;
    }

    private static WebUi create(boolean embedded, String title, String html, int width, int height) {
        if (!NativeWebView.isAvailable()) {
            throw new IllegalStateException("ferric_oxide native library is not loaded");
        }
        // The webview does not exist yet, but the bridge must: its inbound handler goes into the
        // builder. The evaluator therefore resolves the webview lazily through the holder, which
        // is filled in below before the page can run any script.
        NativeWebView[] holder = new NativeWebView[1];
        WebBridgeImpl bridge = new WebBridgeImpl(
            js -> {
                NativeWebView target = holder[0];
                if (target == null) {
                    LOGGER.error("Dropping bridge message sent before the webview was created: {}", js);
                    return;
                }
                target.eval(js);
            },
            WebUi::onRenderThread
        );
        NativeWebView.Builder builder = new NativeWebView.Builder()
            .title(title)
            .size(width, height)
            .html(html)
            .initScript(WebBridgeImpl.initScript(readModAsset("ferric_oxide", BRIDGE_RUNTIME), resourceBase()))
            .resource(RESOURCE_PROTOCOL, new MinecraftResourceHandler())
            .ipc(bridge::accept);
        if (embedded) {
            builder.parent(minecraftWindowHandle());
            builder.onCreated((id, error) -> {
                if (error != null && !error.isEmpty()) {
                    LOGGER.error("Failed to create embedded webview: {}", error);
                    return;
                }
                // Creation callback runs on the native UI thread: marshal game access back to
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
        return new WebUi(webView, bridge);
    }

    /**
     * Reads a UTF-8 text asset (HTML/JS/CSS) from a mod's {@code assets/<modId>} tree.
     */
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

    /**
     * Native parent handle for the Minecraft window: HWND on Windows, NSView pointer on macOS,
     * or {@code 0} on Linux.
     */
    public static long minecraftWindowHandle() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        long glfwWindow = Minecraft.getInstance().getWindow().handle();
        if (os.contains("win")) {
            return GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
        }
        if (os.contains("mac")) {
            return GLFWNativeCocoa.glfwGetCocoaView(glfwWindow);
        }
        return 0L;
    }

    /**
     * Runs the task on the render thread; executes directly when already on it.
     */
    public static void onRenderThread(Runnable task) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            task.run();
        } else {
            mc.execute(task);
        }
    }

    /**
     * The two-way channel to the page. Register handlers on it before the page starts talking,
     * and use it to push events or run queries against the page.
     */
    public WebBridge bridge() {
        return bridge;
    }

    /**
     * Navigates to a URL. The bridge runtime is reinstalled automatically on the new page.
     */
    public void loadUrl(String url) {
        webView.loadUrl(url);
    }

    /**
     * Loads an HTML string. The bridge runtime is reinstalled automatically on the new page.
     */
    public void loadHtml(String html) {
        webView.loadHtml(html);
    }

    /**
     * Shows or hides the webview.
     */
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

    /**
     * Moves keyboard focus to the webview.
     */
    public void focus() {
        webView.focus();
    }

    /**
     * Whether {@link #close()} has not been called yet.
     */
    public boolean isOpen() {
        return open;
    }

    /**
     * Releases the wrapped webview and fails every in-flight bridge call; further calls become
     * no-ops.
     */
    @Override
    public void close() {
        open = false;
        bridge.close();
        webView.close();
    }
}
