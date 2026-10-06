package se.deversity.asynctest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;

/**
 * Marks a test class that can only fail on some operating systems: the defect it guards behaves
 * differently on Windows or macOS than on the Ubuntu runner every pull request uses.
 *
 * <p>The Windows and macOS legs of the full suite are advisory (#484), so a test like this would
 * otherwise have no leg that blocks a merge. Tests & Build's {@code OS-Sensitive Tests} job runs
 * {@code -Dgroups=os-sensitive} on Windows and macOS on every pull request, without
 * {@code continue-on-error} (#907). Tag a class when its failure depends on the platform's file
 * system, process or scheduling semantics, and keep the set small: every tagged class runs on two
 * extra runners per push.
 *
 * <p>A meta-annotation rather than a raw {@code @Tag}, so a typo cannot drop a class out of the
 * job; {@code OsSensitiveTestsBlockOnEveryOsTest} pins the tag id, the known classes and the job.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("os-sensitive")
public @interface OsSensitive {
}
