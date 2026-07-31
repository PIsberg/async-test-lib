package se.deversity.asynctest.example.service;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Encodes outbound text and decodes inbound bytes for a wire protocol.
 *
 * <p>{@code Charset} is thread-safe and immutable. {@link CharsetEncoder} and
 * {@link CharsetDecoder} are neither — they are state machines. Each one tracks whether it
 * is mid-surrogate-pair, whether {@code flush()} has run, and what its malformed-input and
 * unmappable-character actions are, and {@code reset()} exists precisely because that state
 * survives between calls. The javadoc spells out the protocol: reset, then one or more
 * {@code encode()} calls, then a final {@code encode()} with {@code endOfInput = true}, then
 * {@code flush()}. Two threads interleaving that sequence on one coder garble the output or
 * throw {@link IllegalStateException} for a state transition neither of them asked for.
 *
 * <p>Coders get cached because {@code Charset.newEncoder()} allocates. It allocates very
 * little.
 */
public final class TextEncodingService {

    /** BUG: one encoder, reused by every thread that sends a message. */
    private final CharsetEncoder sharedEncoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);

    /** BUG: one decoder, likewise. */
    private final CharsetDecoder sharedDecoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);

    /**
     * Encodes {@code text} using the shared encoder — the reset-then-encode pair is exactly
     * the sequence a second thread breaks.
     */
    public byte[] encode(String text) throws CharacterCodingException {
        sharedEncoder.reset();
        ByteBuffer encoded = sharedEncoder.encode(CharBuffer.wrap(text));
        byte[] out = new byte[encoded.remaining()];
        encoded.get(out);
        return out;
    }

    /** Decodes {@code bytes} using the shared decoder. Same hazard, other direction. */
    public String decode(byte[] bytes) throws CharacterCodingException {
        sharedDecoder.reset();
        return sharedDecoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    /**
     * The fix: a coder per call. {@code newEncoder()} is a small allocation, and a
     * {@code ThreadLocal<CharsetEncoder>} covers the case where it is not.
     */
    public byte[] encodeSafely(String text) throws CharacterCodingException {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer encoded = encoder.encode(CharBuffer.wrap(text));
        byte[] out = new byte[encoded.remaining()];
        encoded.get(out);
        return out;
    }

    /**
     * The simplest fix of all when you do not need the coder's error actions:
     * {@code String.getBytes(Charset)} allocates its own coder internally.
     */
    public byte[] encodeSimply(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
