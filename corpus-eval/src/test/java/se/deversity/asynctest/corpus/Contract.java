package se.deversity.asynctest.corpus;

/**
 * The thread-safety contract a corpus subject's own javadoc states.
 *
 * <p>This is the eval's ground truth. It is never inferred from how the class looks: every
 * {@link Subject} carries the quoted sentence and the source line it came from, so a reader can
 * check the classification without trusting this module.
 */
enum Contract {

    /**
     * The class documents itself as safe for concurrent use. Exercising it from many threads is
     * therefore correct usage, and a finding on it is a candidate false positive.
     */
    THREAD_SAFE,

    /**
     * The class documents itself as not thread-safe. Sharing one instance across threads is a real
     * defect, and a finding on it is a true positive.
     */
    NOT_THREAD_SAFE
}
