package dev.anvilcraft.oxide.ferric.webui.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Bridge implementation over a JavaScript evaluator.
 *
 * <p>Deliberately decoupled from {@code NativeWebView}: outbound traffic is handed to a
 * JS-evaluating {@link Consumer} and handler dispatch to an {@link Executor}, so the whole
 * protocol can be driven in tests without a native WebView or a running game.
 */
public final class WebBridgeImpl implements WebBridge {
    // The bridge is pure protocol code with no game dependency, so it uses SLF4J directly rather
    // than Mojang's LogUtils; that keeps it exercisable in plain unit tests.
    private static final Logger LOGGER = LoggerFactory.getLogger(WebBridgeImpl.class);
    /**
     * Gson escapes HTML characters and the JS line separators U+2028/U+2029 by default, which is
     * exactly what makes its output safe to embed in the {@code window.__ferric.accept(...)}
     * expression below.
     */
    private static final Gson GSON = new Gson();

    /** Token in {@code bridge.js} replaced with the platform's resource URL prefix. */
    private static final String PLACEHOLDER = "__RESOURCE_BASE__";

    private final Consumer<String> jsEvaluator;
    private final Executor dispatcher;

    private final Map<String, List<Consumer<@Nullable JsonElement>>> listeners = new ConcurrentHashMap<>();
    private final Map<String, QueryHandler> handlers = new ConcurrentHashMap<>();
    private final Map<Long, PendingCall<?>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextCallId = new AtomicLong(1);
    private volatile boolean closed;

    /**
     * @param jsEvaluator evaluates a JS snippet in the page; called from arbitrary threads
     * @param dispatcher  runs handler dispatch on the render thread
     */
    public WebBridgeImpl(Consumer<String> jsEvaluator, Executor dispatcher) {
        this.jsEvaluator = jsEvaluator;
        this.dispatcher = dispatcher;
    }

    /**
     * The bridge runtime source with the resource base substituted, ready to be installed as the
     * WebView initialization script.
     *
     * <p>The placeholder must occur exactly once. A second occurrence — a mention in a comment,
     * say — would make the substitution ambiguous for any consumer replacing only the first match,
     * so it is rejected rather than silently accepted.
     *
     * @param runtimeSource contents of {@code webui/bridge.js}
     * @param resourceBase  platform-correct resource URL prefix ending in {@code /}
     */
    public static String initScript(String runtimeSource, String resourceBase) {
        int first = runtimeSource.indexOf(PLACEHOLDER);
        if (first < 0) {
            throw new IllegalArgumentException("bridge runtime has no " + PLACEHOLDER + " placeholder");
        }
        if (runtimeSource.indexOf(PLACEHOLDER, first + PLACEHOLDER.length()) >= 0) {
            throw new IllegalArgumentException(
                "bridge runtime must contain " + PLACEHOLDER + " exactly once");
        }
        return runtimeSource.replace(PLACEHOLDER, resourceBase);
    }

    // -----------------------------------------------------------------------
    // Java -> page
    // -----------------------------------------------------------------------

    @Override
    public void emit(String name, @Nullable Object payload) {
        deliver(Envelope.event(name, GSON.toJsonTree(payload)));
    }

