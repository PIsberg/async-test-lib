package se.deversity.asynctest.corpus;

/**
 * One corpus entry: a third-party class, the contract its javadoc states, and the evidence for it.
 *
 * @param testMethod the {@code @AsyncTest} method in {@link CorpusEvalTest} that exercises it
 * @param library    the artifact the class ships in, at the version this module resolves
 * @param className  the fully qualified class under test
 * @param contract   the documented contract, used as ground truth
 * @param evidence   the sentence from the class's own javadoc that states the contract
 * @param source     {@code file:line} in that library's sources jar where the sentence lives
 */
record Subject(
        String testMethod,
        String library,
        String className,
        Contract contract,
        String evidence,
        String source) {
}
