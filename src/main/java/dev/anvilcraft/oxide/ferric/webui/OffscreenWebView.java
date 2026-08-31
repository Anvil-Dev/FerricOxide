package dev.anvilcraft.oxide.ferric.webui;

import com.google.gson.Gson;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An offscreen WebView rendered to a CPU-readable RGBA frame buffer (Windows only).
 *
 * <p>The native side (see {@code rust/src/offscreen.rs}) hosts a WebView2 composition
 * controller rooted at a WinRT composition visual that is never shown on screen, captures it
 * with Windows.Graphics.Capture and exposes the newest frame on demand. Input is forwarded
 * through the Chrome DevTools Protocol, so the webview needs no window at all.
 *
 * <p>Frames are pulled by the caller: {@link #getFrame()} returns {@code null} until the
 * native side reports a generation newer than the last one it handed out.
 *
 * <p>Instances are safe to use from any thread. A garbage-collected instance that was never
 * closed is closed automatically by {@link Cleaner} semantics in {@link CloseGuard}.
 */
public final class OffscreenWebView implements AutoCloseable {
    private static final Gson GSON = new Gson();

    private final CloseGuard guard;
    private long lastGeneration;

    private OffscreenWebView(long handle) {
        this.guard = new CloseGuard(handle);
    }

    /**
     * Indicates whether offscreen webviews can be created on this machine: the native library
     * must be loaded and the platform must be Windows (WebView2 + WGC), macOS (WKWebView
     * snapshot) or Linux (WebKitGTK snapshot).
     */
    public static boolean isAvailable() {
        if (!NativeLoader.isLoaded()) {
            return false;
        }
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("win") || os.contains("mac") || os.contains("linux");
    }

    private static native long nativeCreate(
        int width,
        int height,
        @Nullable String url,
        @Nullable String initScript,
        @Nullable CreationCallback creation
    );

    private static native @Nullable byte[] nativeGetFrame(long handle, long lastGeneration);

    private static native void nativeResize(long handle, int width, int height);

    private static native void nativeCdp(long handle, String method, String params);

    private static native void nativeLoadUrl(long handle, String url);

    private static native void nativeEval(long handle, String js);

    private static native void nativeClose(long handle);

    /** One captured frame: the native generation counter plus tightly packed RGBA pixels. */
    public record Frame(long generation, byte[] rgba) {
    }

    /**
     * Returns the newest frame when it is newer than the last one this instance handed out,
     * otherwise {@code null}.
     */
    public @Nullable Frame getFrame() {
        long handle = guard.handle.get();
        if (handle == 0L) {
            return null;
        }
        byte[] payload = nativeGetFrame(handle, lastGeneration);
        if (payload == null || payload.length < 8) {
            return null;
        }
        long generation = 0;
        for (int i = 7; i >= 0; i--) {
            generation = (generation << 8) | (payload[i] & 0xFFL);
        }
        byte[] rgba = new byte[payload.length - 8];
        System.arraycopy(payload, 8, rgba, 0, rgba.length);
        lastGeneration = generation;
        return new Frame(generation, rgba);
    }

    /** Resizes the page viewport. */
    public void resize(int width, int height) {
        long handle = guard.handle.get();
        if (handle != 0L) {
            nativeResize(handle, width, height);
        }
    }

    /**
     * Sends a raw Chrome DevTools Protocol command. Fire-and-forget; the response is ignored.
     */
    public void cdp(String method, String paramsJson) {
        long handle = guard.handle.get();
        if (handle != 0L) {
            nativeCdp(handle, method, paramsJson);
        }
    }

    /** Navigates to a URL. */
    public void loadUrl(String url) {
        long handle = guard.handle.get();
        if (handle != 0L) {
            nativeLoadUrl(handle, url);
        }
    }

    /** Runs a JavaScript snippet in the page context. Fire-and-forget. */
    public void eval(String js) {
        long handle = guard.handle.get();
        if (handle != 0L) {
            nativeEval(handle, js);
        }
    }

    // ------------------------------------------------------------------
    // CDP input helpers (Input domain)
    // ------------------------------------------------------------------

    /** Forwards a mouse move to the given page coordinates. */
    public void mouseMove(int x, int y) {
        dispatchMouseEvent("mouseMoved", x, y, "none", 0);
    }

    /** Forwards a mouse button press/release. {@code button}: 0 left, 1 middle, 2 right. */
    public void mouseButton(int x, int y, int button, boolean down) {
        dispatchMouseEvent(down ? "mousePressed" : "mouseReleased", x, y, buttonName(button), 1);
    }

    /** Forwards a scroll-wheel event with pixel deltas. */
    public void mouseWheel(int x, int y, double deltaX, double deltaY) {
        Map<String, Object> params = baseMouseParams("mouseWheel", x, y);
        params.put("deltaX", deltaX);
        params.put("deltaY", deltaY);
        cdp("Input.dispatchMouseEvent", GSON.toJson(params));
    }

    /**
     * Forwards a key event. {@code type} is {@code keyDown}/{@code keyUp}/{@code rawKeyDown};
     * the code points follow the CDP {@code Input.dispatchKeyEvent} parameter naming.
     */
    public void keyEvent(
        String type,
        int windowsVirtualKeyCode,
        String key,
        String code,
        @Nullable String text,
        int modifiers
    ) {
        Map<String, Object> params = new HashMap<>();
        params.put("type", type);
        params.put("windowsVirtualKeyCode", windowsVirtualKeyCode);
        params.put("nativeVirtualKeyCode", windowsVirtualKeyCode);
        params.put("key", key);
        params.put("code", code);
        if (text != null) {
            params.put("text", text);
        }
        if (modifiers != 0) {
            params.put("modifiers", modifiers);
        }
        cdp("Input.dispatchKeyEvent", GSON.toJson(params));
    }

    /** Inserts committed text (typed characters) into the focused element. */
    public void insertText(String text) {
        Map<String, Object> params = new HashMap<>();
        params.put("text", text);
        cdp("Input.insertText", GSON.toJson(params));
    }

    /** Tells the page it lost the mouse pointer (cursor left the display surface). */
    public void mouseLeave(int x, int y) {
        dispatchMouseEvent("mouseLeft", x, y, "none", 0);
    }

    private void dispatchMouseEvent(String type, int x, int y, String button, int clickCount) {
        Map<String, Object> params = baseMouseParams(type, x, y);
        params.put("button", button);
        params.put("clickCount", clickCount);
        cdp("Input.dispatchMouseEvent", GSON.toJson(params));
    }

    private static Map<String, Object> baseMouseParams(String type, int x, int y) {
        Map<String, Object> params = new HashMap<>();
        params.put("type", type);
        params.put("x", x);
        params.put("y", y);
        return params;
    }

    private static String buttonName(int button) {
        return switch (button) {
            case 0 -> "left";
            case 1 -> "middle";
            case 2 -> "right";
            case 3 -> "back";
            case 4 -> "forward";
            default -> "none";
        };
    }

    /**
     * Destroys the native WebView. Idempotent; safe to call more than once and from multiple
     * threads.
     */
    @Override
    public void close() {
        guard.close();
    }

    /**
     * Ensures the native webview is destroyed even if the caller forgets {@link #close()}.
     */
    private static final class CloseGuard implements AutoCloseable {
        private static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();

        private final AtomicLong handle;

        CloseGuard(long handle) {
            this.handle = new AtomicLong(handle);
            AtomicLong handleRef = this.handle;
            CLEANER.register(
                this, () -> {
                    long h = handleRef.getAndSet(0L);
                    if (h != 0L) {
                        nativeClose(h);
                    }
                }
            );
        }

        @Override
        public void close() {
            long h = handle.getAndSet(0L);
            if (h != 0L) {
                nativeClose(h);
            }
        }
    }

    /**
     * Fluent builder mirroring the native creation parameters.
     */
    public static final class Builder {
        private int width = 960;
        private int height = 540;
        private @Nullable String url;
        private @Nullable String initScript;
        private @Nullable CreationCallback creation;

        /** Viewport size in pixels (also the captured frame size). */
        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        /** URL to load. */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /** Script evaluated before any page script on every navigation. */
        public Builder initScript(String initScript) {
            this.initScript = initScript;
            return this;
        }

        /** Asynchronous creation-result callback. */
        public Builder onCreated(CreationCallback creation) {
            this.creation = creation;
            return this;
        }

        /**
         * Creates the offscreen WebView. Returns immediately; the actual creation happens on
         * the native event-loop thread and its outcome is reported through
         * {@link #onCreated(CreationCallback)}.
         *
         * @throws IllegalStateException if the native library is not loaded, or the native
         *                               command dispatcher is not running
         */
        public OffscreenWebView build() {
            if (!NativeLoader.isLoaded()) {
                throw new IllegalStateException("ferric_oxide native library is not loaded");
            }
            long handle = nativeCreate(width, height, url, initScript, creation);
            if (handle == 0L) {
                throw new IllegalStateException("nativeCreate returned a null handle");
            }
            return new OffscreenWebView(handle);
        }
    }
}
