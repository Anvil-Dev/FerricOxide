package dev.anvilcraft.oxide.ferric.webui.bridge;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Two-way channel between the mod and the page loaded in a WebView.
 *
 * <p>Both directions are symmetric and support the same two interaction shapes:
 * <ul>
 *   <li><b>event</b> — one-way notification, no result ({@link #emit} / {@link #on});</li>
 *   <li><b>query</b> — request expecting a result ({@link #call} / {@link #handle}).</li>
 * </ul>
 *
 * <p>The page side mirrors this API as {@code ferric.emit / ferric.on / ferric.call /
 * ferric.handle}, installed by the bridge runtime before any page script runs.
 *
 * <p>Payloads are converted with Gson: outbound objects are serialized as-is, inbound payloads
 * are deserialized into the declared type, so a mistyped field surfaces as a null or a
 * conversion error instead of a silent fallback value.
 *
 * <p>Threading: {@link #emit} and {@link #call} may be invoked from any thread. Every handler
 * registered through {@link #on} / {@link #handle} / {@link #handleAsync} is invoked on the
 * Minecraft render thread, so implementations may touch game state directly.
 *
 * <p>Channel names share one namespace across events and queries; use a
 * {@code <mod-domain>.<action>} form to avoid collisions between mods.
 */
public interface WebBridge {
    /**
     * Sends a one-way event to the page. Listeners registered with {@code ferric.on(name, ...)}
     * receive the serialized payload.
     *
     * @param name    channel name
     * @param payload payload object, serialized with Gson; {@code null} sends a null payload
     */
    void emit(String name, @Nullable Object payload);

    /**
     * Sends a query to the page and completes with the reply payload.
     *
     * <p>The returned future completes exceptionally when the page has no handler for the
     * channel, the page handler throws or rejects, or the WebView is closed while the call is
     * still in flight.
     *
     * @param name       channel name; the page must answer it via {@code ferric.handle}
     * @param payload    payload object, serialized with Gson
     * @param resultType type the reply payload is deserialized into
     */
    <R> CompletableFuture<R> call(String name, @Nullable Object payload, Class<R> resultType);

    /**
     * Registers a listener for page events on the given channel. Multiple listeners per channel
     * are allowed and are invoked in registration order.
     *
     * @param name        channel name
     * @param payloadType type the event payload is deserialized into
     * @param handler     invoked on the render thread; receives {@code null} for a null payload
     */
    <T> WebBridge on(String name, Class<T> payloadType, Consumer<@Nullable T> handler);

    /**
     * Registers a listener for page events whose payload is irrelevant.
     */
    WebBridge on(String name, Runnable handler);

    /**
     * Answers page queries on the given channel. One handler per channel; registering a second
     * one for the same name is an error.
     *
     * @param name        channel name
     * @param payloadType type the query payload is deserialized into
     * @param handler     invoked on the render thread; its return value is serialized as the reply
     */
    <T, R> WebBridge handle(String name, Class<T> payloadType, Function<@Nullable T, R> handler);

    /**
     * Answers page queries whose result is produced asynchronously. The handler itself runs on
     * the render thread; the future it returns may complete on any thread.
     */
    <T, R> WebBridge handleAsync(
        String name,
        Class<T> payloadType,
        Function<@Nullable T, CompletableFuture<R>> handler
    );
}
