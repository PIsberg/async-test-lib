package com.example.groovyrunner

import groovy.transform.CompileStatic

/**
 * Two counters, one incremented bare and one under a lock. Statically compiled so each increment
 * is a field instruction the agent can weave: dynamic Groovy reaches a field through its meta
 * class, by reflection, where no woven instruction sees it.
 */
@CompileStatic
class Counters {
    private final Object lock = new Object()
    int racing
    int guarded

    void incrementRacing() {
        racing++
    }

    void incrementGuarded() {
        synchronized (lock) {
            guarded++
        }
    }
}
