package se.deversity.asynctest.example.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serializes response bodies to JSON, using one mapper for the whole application.
 *
 * <p>Sharing the mapper is the <em>recommended</em> practice — Jackson's {@code ObjectMapper}
 * is documented as thread-safe for {@code readValue}/{@code writeValue}, and it builds
 * serializer caches that only pay off when the instance is long-lived. Gson, Moshi and
 * kotlinx.serialization all say the same thing about their equivalents.
 *
 * <p>The guarantee has a boundary, and the boundary is <strong>configuration</strong>. The
 * {@code configure()} / {@code setDateFormat()} / {@code registerModule()} family mutates
 * fields the serialization path reads without synchronization, and it invalidates caches
 * that other threads are in the middle of using. Jackson's own javadoc puts it plainly:
 * configuration is expected to happen once, before the mapper is shared.
 *
 * <p>So the bug is never "we shared the mapper". It is "we shared the mapper, and then
 * something reconfigured it" — a per-request date format, a feature toggled from a lazily
 * initialised code path, a test helper that flips pretty-printing on. The window is small
 * and the corruption is intermittent, which is the worst combination to debug.
 *
 * <p>This example models a mapper with a hand-rolled one so it depends on no JSON library;
 * the detector takes the mapper as {@code Object} for the same reason and applies unchanged
 * to a real {@code ObjectMapper}.
 */
public final class JsonSerializationService {

    /** Fine, and recommended: one long-lived mapper for the application. */
    private final MiniJsonMapper sharedMapper = new MiniJsonMapper();

    /** Safe: read-only use of the shared mapper from any number of threads. */
    public String serialize(Map<String, Object> body) {
        return sharedMapper.write(body);
    }

    /**
     * BUG: reconfigures the shared mapper per request. Every thread already serializing
     * through it sees the change mid-flight, and its serializer cache is dropped underneath
     * them.
     */
    public String serializeWithDateFormat(Map<String, Object> body, String dateFormat) {
        sharedMapper.setDateFormat(dateFormat);
        return sharedMapper.write(body);
    }

    /**
     * The fix: configure once at construction, then never mutate. When a request genuinely
     * needs different settings, derive a copy — Jackson's {@code ObjectMapper.copy()} and
     * {@code ObjectWriter}/{@code ObjectReader} views exist for this.
     */
    public String serializeWithDateFormatSafely(Map<String, Object> body, String dateFormat) {
        MiniJsonMapper perRequest = sharedMapper.copy();
        perRequest.setDateFormat(dateFormat);
        return perRequest.write(body);
    }

    public MiniJsonMapper sharedMapper() {
        return sharedMapper;
    }

    /**
     * A stand-in for {@code ObjectMapper}: mutable configuration, plus a cache that
     * reconfiguration invalidates. Enough to reproduce the shape, not a JSON library.
     */
    public static final class MiniJsonMapper {

        private volatile String dateFormat = "yyyy-MM-dd";

        /** Models the serializer cache a real mapper builds and drops on reconfiguration. */
        private final Map<String, String> serializerCache = new ConcurrentHashMap<>();

        public void setDateFormat(String dateFormat) {
            this.dateFormat = dateFormat;
            this.serializerCache.clear();          // the part other threads are using
        }

        public String dateFormat() {
            return dateFormat;
        }

        public int cacheSize() {
            return serializerCache.size();
        }

        public MiniJsonMapper copy() {
            MiniJsonMapper copy = new MiniJsonMapper();
            copy.dateFormat = this.dateFormat;
            return copy;
        }

        public String write(Map<String, Object> body) {
            StringBuilder out = new StringBuilder("{");
            Map<String, Object> ordered = new LinkedHashMap<>(body);
            boolean first = true;
            for (Map.Entry<String, Object> entry : ordered.entrySet()) {
                serializerCache.computeIfAbsent(entry.getKey(), key -> "serializer:" + key);
                if (!first) {
                    out.append(',');
                }
                out.append('"').append(entry.getKey()).append("\":\"")
                   .append(entry.getValue()).append('"');
                first = false;
            }
            return out.append(",\"_dateFormat\":\"").append(dateFormat).append("\"}").toString();
        }
    }
}
