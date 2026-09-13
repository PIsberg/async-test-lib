package com.example.agentfixture;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * The same method references as {@link SharedThroughMethodReferenceBean}, on objects no two
 * threads share, plus a serializable reference that must survive the weaver untouched.
 *
 * <p>A serializable lambda is rebuilt on deserialization by a generated
 * {@code $deserializeLambda$} that compares the implementation method it was compiled against with
 * the one recorded in the stream. Rewriting that method to a hook would make every such lambda in
 * a woven class fail to deserialize, so the weaver must leave it alone, and
 * {@link #roundTripASerializableReference()} fails if it does not.
 */
public class ConfinedThroughMethodReferenceBean {

    /** Appends to a builder this call made, through a bound reference. */
    public void appendThroughBoundReference() {
        StringBuilder mine = new StringBuilder();
        Consumer<String> sink = mine::append;
        sink.accept("x");
    }

    /** Appends to a builder this call made, through an unbound reference. */
    public void appendThroughUnboundReference() {
        BiFunction<StringBuilder, String, StringBuilder> append = StringBuilder::append;
        append.apply(new StringBuilder(), "y");
    }

    /** Takes and releases a fresh lock, both through references. */
    public void balanceALockThroughReferences() {
        ReentrantLock lock = new ReentrantLock();
        Runnable take = lock::lock;
        Runnable give = lock::unlock;
        take.run();
        give.run();
    }

    /**
     * Serializes and deserializes a method reference, then uses it.
     *
     * @return {@code "deserialized"} once the reference came back and accepted a value
     */
    public String roundTripASerializableReference() {
        StringBuilder mine = new StringBuilder();
        Consumer<String> sink = (Consumer<String> & Serializable) mine::append;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(sink);
            }
            try (ObjectInputStream in = new ObjectInputStream(
                    new ByteArrayInputStream(bytes.toByteArray()))) {
                @SuppressWarnings("unchecked")
                Consumer<String> back = (Consumer<String>) in.readObject();
                back.accept("z");
                return "deserialized";
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