    @Override
    public <R> CompletableFuture<R> call(String name, @Nullable Object payload, Class<R> resultType) {
        CompletableFuture<R> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new IllegalStateException("bridge is closed: " + name));
            return future;
        }
        long id = nextCallId.getAndIncrement();
        pending.put(id, new PendingCall<>(name, resultType, future));
        deliver(Envelope.query(id, name, GSON.toJsonTree(payload)));
        return future;
    }

    private void deliver(Envelope envelope) {
        // The page-side entry point takes a parsed object, so the serialized envelope is embedded
        // as a JS object literal instead of building JS source per call site.
        jsEvaluator.accept("window.__ferric.accept(" + GSON.toJson(envelope) + ");");
    }

    // -----------------------------------------------------------------------
    // Page -> Java, registration
    // -----------------------------------------------------------------------

    @Override
    public <T> WebBridge on(String name, Class<T> payloadType, Consumer<@Nullable T> handler) {
        listeners.computeIfAbsent(name, key -> new CopyOnWriteArrayList<>())
            .add(payload -> handler.accept(decode(payload, payloadType)));
        return this;
    }

    @Override
    public WebBridge on(String name, Runnable handler) {
        listeners.computeIfAbsent(name, key -> new CopyOnWriteArrayList<>())
            .add(payload -> handler.run());
        return this;
    }

    @Override
    public <T, R> WebBridge handle(String name, Class<T> payloadType, Function<@Nullable T, R> handler) {
        return handleAsync(
            name, payloadType, payload -> CompletableFuture.completedFuture(handler.apply(payload)));
    }

    @Override
    public <T, R> WebBridge handleAsync(
        String name,
        Class<T> payloadType,
        Function<@Nullable T, CompletableFuture<R>> handler
    ) {
        QueryHandler previous = handlers.putIfAbsent(
            name, payload -> handler.apply(decode(payload, payloadType)).thenApply(GSON::toJsonTree));
        if (previous != null) {
            throw new IllegalStateException("query handler already registered for channel " + name);
        }
        return this;
    }

    private <T> @Nullable T decode(@Nullable JsonElement payload, Class<T> type) {
        if (payload == null || payload.isJsonNull()) {
            return null;
        }
        return GSON.fromJson(payload, type);
    }

    // -----------------------------------------------------------------------
    // Page -> Java, inbound dispatch
    // -----------------------------------------------------------------------

    /**
     * Handles one raw {@code window.ipc.postMessage} body. Called on the native UI thread;
     * handler invocation is marshaled to the render thread through the dispatcher.
     */
    public void accept(String raw) {
        Envelope envelope;
        try {
            envelope = GSON.fromJson(raw, Envelope.class);
        } catch (JsonSyntaxException e) {
            LOGGER.error("Dropping unparseable bridge message: {}", raw, e);
            return;
        }
        if (envelope == null || envelope.kind == null) {
            LOGGER.error("Dropping bridge message without a kind: {}", raw);
            return;
        }
        switch (envelope.kind) {
            case Envelope.KIND_EVENT -> dispatchEvent(envelope);
            case Envelope.KIND_QUERY -> dispatchQuery(envelope);
            case Envelope.KIND_REPLY -> dispatchReply(envelope);
            default -> LOGGER.error("Dropping bridge message with unknown kind '{}': {}", envelope.kind, raw);
        }
    }

    private void dispatchEvent(Envelope envelope) {
        String name = envelope.name;
        if (name == null) {
            LOGGER.error("Dropping bridge event without a channel name");
            return;
        }
        List<Consumer<@Nullable JsonElement>> registered = listeners.get(name);
        if (registered == null || registered.isEmpty()) {
            LOGGER.warn("No listener registered for bridge event '{}'", name);
            return;
        }
        dispatcher.execute(() -> {
            for (Consumer<@Nullable JsonElement> listener : registered) {
                try {
                    listener.accept(envelope.payload);
                } catch (RuntimeException e) {
                    LOGGER.error("Listener for bridge event '{}' failed", name, e);
                }
            }
        });
    }

    private void dispatchQuery(Envelope envelope) {
        String name = envelope.name;
        Long id = envelope.id;
        if (name == null || id == null) {
            LOGGER.error("Dropping bridge query without a channel name or call id");
            return;
        }
        QueryHandler handler = handlers.get(name);
        if (handler == null) {
            LOGGER.warn("No handler registered for bridge query '{}'", name);
            deliver(Envelope.failure(id, "no handler: " + name));
            return;
        }
        dispatcher.execute(() -> {
            CompletableFuture<@Nullable JsonElement> result;
            try {
                result = handler.apply(envelope.payload);
            } catch (RuntimeException e) {
                LOGGER.error("Handler for bridge query '{}' threw", name, e);
                deliver(Envelope.failure(id, describe(e)));
                return;
            }
            result.whenComplete((payload, error) -> {
                if (error == null) {
                    deliver(Envelope.success(id, payload));
                } else {
                    LOGGER.error("Handler for bridge query '{}' failed", name, error);
                    deliver(Envelope.failure(id, describe(error)));
                }
            });
        });
    }

    private void dispatchReply(Envelope envelope) {
        Long id = envelope.id;
        if (id == null) {
            LOGGER.error("Dropping bridge reply without a call id");
            return;
        }
        PendingCall<?> call = pending.remove(id);
        if (call == null) {
            LOGGER.warn("Dropping bridge reply for unknown call id {}", id);
            return;
        }
        // Deserialization happens on the render thread so that a converter touching game state
        // sees the same threading guarantees as the handlers.
        dispatcher.execute(() -> call.complete(envelope, this));
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }

    /**
     * Fails every in-flight page query so no caller is left waiting on a dead WebView, and drops
     * all registrations. Idempotent.
     */
    public void close() {
        closed = true;
        listeners.clear();
        handlers.clear();
        pending.values().removeIf(call -> {
            call.fail(new IllegalStateException("webview closed before '" + call.name + "' replied"));
            return true;
        });
    }

    /** Type-erased query handler: JSON payload in, JSON reply out. */
    @FunctionalInterface
    private interface QueryHandler {
        CompletableFuture<@Nullable JsonElement> apply(@Nullable JsonElement payload);
    }

    /** An outbound query awaiting its reply, retaining the type its payload converts to. */
    private record PendingCall<R>(String name, Class<R> resultType, CompletableFuture<R> future) {
        void complete(Envelope reply, WebBridgeImpl bridge) {
            if (reply.error != null) {
                future.completeExceptionally(
                    new BridgeException("page failed to answer '" + name + "': " + reply.error));
                return;
            }
            try {
                future.complete(bridge.decode(reply.payload, resultType));
            } catch (RuntimeException e) {
                future.completeExceptionally(
                    new BridgeException("cannot convert reply of '" + name + "' to " + resultType.getSimpleName(), e));
            }
        }

        void fail(Throwable error) {
            future.completeExceptionally(error);
        }
    }
}
