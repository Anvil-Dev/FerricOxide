package dev.anvilcraft.oxide.ferric.webui;

/**
 * Functional interface invoked from the native WebView IPC handler whenever the page calls
 * {@code window.ipc.postMessage("...")}.
 *
 * <p>The call originates from the native event-loop thread, not a Minecraft thread. Marshaling
 * back to the client thread is left to the receiver.
 */
@FunctionalInterface
public interface MessageHandler {
    void onMessage(String message);
}
