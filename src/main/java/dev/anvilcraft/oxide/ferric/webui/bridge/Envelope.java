package dev.anvilcraft.oxide.ferric.webui.bridge;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import org.jspecify.annotations.Nullable;

/**
 * Wire format shared by both directions of the bridge.
 *
 * <p>Every message is a JSON object with a one-letter kind discriminator:
 * <pre>
 * {"k":"e","n":name,"p":payload}          event, one-way
 * {"k":"q","i":id,"n":name,"p":payload}   query, expects a reply
 * {"k":"r","i":id,"p":payload}            reply, success
 * {"k":"r","i":id,"e":message}            reply, failure
 * </pre>
 *
 * <p>Field names are kept short because every message crosses the JS/native boundary as text.
 * Gson omits null fields, so an event carries no {@code i} and a success reply no {@code e}.
 */
final class Envelope {
    static final String KIND_EVENT = "e";
    static final String KIND_QUERY = "q";
    static final String KIND_REPLY = "r";

    /** Envelope kind: one of {@link #KIND_EVENT}, {@link #KIND_QUERY}, {@link #KIND_REPLY}. */
    @SerializedName("k")
    @Nullable String kind;

    /** Channel name; present on events and queries. */
    @SerializedName("n")
    @Nullable String name;

    /** Call id correlating a query with its reply; absent on events. */
    @SerializedName("i")
    @Nullable Long id;

    /** Business payload; absent or JSON null when the message carries no data. */
    @SerializedName("p")
    @Nullable JsonElement payload;

    /** Failure description; present only on failed replies, mutually exclusive with payload. */
    @SerializedName("e")
    @Nullable String error;

    private Envelope() {
    }

    static Envelope event(String name, @Nullable JsonElement payload) {
        Envelope envelope = new Envelope();
        envelope.kind = KIND_EVENT;
        envelope.name = name;
        envelope.payload = payload;
        return envelope;
    }

    static Envelope query(long id, String name, @Nullable JsonElement payload) {
        Envelope envelope = new Envelope();
        envelope.kind = KIND_QUERY;
        envelope.id = id;
        envelope.name = name;
        envelope.payload = payload;
        return envelope;
    }

    static Envelope success(long id, @Nullable JsonElement payload) {
        Envelope envelope = new Envelope();
        envelope.kind = KIND_REPLY;
        envelope.id = id;
        envelope.payload = payload;
        return envelope;
    }

    static Envelope failure(long id, String error) {
        Envelope envelope = new Envelope();
        envelope.kind = KIND_REPLY;
        envelope.id = id;
        envelope.error = error;
        return envelope;
    }
}
