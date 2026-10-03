package com.example.agentfixture;

/**
 * Two volatile fields that share a simple name, {@code ready} here and in {@link Sub}, and one
 * declared only here, {@code inherited}, for #813 item 5.
 *
 * <p>javac names a field instruction by the static type of its qualifier, so {@code payload} and
 * {@code inherited} written from {@link Sub}'s methods carry {@code Sub} as owner while a read
 * through a {@code ShadowedVolatileBean} reference carries this class. The agent has to name each
 * field by the class that declares it for those to meet, and must not let {@code Sub.ready} stand
 * in for this class's {@code ready}.
 */
public class ShadowedVolatileBean {

    /** Set in the constructor, which the agent does not weave, so no release backs it. */
    public volatile boolean ready = true;

    public volatile boolean inherited;

    public int payload;

    /** Shadows {@link ShadowedVolatileBean#ready}: another field of the same object. */
    public static class Sub extends ShadowedVolatileBean {

        public volatile boolean ready;

        /** Writes the payload and publishes it through this class's own {@code ready}. */
        public void publishThroughOwnReady() {
            payload = 1;
            ready = true;
        }

        /** Writes the payload and publishes it through the inherited field, named via {@code Sub}. */
        public void publishThroughInherited() {
            payload = 1;
            inherited = true;
        }

        /** Reads the superclass's {@code ready}, which nobody released, then updates the payload. */
        public int bumpAfterBaseReady() {
            ShadowedVolatileBean base = this;
            if (!base.ready) {
                return -1;
            }
            payload = payload + 1;
            return payload;
        }

        /** Reads the inherited flag through the superclass's type, then updates the payload. */
        public int bumpAfterInherited() {
            ShadowedVolatileBean base = this;
            if (!base.inherited) {
                return -1;
            }
            payload = payload + 1;
            return payload;
        }
    }
}
