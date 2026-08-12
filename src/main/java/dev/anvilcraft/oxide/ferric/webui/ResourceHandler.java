package dev.anvilcraft.oxide.ferric.webui;

/**
 * Resolves resource-pack paths requested by the webview through the custom {@code ferric://}
 * protocol.
 *
 * <p>The native protocol handler maps {@code ferric://<namespace>/<path>} to a
 * {@code namespace:path} resource location and invokes {@link #resolve(String, long)} on the
 * platform's native UI thread. Implementations must marshal back to the render thread, resolve
 * the resource, and hand the bytes back through {@link NativeWebView#respondResource} so the
 * pending request completes.
 */
@FunctionalInterface
public interface ResourceHandler {
    /**
     * Resolves the given resource location.
     *
     * @param location  resource location in {@code namespace:path} form
     * @param requestId opaque id that must be passed back to
     *                  {@link NativeWebView#respondResource(long, byte[], String)} with the result
     */
    void resolve(String location, long requestId);
}
