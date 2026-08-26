#!/usr/bin/env python3
"""Report which @Disabled example demonstrations pass when they are enabled.

A demonstration says, in its @Disabled reason, "Remove @Disabled to see X detected by
YDetector". Nothing runs them, so that sentence is unchecked: on 2026-08-25 an audit enabled
all 97 and 72 of them passed. ExampleDisabledDemoTest checks the static half - every disabled
@AsyncTest sets failOn, so it *can* fail - but it cannot tell whether the detector the
demonstration names would say anything. This script is the dynamic half. See issue #359.

The inversion is the whole design: a demonstration that PASSES is the finding.

Usage:
    disabled_demo_report.py <repo-root> <run-dir> [<run-dir> ...]

Each <run-dir> holds one run's surefire XML, collected as
    examples/*/target/surefire-reports/TEST-*.xml
Passing more than one is the point: detection in a few demonstrations is timing-dependent,
and three runs was the minimum that separated "never fires" from "fires sometimes".

Exit code 1 when a demonstration passed in every run.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# The message ConcurrencyRunner raises when a finding trips the failOn gate. A demonstration
# failing with this failed for the right reason; one failing with anything else tripped its own
# assertion, or timed out, before the detector was consulted.
GATE_MARKER = "at or above failOn="

# The sentence ConcurrencyRunner puts on a RoundTimeoutError when the detectors had something to
# say. The failOn gate is success-path-only, so a run that hangs cannot fail on a finding however
# healthy it is; naming the detectors in the timeout is the closest a hanging demonstration gets
# to keeping its @Disabled reason's promise, and 9 of them do. Distinguished from a body that
# threw its own assertion, because those are different defects and only the second is one.
TIMEOUT_WITH_FINDING_MARKER = "detector finding(s) recorded before the timeout"

# Demonstrations already known to pass, with a reason and an issue. See the file's own header.
BASELINE_FILE = ".github/known-silent-demos.txt"

DISABLED = re.compile(r"^\s*@Disabled\b")
ASYNC_TEST = re.compile(r"^\s*@AsyncTest\b")
METHOD = re.compile(r"^\s*(?:public\s+|private\s+|protected\s+)?(?:static\s+)?\w[\w<>\[\], .]*\s+(\w+)\s*\(")


def disabled_demonstrations(repo_root):
    """{(class_fqn, method): source_location} for every @Disabled @AsyncTest under examples/."""
    found = {}
    for java in sorted((repo_root / "examples").rglob("src/test/**/*.java")):
        lines = java.read_text(encoding="utf-8", errors="replace").splitlines()
        package = ""
        for line in lines:
            if line.startswith("package "):
                package = line[len("package "):].strip().rstrip(";")
                break
        class_fqn = f"{package}.{java.stem}" if package else java.stem
        for i, line in enumerate(lines):
            if not DISABLED.match(line):
                continue
            # Walk forward past whatever annotations sit between @Disabled and the method,
            # counting parentheses rather than reading line by line: the examples wrap long
            # reason strings and long @AsyncTest attribute lists over several lines, and a
            # line-at-a-time scan treated the second line of a wrapped @Disabled("...") as
            # ordinary code and gave up. It did, on
            # examples/01-completablefuture-exception-handling, which is exactly the kind of
            # silent under-count this whole job exists to stop.
            saw_async_test = False
            depth = 0
            in_annotation = False
            for j in range(i, min(i + 60, len(lines))):
                text = lines[j]
                candidate = text.strip()
                if j == i or (depth == 0 and candidate.startswith("@")):
                    in_annotation = True
                    if ASYNC_TEST.match(text):
                        saw_async_test = True
                if in_annotation:
                    depth += text.count("(") - text.count(")")
                    if depth <= 0:
                        depth = 0
                        in_annotation = False
                    continue
                if not candidate or candidate.startswith("//") or candidate.startswith("*"):
                    continue
                if not saw_async_test:
                    break                       # @Disabled on something that is not a demo
                method = METHOD.match(text)
                if method:
                    found[(class_fqn, method.group(1))] = (
                        f"{java.relative_to(repo_root).as_posix()}:{i + 1}")
                break
    return found


def baseline(repo_root):
    """{(class_fqn, method)} the committed list of demonstrations known to stay silent."""
    path = repo_root / BASELINE_FILE
    if not path.exists():
        return set()
    known = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        # A whole-line comment, or blank. An inline comment starts after the entry's first
        # whitespace, so '#' is not a general comment marker here: it separates class from
        # method, and splitting on it would cut every entry in half.
        if not stripped or stripped.startswith("#"):
            continue
        entry = stripped.split(None, 1)[0]
        class_fqn, separator, method = entry.rpartition("#")
        if not separator or not class_fqn or not method:
            print(f"::warning::{BASELINE_FILE}: '{stripped}' is not <class>#<method>, ignored")
            continue
        known.add((class_fqn, method))
    return known


def strip_invocation(name):
    """'demo()[2]' and 'demo[2]' and 'demo()' all name the method 'demo'."""
    return re.sub(r"(\(\))?(\[.*\])?$", "", name.strip())


def first_line(text):
    for line in text.splitlines():
        if line.strip():
            return line.strip()[:200]
    return "(no message)"


# A demonstration runs N times inside one @TestTemplate. Worse news wins: one failing
# invocation means the demonstration failed, and only an all-green demonstration is a finding.
PRECEDENCE = {"skipped": 0, "passed": 1, "other": 2, "named": 3, "gate": 4}


def outcomes(run_dir):
    """{(class_fqn, method): 'passed' | 'gate' | 'other:<msg>' | 'skipped'} for one run."""
    seen = {}
    for report in sorted(Path(run_dir).rglob("TEST-*.xml")):
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError as broken:
            print(f"::warning::unparseable surefire report {report}: {broken}")
            continue
        for case in root.iter("testcase"):
            key = (case.get("classname", ""), strip_invocation(case.get("name", "")))
            if case.find("skipped") is not None:
                status = "skipped"
            else:
                failure = case.find("failure")
                if failure is None:
                    failure = case.find("error")
                if failure is None:
                    status = "passed"
                else:
                    text = (failure.get("message") or "") + (failure.text or "")
                    if GATE_MARKER in text:
                        status = "gate"
                    elif TIMEOUT_WITH_FINDING_MARKER in text:
                        status = "named:" + first_line(text)
                    else:
                        status = "other:" + first_line(text)
            previous = seen.get(key)
            if previous is None or PRECEDENCE[status.split(":")[0]] > PRECEDENCE[previous.split(":")[0]]:
                seen[key] = status
    return seen


FAKE_SUITE = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="selftest" tests="5">
  <testcase name="demoThatPassed()[1]" classname="x.Demo"/>
  <testcase name="demoOnTheGate()[1]" classname="x.Demo">
    <failure message="[AsyncTest] 2 detector finding(s) at or above failOn=LOW">stack</failure>
  </testcase>
  <testcase name="demoOnItsOwnAssertion()[1]" classname="x.Demo">
    <failure message="expected: &lt;true&gt; but was: &lt;false&gt;">trace</failure>
  </testcase>
  <testcase name="demoThatHungWithAFinding()[1]" classname="x.Demo">
    <failure message="Test timed out after 5000ms. Possible deadlock, starvation, or visibility issue. 1 detector finding(s) recorded before the timeout: LockLeakDetector. Full reports above.">trace</failure>
  </testcase>
  <testcase name="demoStillSkipped" classname="x.Demo"><skipped message="Remove @Disabled"/></testcase>
  <testcase name="demoThatPassed()[2]" classname="x.Demo">
    <error message="round timed out">trace</error>
  </testcase>
</testsuite>
"""


