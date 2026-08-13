package dev.anvilcraft.oxide.ferric.webui.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the full envelope protocol through a bridge whose JS evaluator and render-thread
 * dispatcher are replaced by test doubles: outbound JS is captured as text, inbound messages are
 * fed in as the raw strings the page would post.
 */
class WebBridgeImplTest {
    private final List<String> evaluated = new ArrayList<>();
    /** Stands in for the render thread; runs inline so assertions see completed work. */
    private final WebBridgeImpl bridge = new WebBridgeImpl(evaluated::add, Runnable::run);

    record Point(int x, int y) {
    }

    record Greeting(String text) {
    }

    /** Extracts the envelope from the {@code window.__ferric.accept(<json>);} wrapper. */
    private JsonObject lastEnvelope() {
        String js = evaluated.get(evaluated.size() - 1);
        String prefix = "window.__ferric.accept(";
        assertTrue(js.startsWith(prefix), js);
        assertTrue(js.endsWith(");"), js);
        String json = js.substring(prefix.length(), js.length() - 2);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void emitSendsAnEventEnvelope() {
        bridge.emit("demo.moved", new Point(3, 4));

        JsonObject envelope = lastEnvelope();
        assertEquals("e", envelope.get("k").getAsString());
        assertEquals("demo.moved", envelope.get("n").getAsString());
        assertEquals(3, envelope.getAsJsonObject("p").get("x").getAsInt());
        assertEquals(4, envelope.getAsJsonObject("p").get("y").getAsInt());
        assertNull(envelope.get("i"), "events carry no call id");
    }

    @Test
    void inboundEventIsDecodedIntoTheDeclaredType() {
        AtomicReference<Point> seen = new AtomicReference<>();
        bridge.on("page.moved", Point.class, seen::set);

        bridge.accept("{\"k\":\"e\",\"n\":\"page.moved\",\"p\":{\"x\":7,\"y\":9}}");

        assertEquals(new Point(7, 9), seen.get());
    }

    @Test
    void inboundEventReachesEveryListenerInOrder() {
        List<String> order = new ArrayList<>();
        bridge.on("page.tick", () -> order.add("first"));
        bridge.on("page.tick", () -> order.add("second"));

        bridge.accept("{\"k\":\"e\",\"n\":\"page.tick\"}");

        assertEquals(List.of("first", "second"), order);
    }

    @Test
    void inboundQueryIsAnsweredWithTheHandlerResult() {
        bridge.handle("page.greet", Point.class, point -> new Greeting("at " + point.x() + "," + point.y()));

        bridge.accept("{\"k\":\"q\",\"i\":42,\"n\":\"page.greet\",\"p\":{\"x\":1,\"y\":2}}");

        JsonObject reply = lastEnvelope();
        assertEquals("r", reply.get("k").getAsString());
        assertEquals(42, reply.get("i").getAsInt());
        assertEquals("at 1,2", reply.getAsJsonObject("p").get("text").getAsString());
        assertNull(reply.get("e"), "successful replies carry no error");
    }

    @Test
    void inboundQueryWithoutHandlerRepliesWithAnError() {
        bridge.accept("{\"k\":\"q\",\"i\":8,\"n\":\"page.missing\"}");

        JsonObject reply = lastEnvelope();
        assertEquals("r", reply.get("k").getAsString());
        assertEquals(8, reply.get("i").getAsInt());
        assertEquals("no handler: page.missing", reply.get("e").getAsString());
    }

    @Test
    void handlerFailureBecomesAnErrorReply() {
        bridge.handle("page.boom", Void.class, ignored -> {
            throw new IllegalStateException("handler exploded");
        });

        bridge.accept("{\"k\":\"q\",\"i\":5,\"n\":\"page.boom\"}");

        JsonObject reply = lastEnvelope();
        assertEquals(5, reply.get("i").getAsInt());
        assertEquals("handler exploded", reply.get("e").getAsString());
    }

    @Test
    void asyncHandlerRepliesWhenItsFutureCompletes() {
        CompletableFuture<Greeting> pending = new CompletableFuture<>();
        bridge.handleAsync("page.slow", Void.class, ignored -> pending);

        bridge.accept("{\"k\":\"q\",\"i\":11,\"n\":\"page.slow\"}");
        assertTrue(evaluated.isEmpty(), "nothing is sent before the future completes");

        pending.complete(new Greeting("late"));

        JsonObject reply = lastEnvelope();
        assertEquals(11, reply.get("i").getAsInt());
        assertEquals("late", reply.getAsJsonObject("p").get("text").getAsString());
    }

    @Test
    void registeringTwoHandlersForOneChannelIsRejected() {
        bridge.handle("page.only", Void.class, ignored -> "first");

        IllegalStateException error = assertThrows(
            IllegalStateException.class, () -> bridge.handle("page.only", Void.class, ignored -> "second"));
        assertTrue(error.getMessage().contains("page.only"), error.getMessage());
    }

    @Test
    void outboundCallResolvesWithTheDecodedReply() throws Exception {
        CompletableFuture<Greeting> future = bridge.call("page.ask", new Point(1, 1), Greeting.class);

        long id = lastEnvelope().get("i").getAsLong();
        assertEquals("q", lastEnvelope().get("k").getAsString());
        bridge.accept("{\"k\":\"r\",\"i\":" + id + ",\"p\":{\"text\":\"hi\"}}");

        assertEquals(new Greeting("hi"), future.get());
    }

    @Test
    void outboundCallFailsWhenThePageReportsAnError() {
        CompletableFuture<Greeting> future = bridge.call("page.ask", null, Greeting.class);
        long id = lastEnvelope().get("i").getAsLong();

        bridge.accept("{\"k\":\"r\",\"i\":" + id + ",\"e\":\"page said no\"}");

        ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
        BridgeException cause = assertInstanceOf(BridgeException.class, thrown.getCause());
        assertTrue(cause.getMessage().contains("page said no"), cause.getMessage());
    }

    @Test
    void concurrentCallsAreCorrelatedByTheirOwnIds() throws Exception {
        CompletableFuture<Greeting> first = bridge.call("page.ask", null, Greeting.class);
        long firstId = lastEnvelope().get("i").getAsLong();
        CompletableFuture<Greeting> second = bridge.call("page.ask", null, Greeting.class);
        long secondId = lastEnvelope().get("i").getAsLong();

        // Reply out of order: the later call is answered first.
        bridge.accept("{\"k\":\"r\",\"i\":" + secondId + ",\"p\":{\"text\":\"second\"}}");
        bridge.accept("{\"k\":\"r\",\"i\":" + firstId + ",\"p\":{\"text\":\"first\"}}");

        assertEquals(new Greeting("first"), first.get());
        assertEquals(new Greeting("second"), second.get());
    }

    @Test
    void closeFailsInFlightCalls() {
        CompletableFuture<Greeting> future = bridge.call("page.ask", null, Greeting.class);

        bridge.close();

        ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }

    @Test
    void callAfterCloseFailsImmediatelyInsteadOfHanging() {
        bridge.close();
        evaluated.clear();

        CompletableFuture<Greeting> future = bridge.call("page.ask", null, Greeting.class);

        assertTrue(future.isCompletedExceptionally());
        assertTrue(evaluated.isEmpty(), "a closed bridge sends nothing");
    }

    @Test
    void malformedInboundMessagesAreDroppedWithoutAffectingLaterOnes() {
        AtomicReference<Point> seen = new AtomicReference<>();
        bridge.on("page.moved", Point.class, seen::set);

        bridge.accept("not json at all");
        bridge.accept("{\"k\":\"zzz\",\"n\":\"page.moved\"}");
        bridge.accept("{\"n\":\"page.moved\"}");
        assertNull(seen.get());

        bridge.accept("{\"k\":\"e\",\"n\":\"page.moved\",\"p\":{\"x\":1,\"y\":2}}");
        assertEquals(new Point(1, 2), seen.get());
    }

    @Test
    void payloadlessEventDecodesToNull() {
        AtomicReference<Point> seen = new AtomicReference<>(new Point(0, 0));
        bridge.on("page.moved", Point.class, seen::set);

        bridge.accept("{\"k\":\"e\",\"n\":\"page.moved\"}");

        assertNull(seen.get());
    }

    /**
     * The demo wiring declares payload-less queries as {@code Void.class}; a page calling
     * {@code ferric.call(name)} sends a null payload, which must reach the handler as null
     * rather than failing to convert.
     */
    @Test
    void payloadlessQueryDeclaredAsVoidIsAnswered() {
        bridge.handle("page.who", Void.class, ignored -> new Greeting("Steve"));

        bridge.accept("{\"k\":\"q\",\"i\":3,\"n\":\"page.who\",\"p\":null}");

        JsonObject reply = lastEnvelope();
        assertNull(reply.get("e"), "a null payload must not fail conversion");
        assertEquals("Steve", reply.getAsJsonObject("p").get("text").getAsString());
    }

    @Test
    void initScriptSubstitutesTheResourceBase() {
        String script = WebBridgeImpl.initScript("var b = '__RESOURCE_BASE__';", "ferric://");

        assertEquals("var b = 'ferric://';", script);
    }

    @Test
    void initScriptRejectsARuntimeWithoutThePlaceholder() {
        assertThrows(IllegalArgumentException.class, () -> WebBridgeImpl.initScript("var b = 0;", "ferric://"));
    }

    /**
     * A second occurrence — a mention in a comment, say — makes the substitution ambiguous for
     * any consumer that replaces only the first match, so it must be rejected outright.
     */
    @Test
    void initScriptRejectsADuplicatedPlaceholder() {
        assertThrows(
            IllegalArgumentException.class,
            () -> WebBridgeImpl.initScript("// __RESOURCE_BASE__\nvar b = '__RESOURCE_BASE__';", "ferric://"));
    }

    /** The shipped runtime must satisfy the exactly-once rule the loader enforces. */
    @Test
    void shippedRuntimeCarriesExactlyOnePlaceholder() throws Exception {
        String runtime = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/resources/assets/ferric_oxide/webui/bridge.js"));

        String script = WebBridgeImpl.initScript(runtime, "ferric://");

        assertTrue(script.contains("var RESOURCE_BASE = 'ferric://';"), "resource base must be substituted");
        assertTrue(script.indexOf("__RESOURCE_BASE__") < 0, "no placeholder may survive substitution");
    }

    @Test
    void outboundPayloadIsEscapedSoItCannotBreakOutOfTheJsExpression() {
        bridge.emit("demo.text", new Greeting("</script><script>alert(1)</script>"));

        String js = evaluated.get(0);
        assertTrue(js.indexOf("</script>") < 0, "raw script tags must not reach the page: " + js);
        // Still the same value once parsed.
        assertEquals(
            "</script><script>alert(1)</script>",
            lastEnvelope().getAsJsonObject("p").get("text").getAsString());
    }

    @Test
    void bridgeIsUsableThroughItsInterface() {
        WebBridge api = bridge;
        assertSame(api, api.on("page.tick", () -> {
        }));
    }
}
