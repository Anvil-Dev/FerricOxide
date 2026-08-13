package dev.anvilcraft.oxide.ferric.webui;

/**
 * Functional interface invoked from the native WebView IPC handler whenever the page calls
 * {@code window.ipc.postMessage("...")}.
 *
 * <p>The call originates from the native event-loop thread, not a Minecraft thread. Marshaling
 * back to the client thread is left to the receiver.
 *
 * <p>This is the raw transport hook. Mod code should use
 * {@link dev.anvilcraft.oxide.ferric.webui.bridge.WebBridge} through {@link WebUi#bridge()}
 * instead, which owns this callback and adds typed events and queries on top of it.
 */
@FunctionalInterface
public interface MessageHandler {
    void onMessage(String message);
}
