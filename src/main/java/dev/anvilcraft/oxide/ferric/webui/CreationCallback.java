package dev.anvilcraft.oxide.ferric.webui;

/**
 * Receives the asynchronous result of a {@link NativeWebView} creation.
 *
 * <p>The callback runs on the native event-loop thread; marshal back to the client thread
 * before touching the game.
 */
@FunctionalInterface
public interface CreationCallback {
    /**
     * Called once the native WebView was created or failed to be created.
     *
     * @param id    the webview handle (matches {@link NativeWebView}'s internal id)
     * @param error a description of the failure, or {@code null} on success
     */
    void onResult(long id, String error);
}
