package com.example.agentfixture;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The shapes a class builds a {@code CompletableFuture} by hand in, for the construction weaver's
 * future hook (#1054), and one subclass whose superclass call must be left alone.
 */
public final class FutureConstructionSample {

    private FutureConstructionSample() {
    }

    /** {@return a future built and returned} */
    public static CompletableFuture<String> built() {
        return new CompletableFuture<>();
    }

    /** Builds a future and drops it: {@code NEW}, {@code DUP}, the constructor, {@code POP}. */
    public static void discarded() {
        new CompletableFuture<String>();
    }

    /** {@return two futures, each built inside another call's arguments} */
    public static List<CompletableFuture<String>> nested() {
        return List.of(new CompletableFuture<>(), new CompletableFuture<>());
    }

    /** {@return a future from a factory method, which no hook reports} */
    public static CompletableFuture<String> completed() {
        return CompletableFuture.completedFuture("done");
    }

    /** A subclass: its constructor's superclass call is not a {@code new CompletableFuture<>()}. */
    public static final class Child extends CompletableFuture<String> {
    }
}
