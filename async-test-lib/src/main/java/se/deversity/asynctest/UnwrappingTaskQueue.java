package se.deversity.asynctest;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.AgentHandOffHooks.HandedTask;

import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A {@code ThreadPoolExecutor}'s queue as woven code reads it, where the pool queues the wrappers a
 * woven {@code execute} makes (#1173): each element is the task the caller gave, a task is found
 * and removed by that task, and one taken out of the queue is told to the schedule as never going
 * to run, as {@code shutdownNow} and {@code remove} already do. What is added goes in unchanged, so
 * it runs unwrapped, as it would without the agent.
 *
 * <p>Every operation is the pool queue's own, so its thread safety is the queue's.
 */
final class UnwrappingTaskQueue extends AbstractQueue<Runnable> implements BlockingQueue<Runnable> {

    private final BlockingQueue<Runnable> queue;

    UnwrappingTaskQueue(BlockingQueue<Runnable> queue) {
        this.queue = queue;
    }

    /** {@return the task the caller gave, for an element of the pool's queue} */
    private static @Nullable Runnable given(@Nullable Runnable element) {
        return element == null ? null : AgentConcurrencyUtilHooks.poolTask(element);
    }

    /** {@return {@code element}, given back by a removal: a wrapped task will never run} */
    private static @Nullable Runnable taken(@Nullable Runnable element) {
        if (element instanceof HandedTask handed) {
            handed.abandoned();
        }
        return given(element);
    }

    /** {@return whether {@code element} is, or wraps, {@code task}} */
    private static boolean matches(Runnable element, @Nullable Object task) {
        return Objects.equals(task, element)
                || element instanceof HandedTask handed && Objects.equals(task, handed.task);
    }

    @Override
    public Iterator<Runnable> iterator() {
        Iterator<Runnable> elements = queue.iterator();
        return new Iterator<>() {
            private @Nullable Runnable last;

            @Override
            public boolean hasNext() {
                return elements.hasNext();
            }

            @Override
            public Runnable next() {
                Runnable element = elements.next();
                last = element;
                return AgentConcurrencyUtilHooks.poolTask(element);
            }

            @Override
            public void remove() {
                elements.remove();
                taken(last);
            }
        };
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public boolean contains(@Nullable Object task) {
        for (Runnable element : queue) {
            if (matches(element, task)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean remove(@Nullable Object task) {
        for (Runnable element : queue) {
            if (matches(element, task) && queue.remove(element)) {
                taken(element);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean offer(Runnable task) {
        return queue.offer(task);
    }

    @Override
    public boolean offer(Runnable task, long timeout, TimeUnit unit) throws InterruptedException {
        return queue.offer(task, timeout, unit);
    }

    @Override
    public void put(Runnable task) throws InterruptedException {
        queue.put(task);
    }

    @Override
    public @Nullable Runnable poll() {
        return taken(queue.poll());
    }

    @Override
    public @Nullable Runnable poll(long timeout, TimeUnit unit) throws InterruptedException {
        return taken(queue.poll(timeout, unit));
    }

    @Override
    public Runnable take() throws InterruptedException {
        Runnable element = queue.take();
        taken(element);
        return AgentConcurrencyUtilHooks.poolTask(element);
    }

    @Override
    public @Nullable Runnable peek() {
        return given(queue.peek());
    }

    @Override
    public int remainingCapacity() {
        return queue.remainingCapacity();
    }

    @Override
    public int drainTo(Collection<? super Runnable> target) {
        return drainTo(target, Integer.MAX_VALUE);
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // BlockingQueue's own rule is about this very queue
    public int drainTo(Collection<? super Runnable> target, int maxElements) {
        Objects.requireNonNull(target);
        if (target == this || target == queue) { // NOPMD CompareObjectsWithEquals - BlockingQueue's own rule, by identity
            throw new IllegalArgumentException("cannot drain a queue into itself");
        }
        List<Runnable> drained = new ArrayList<>();
        int count = queue.drainTo(drained, maxElements);
        for (Runnable element : drained) {
            target.add(taken(element));
        }
        return count;
    }
}