def self_test(repo_root):
    """Check the parser against known input, so a silent stop-matching cannot look like 'clean'.

    This job's whole output is "nothing passed", and that sentence is worthless if the parser
    quietly stopped recognising surefire's shape. Cheap enough to run before every real sweep.
    """
    import tempfile

    failures = []

    def expect(actual, wanted, what):
        if actual != wanted:
            failures.append(f"{what}: expected {wanted!r}, got {actual!r}")

    expect(strip_invocation("demo()[2]"), "demo", "strip_invocation on 'demo()[2]'")
    expect(strip_invocation("demo[2]"), "demo", "strip_invocation on 'demo[2]'")
    expect(strip_invocation("demo()"), "demo", "strip_invocation on 'demo()'")

    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / "TEST-selftest.xml").write_text(FAKE_SUITE, encoding="utf-8")
        seen = outcomes(tmp)
    expect(seen.get(("x.Demo", "demoOnTheGate")), "gate", "a failOn-gate failure")
    expect(str(seen.get(("x.Demo", "demoOnItsOwnAssertion"))).split(":")[0], "other",
           "a failure that is not the gate")
    expect(str(seen.get(("x.Demo", "demoThatHungWithAFinding"))).split(":")[0], "named",
           "a timeout whose message names the detectors that had a finding")
    expect(seen.get(("x.Demo", "demoStillSkipped")), "skipped", "a still-skipped demonstration")
    # One green invocation and one timed-out invocation of the same demonstration: worse news
    # wins, or a demonstration that fails only sometimes would be filed as healthy.
    expect(str(seen.get(("x.Demo", "demoThatPassed"))).split(":")[0], "other",
           "a demonstration with one passing and one erroring invocation")

    demos = disabled_demonstrations(repo_root)
    if len(demos) < 50:
        failures.append(f"the source scan found only {len(demos)} disabled demonstrations under "
                        "examples/; it found 98 when this was written, so it has probably "
                        "stopped matching")

    # A @Disabled whose reason wraps onto a second line. The first version of this scanner read
    # that continuation as ordinary code and gave up on the method, so this demonstration was
    # missing from the report entirely - the silent under-count the job exists to prevent,
    # committed inside the job itself. Named rather than counted, so the case cannot drift away.
    wrapped = ("se.deversity.asynctest.example.OrderProcessingServiceTest",
               "testProcessMultipleOrders_Concurrent_WITH_ASYNC_TEST")
    if wrapped not in demos:
        failures.append(f"{wrapped[0]}.{wrapped[1]} was not found. Its @Disabled reason wraps "
                        "onto a second line, which is the case this scanner used to miss. If "
                        "that example changed, point this check at another wrapped @Disabled "
                        "rather than deleting it")

    for failure in failures:
        print(f"::error::self-test: {failure}")
    if failures:
        return 1
    print(f"self-test passed; the source scan sees {len(demos)} disabled demonstrations")
    return 0


