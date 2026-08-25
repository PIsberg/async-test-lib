package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.function.Consumer;

/**
 * Sorts lists of integers with a fork/join merge sort on the {@link ForkJoinPool#commonPool()}.
 *
 * <p><strong>Bug:</strong> {@link #sort(List)} forks the left half and never joins it. The
 * forked task runs, produces a result, and nobody collects it, so the merge is done with only
 * half the data and the sorted list quietly comes back short. If that half had thrown, nobody
 * would ever have found out either: an exception in a forked task surfaces at {@code join()},
 * and there is no {@code join()}.
 *
 * <p><strong>Fix:</strong> {@link #sortFixed(List)} is the same method with the {@code join()}
 * put back. Every {@code fork()} needs a matching {@code join()}, on every path.
 *
 * <p>Note on scope: blocking inside a ForkJoin worker is a different bug with a different
 * detector, demonstrated in example 51. This example is about the fork that never gets joined,
 * which is what {@code ForkJoinPoolDetector} actually models.
 *
 * <p><strong>INSTRUMENTATION:</strong> ForkJoinPoolDetector does not infer the imbalance. Its own
 * javadoc says so: "reported when the caller says so via recordForkWithoutJoin. It is not
 * inferred", because a test that ends mid-computation would show an imbalance without a defect.
 * So the code that abandons the task is the only thing that can report it, and the hooks below
 * are how. They default to no-ops, so the production path never touches the test library.
 */
public class ParallelSorter {

    private volatile Consumer<String> onFork = taskName -> { };

    private volatile Consumer<String> onJoin = taskName -> { };

    private volatile Consumer<String> onForkWithoutJoin = taskName -> { };

    /**
     * Sorts a copy of the input list on the common pool.
     *
     * <p>BUG: half the data is dropped, silently. See the class javadoc.
     *
     * @param data the list to sort
     * @return what is left of it, sorted
     */
    public List<Integer> sort(List<Integer> data) {
        return ForkJoinPool.commonPool().invoke(new SortTask(data, false));
    }

    /**
     * The same sort with every fork joined.
     *
     * @param data the list to sort
     * @return the whole list, sorted
     */
    public List<Integer> sortFixed(List<Integer> data) {
        return ForkJoinPool.commonPool().invoke(new SortTask(data, true));
    }

    /**
     * Installs the hooks ForkJoinPoolDetector needs. No-ops by default.
     *
     * @param fork            called with the task label at each fork()
     * @param join            called with the task label at each join()
     * @param forkWithoutJoin called with the task label where a forked task is abandoned
     */
    public void observeForkJoin(Consumer<String> fork, Consumer<String> join,
                                Consumer<String> forkWithoutJoin) {
        this.onFork = fork;
        this.onJoin = join;
        this.onForkWithoutJoin = forkWithoutJoin;
    }

    /**
     * {@return the parallelism of the common pool}
     */
    public int getCommonPoolParallelism() {
        return ForkJoinPool.commonPool().getParallelism();
    }

    private final class SortTask extends RecursiveTask<List<Integer>> {

        private static final long serialVersionUID = 1L;

        private final transient List<Integer> data;
        private final boolean joinBothHalves;

        SortTask(List<Integer> data, boolean joinBothHalves) {
            this.data = data;
            this.joinBothHalves = joinBothHalves;
        }

        @Override
        protected List<Integer> compute() {
            if (data.size() <= 1) {
                return new ArrayList<>(data);
            }
            int mid = data.size() / 2;
            SortTask left = new SortTask(data.subList(0, mid), joinBothHalves);
            SortTask right = new SortTask(data.subList(mid, data.size()), joinBothHalves);

            left.fork();
            onFork.accept("sort-left");
            List<Integer> rightResult = right.compute();

            if (!joinBothHalves) {
                // BUG: the forked half is abandoned here. It runs, it finishes, and its result
                // goes nowhere. The caller gets a sorted list that is missing elements.
                onForkWithoutJoin.accept("sort-left");
                return rightResult;
            }

            List<Integer> leftResult = left.join();
            onJoin.accept("sort-left");
            return merge(leftResult, rightResult);
        }
    }

    private static List<Integer> merge(List<Integer> left, List<Integer> right) {
        List<Integer> merged = new ArrayList<>(left.size() + right.size());
        merged.addAll(left);
        merged.addAll(right);
        Collections.sort(merged);
        return merged;
    }
}
