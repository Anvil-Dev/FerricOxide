package dev.anvilcraft.oxide.ferric.webui;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A native OS WebView window (WebView2 on Windows, WebKitGTK on Linux, WKWebView on macOS).
 * Windows and Linux use a dedicated Rust event-loop thread; macOS dispatches every operation to
 * the OS main queue because AppKit and WKWebView require the process main thread.
 *
 * <p>Instances are created via the {@link Builder}. All methods are safe to call from any
 * thread; work is marshaled to the platform's native UI thread. A garbage-collected instance
 * that was never closed is closed automatically by {@link Cleaner} semantics in
 * {@link CloseGuard}.
 */
public final class NativeWebView implements AutoCloseable {
    private final CloseGuard guard;

    private NativeWebView(long handle) {
        this.guard = new CloseGuard(handle);
    }

    /**
     * Indicates whether the native library could be loaded on this machine. When {@code false},
     * constructing a NativeWebView will throw {@link UnsatisfiedLinkError}.
     */
    public static boolean isAvailable() {
        return NativeLoader.isLoaded();
    }

    private static native long nativeCreate(
        String title,
        int width,
        int height,
        @Nullable String url,
        @Nullable String html,
        boolean transparent,
        boolean visible,
        long parent,
        @Nullable MessageHandler handler,
        @Nullable CreationCallback creation,
        @Nullable String protocol,
        @Nullable ResourceHandler resource
    );

    /**
     * Completes a pending custom-protocol resource request.
     *
     * @param requestId the id previously passed to {@link ResourceHandler#resolve}
     * @param bytes     the resource payload, or {@code null} to answer 404
     * @param mime      content type for the payload, or {@code null} for the octet-stream default
     */
    static void respondResource(long requestId, @Nullable byte[] bytes, @Nullable String mime) {
        nativeResourceResponse(requestId, bytes, mime);
    }

    private static native void nativeResourceResponse(long requestId, @Nullable byte[] bytes, @Nullable String mime);

    private static native void nativeEval(long handle, String js);

    private static native void nativeLoadUrl(long handle, String url);

    private static native void nativeLoadHtml(long handle, String html);

    private static native void nativeSetVisible(long handle, boolean visible);

    private static native void nativeFocus(long handle);

    private static native void nativeSetBounds(long handle, int x, int y, int width, int height);

    private static native void nativeClose(long handle);

    /**
     * Runs a JavaScript snippet in the WebView's page context. Fire-and-forget.
     */
    public void eval(String js) {
        long handle = guard.handle.get();
        if (handle != 0L) {
            nativeEval(handle, js);
        }
    }

    /**
     * Navigates the WebView to the given URL.
     */
    public void loadUrl(String url) {
        long handle = guard.handle.get();
        if (handle != 0L) {
            nativeLoadUrl(handle, url);
        }
    }

    /**
     * Loads the given HTML string into the WebView.
     */
    public void loadHtml(String html) {
        long handle = guard.handle.get();
        if (handle != 0L) {
            nativeLoadHtml(handle, html);
        }
    }

    /**
     * Shows or hides the WebView window.
     */
    public void setVisible(boolean visible) {
        long handle = guard.handle.get();
        if (handle != 0L) {
            nativeSetVisible(handle, visible);
        }
    }

    /**
     * Moves keyboard focus to the WebView (needed for embedded webviews to receive keys).
     */
    public void focus() {
        long handle = guard.handle.get();
        if (handle != 0L) {
            nativeFocus(handle);
        }
    }

    /**
     * Moves and resizes the WebView. Embedded bounds use parent client-area pixels; standalone
     * window bounds use logical pixels.
     */
    public void setBounds(int x, int y, int width, int height) {
        long handle = guard.handle.get();
        if (handle != 0L) {
            nativeSetBounds(handle, x, y, width, height);
        }
    }

    /**
     * Destroys the native WebView and window. Idempotent; safe to call more than once and from
     * multiple threads.
     */
    @Override
    public void close() {
        guard.close();
    }

    /**
     * Ensures the native window is destroyed even if the caller forgets {@link #close()}.
     */
    private static final class CloseGuard implements AutoCloseable {
        private static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();

        private final AtomicLong handle;

        CloseGuard(long handle) {
            this.handle = new AtomicLong(handle);
            // Capture only the atomic handle so the registered action does not pin the guard.
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
     * Fluent builder mirroring {@code wry::WebViewBuilder}.
     */
    public static final class Builder {
        private String title = "FerricOxide";
        private int width = 960;
        private int height = 600;
        private @Nullable String url;
        private @Nullable String html;
        private boolean transparent;
        private boolean visible = true;
        private long parent;
        private @Nullable MessageHandler handler;
        private @Nullable CreationCallback creation;
        private @Nullable String protocol;
        private @Nullable ResourceHandler resource;

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        /**
         * URL to load. Wins over {@link #html(String)} when both are set.
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * Inline HTML to load. Only used when no URL is set.
         */
        public Builder html(String html) {
            this.html = html;
            return this;
        }

        public Builder transparent(boolean transparent) {
            this.transparent = transparent;
            return this;
        }

        public Builder visible(boolean visible) {
            this.visible = visible;
            return this;
        }

        /**
         * Embeds the WebView as a child of the given native parent: an HWND on Windows or an
         * NSView pointer on macOS. Pass {@code 0} (the default) for a standalone window;
         * standalone WebViews are not supported on macOS.
         */
        public Builder parent(long windowHandle) {
            this.parent = windowHandle;
            return this;
        }

        /**
         * Registers a Java callback invoked on the platform's native UI thread for every
         * {@code window.ipc.postMessage(...)} from the page.
         */
        public Builder ipc(MessageHandler handler) {
            this.handler = handler;
            return this;
        }

        /**
         * Registers an asynchronous creation-result callback. Without it, creation failures
         * are only visible as silently missing webviews.
         */
        public Builder onCreated(CreationCallback creation) {
            this.creation = creation;
            return this;
        }

        /**
         * Registers a custom URL protocol mapping {@code {protocol}://<namespace>/<path>} to
         * {@code namespace:path} resource locations, resolved by the given handler. Pass
         * {@code null} for either argument to disable the protocol.
         */
        public Builder resource(@Nullable String protocol, @Nullable ResourceHandler resource) {
            this.protocol = protocol;
            this.resource = resource;
            return this;
        }

        /**
         * Creates the native WebView. Returns immediately; the actual creation happens on the
         * dedicated event-loop thread (Windows/Linux) or the OS main queue (macOS), and its
         * outcome is reported through {@link #onCreated(CreationCallback)}.
         *
         * @throws IllegalStateException if the native library is not loaded, or the native
         *                               command dispatcher is not running
         */
        public NativeWebView build() {
            if (!NativeLoader.isLoaded()) {
                throw new IllegalStateException("ferric_oxide native library is not loaded");
            }
            long handle = nativeCreate(
                title, width, height, url, html, transparent, visible, parent, handler, creation, protocol, resource);
            if (handle == 0L) {
                // nativeCreate already threw a RuntimeException with details; reaching here
                // means the JVM deferred it. Defensive:
                throw new IllegalStateException("nativeCreate returned a null handle");
            }
            return new NativeWebView(handle);
        }
    }
}
