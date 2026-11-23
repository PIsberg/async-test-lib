# The new-code gate

What SonarQube calls the "Sonar way for agentic AI" quality gate, and how this repository meets each
of its conditions. The gate is SonarQube's answer to agent-written code: it judges only what a change
adds, so an agent that touches a file is held to the standard without being asked to fix the file's
history. This repository enforces the same conditions with its own tools on every pull request, and
can also send the analysis to SonarQube Cloud for the dashboard and the gate's own verdict.

The conditions are SonarQube's, read from its documentation on 2026-11-22
(SonarQube Server 2026.5 LTA, "Quality gate for agentic AI"). All of them apply to new code only.

| Sonar condition | Enforced here by | Stricter or looser |
|---|---|---|
| No new reliability issue of severity Low or above | SpotBugs + FindSecBugs, Error Prone + NullAway, CodeQL ([static-analysis.md](static-analysis.md)) | Stricter: zero findings in the whole codebase, not only in new code |
| No new code smell of severity Major or above | PMD and Checkstyle | Stricter, the same way |
| No new dependency risk of severity Low or above | `dependency-review.yml`, `fail-on-severity: low` | Equal. Sonar needs its paid Advanced Security for this one and skips it otherwise |
| Coverage on new code at least 80% | Codecov's `patch` status (`codecov.yml`) | See [Coverage](#coverage-on-new-code) |
| Duplication on new code at most 3% | `.github/scripts/duplication_gate.py` on the coverage leg of Tests & Build | Equal |

## Coverage on new code

Codecov compares the lines a pull request adds with the coverage report the Ubuntu JDK 21 leg of
Tests & Build uploads. That report is the combined library-and-agent one
(`async-test-agent/target/site/jacoco-aggregate/jacoco.xml`, JaCoCo's `report-aggregate`), plus the
analysis module's own.

Before #1482 only the library's report was uploaded. The library's agent hooks are exercised mostly
by `async-test-agent`'s tests, whose coverage was recorded and reported against nothing, so agent
work read as untested: 15 of 32 recent pull requests were under 80% on Codecov, all the lowest ones
agent changes (47.7% to 57.3%). The numbers after the combined report are in the PR that introduced
it.

## Duplication on new code

Each module's `verify` runs PMD's CPD (100-token minimum, main sources) and writes
`target/cpd.xml` as a report; it never fails the build by itself. On a pull request the coverage leg
then runs `duplication_gate.py`, which counts the lines the pull request adds under `src/main/java`
that fall inside a duplicated block, wherever its other copy is, and fails above 3%. When it was
introduced the whole main source held 19 duplicated blocks, 672 of 109,609 lines (0.6%).

To run it locally on a branch:

```bash
mvn verify -DskipTests -Djacoco.skip=true
python .github/scripts/duplication_gate.py --base origin/main \
  async-test-lib/target/cpd.xml async-test-agent/target/cpd.xml async-test-analysis/target/cpd.xml
```

A failure lists the duplicated blocks that touch new code. Extract the repeated code; a duplicate
that is deliberate (two copies kept apart on purpose) belongs in the pull request's description with
the reason, and the gate's threshold is not the place to absorb it.

## SonarQube Cloud

The coverage leg also sends the analysis to SonarQube Cloud when the `SONAR_TOKEN` secret exists,
and skips with a notice when it does not. Sonar then reports its own quality gate on the pull
request as a separate check, through its GitHub app. The step is `continue-on-error`: an outage at
Sonar must not fail the library's test leg.

To turn it on (repository owner):

1. Sign in at sonarcloud.io with GitHub, import `PIsberg/async-test-lib` into the organization, and
   choose "With GitHub Actions" as the analysis method.
2. Check that the organization and project keys it assigns match `sonar.organization` and
   `sonar.projectKey` in the root `pom.xml` (`pisberg`, `PIsberg_async-test-lib`); change the pom
   if they differ.
3. Add the token it shows as the `SONAR_TOKEN` repository secret.
4. In the project's settings, set the new-code definition to "previous version" or "reference
   branch: main", and assign the "Sonar way for agentic AI" quality gate if the plan offers it, or
   copy its conditions into a custom gate.
5. To make it blocking, add Sonar's check to the branch protection rule for `main`.

The scanner is `org.sonarsource.scanner.maven:sonar-maven-plugin`, declared in the root pom's
`pluginManagement` ([DEPENDENCIES.md](../DEPENDENCIES.md#4-build-toolchain)). The harden-runner step
of the test job admits `sonarcloud.io`, `api.sonarcloud.io` and `scanner.sonarcloud.io`.
