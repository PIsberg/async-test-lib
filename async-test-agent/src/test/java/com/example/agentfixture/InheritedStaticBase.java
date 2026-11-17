package com.example.agentfixture;

/**
 * Declares the static fields {@link InheritedStaticBean} reaches through a subclass (#1362).
 * javac names a static field instruction by its qualifying type, so {@code InheritedStaticSub.ready}
 * compiles to {@code GETSTATIC InheritedStaticSub.ready} although this class declares it.
 */
public class InheritedStaticBase {
    public static volatile boolean ready;
    public static int data;
    public static int unguarded;
}
