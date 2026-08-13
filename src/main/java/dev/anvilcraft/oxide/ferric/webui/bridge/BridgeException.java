package dev.anvilcraft.oxide.ferric.webui.bridge;

/**
 * Failure of a {@link WebBridge#call} — the page reported an error, had no handler for the
 * channel, or replied with a payload that does not convert to the requested type.
 *
 * <p>Delivered as the cause of the returned future's completion, never thrown synchronously.
 */
public class BridgeException extends RuntimeException {
    public BridgeException(String message) {
        super(message);
    }

    public BridgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
