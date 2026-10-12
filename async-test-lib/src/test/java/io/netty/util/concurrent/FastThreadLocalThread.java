package io.netty.util.concurrent;

/**
 * Test stand-in for Netty's thread class, by its real name, with the {@code permitBlockingCalls()}
 * query BlockHound's Netty integration asks too.
 */
public class FastThreadLocalThread extends Thread {

    private final boolean permitBlocking;

    public FastThreadLocalThread(Runnable task, boolean permitBlocking) {
        super(task);
        this.permitBlocking = permitBlocking;
    }

    public boolean permitBlockingCalls() {
        return permitBlocking;
    }
}