def main(argv):
    if len(argv) == 3 and argv[2] == "--self-test":
        return self_test(Path(argv[1]).resolve())
    if len(argv) < 3:
        print(__doc__)
        return 2
    repo_root = Path(argv[1]).resolve()
    run_dirs = argv[2:]

    demos = disabled_demonstrations(repo_root)
    if not demos:
        print("::error::no @Disabled @AsyncTest demonstrations were found under examples/. "
              "Either every demonstration is enabled now, or this scan stopped matching and "
              "is reporting on nothing.")
        return 1

    known_silent = baseline(repo_root)
    unknown_baseline = sorted(known_silent - set(demos))
    for entry in unknown_baseline:
        print(f"::warning::{BASELINE_FILE} lists {entry[0]}.{entry[1]}, which is not a "
              "@Disabled demonstration any more. Delete the line.")

    runs = [outcomes(directory) for directory in run_dirs]

    always_passed, sometimes_passed, wrong_reason, never_ran, fired = [], [], [], [], []
    still_skipped, expected_silent, recovered, named_timeout = [], [], [], []
    for key, location in sorted(demos.items()):
        statuses = [run.get(key, "not-run") for run in runs]
        label = f"{key[0]}.{key[1]}  ({location})"
        if any(status == "skipped" for status in statuses):
            still_skipped.append(label)
        elif all(status == "not-run" for status in statuses):
            never_ran.append(label)
        elif all(status == "passed" for status in statuses):
            (expected_silent if key in known_silent else always_passed).append(label)
        elif any(status == "passed" for status in statuses):
            sometimes_passed.append(f"{label}  [{', '.join(statuses)}]")
        elif all(status.startswith("gate") for status in statuses):
            fired.append(label)
            if key in known_silent:
                recovered.append(label)
        elif all(status.startswith(("gate", "named")) for status in statuses):
            named = "; ".join(sorted({s.split(": ", 1)[-1] for s in statuses
                                      if s.startswith("named")}))
            named_timeout.append(f"{label}\n      {named}")
        else:
            reasons = "; ".join(sorted({s for s in statuses if s.startswith("other")}))
            wrong_reason.append(f"{label}\n      {reasons}")

    print(f"Disabled demonstrations found in examples/: {len(demos)}")
    print(f"Runs analysed: {len(runs)}")
    print()
    print(f"  fired every run (healthy):        {len(fired)}")
    print(f"  hung, with the finding named:     {len(named_timeout)}")
    print(f"  passed every run (THE FINDING):   {len(always_passed)}")
    print(f"  passed every run, known silent:   {len(expected_silent)}")
    print(f"  passed in some runs (flaky):      {len(sometimes_passed)}")
    print(f"  failed for another reason:        {len(wrong_reason)}")
    print(f"  still reported as skipped:        {len(still_skipped)}")
    print(f"  never ran:                        {len(never_ran)}")
    print()

    section("Passed every run - the detector they advertise said nothing", always_passed)
    section(f"Passed every run, and {BASELINE_FILE} says so - still a demonstration that does "
            "not demonstrate, and still on the list to fix", expected_silent)
    section(f"Fired every run, though {BASELINE_FILE} expects them silent - delete those lines "
            "if it holds (they are timing-dependent, so one green week is not proof)", recovered)
    section("Passed in some runs - detection is real but timing-dependent", sometimes_passed)
    section("Timed out rather than reaching the failOn gate, but the timeout names the "
            "detector's finding - the subject really does hang, and the reader is told what was "
            "detected. Healthy, and reported so the count stays visible", named_timeout)
    section("Failed, but not on the detector's finding - an assertion in the body tripped first, "
            "or the round timed out with nothing detected", wrong_reason)
    section("Still skipped - the DisabledCondition deactivation did not reach this module, "
            "so nothing was measured about it", still_skipped)
    section("Never ran - the module did not report a result for them at all", never_ran)

    # Only the all-runs case fails the job. A demonstration that fires in two runs of three is a
    # poor demonstration and is reported, but it is not the defect this job exists to catch, and
    # failing on it would get the job switched off within a week.
    if always_passed:
        print(f"::error::{len(always_passed)} demonstration(s) passed when enabled and are not "
              f"in {BASELINE_FILE}. Each one tells a reader to remove @Disabled to see a "
              "detection that does not happen. Fix it, or add a line with a reason and an issue.")
        return 1
    if expected_silent:
        # Reported every run, loudly, and never allowed to fade into the background. The list is
        # a record of accepted debt, not an exemption: the point of committing it is that the
        # count is in the log of a green job rather than in nobody's head.
        print(f"::warning::{len(expected_silent)} demonstration(s) still pass when enabled, all "
              f"of them listed in {BASELINE_FILE} with a reason. See #362.")
    if still_skipped:
        # Reported, not fatal. A module that stayed skipped was not measured, and "nothing
        # found" over an unmeasured set is the failure mode this job exists to end - but a
        # skipped module is a harness problem, not a demonstration that lies.
        print(f"::warning::{len(still_skipped)} demonstration(s) were still skipped, so nothing "
              "was measured about them.")
    print("No demonstration passed in every run.")
    return 0


# The lists that matter are short by construction; "never ran" can be the whole set when the
# reactor died early, and 97 lines of it buries the finding above. Truncation is announced,
# never silent.
MAX_LISTED = 25


def section(title, entries):
    if not entries:
        return
    print(f"### {title} ({len(entries)})")
    for entry in entries[:MAX_LISTED]:
        print(f"  - {entry}")
    if len(entries) > MAX_LISTED:
        print(f"  ... and {len(entries) - MAX_LISTED} more, not listed")
    print()


if __name__ == "__main__":
    sys.exit(main(sys.argv))
