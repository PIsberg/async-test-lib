#!/usr/bin/env python3
"""Fail when the set of skipped tests differs from the committed baseline.

CI reports skipped tests as a count in a log line and treats them as passes, so nothing noticed
when a test started skipping or never stopped. RealKeygenLicenseE2eTest and
RealOfflineLicenseE2eTest guarded the only real-grant path and skipped on every leg for about two
months (#901). This gate reads a job's JUnit XML after its tests and fails in both directions
(#905):

  - a skip that is not in .github/skipped-tests.txt hides a test;
  - a baselined skip that ran, or never appeared, hides a reason that no longer holds.

Usage:
    skipped_tests_gate.py <repo-root> <context> <dir> [<dir> ...]
    skipped_tests_gate.py <repo-root> --self-test

<context> names the job, for example tests/jdk21/ubuntu-latest. Every TEST-*.xml under each <dir> is read (Maven
writes target/surefire-reports, Gradle build/test-results), and each report is checked in the
context "<context>/<its directory relative to the repo root>". A baseline line applies where one
of its patterns matches that context; an entry no scanned context matches is out of scope for
this job and is not checked.

Exit code 1 on any difference, or when no report was found: a job that ran nothing has nothing to
compare, and an empty run must not read as a clean one.
"""
import fnmatch
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

BASELINE_FILE = ".github/skipped-tests.txt"
INVOCATION = re.compile(r"(\(\))?(\[.*\])?$")


def method_of(name):
    """'demo()[2]', 'demo[2]', 'demo()' and 'demo' all name the method 'demo'."""
    return INVOCATION.sub("", (name or "").strip())


def load_baseline(text):
    """[(class, method, [context patterns], reason, may_skip)] from the baseline file's text.

    A line is '<class>#<method> <pattern>[,<pattern>...] # <reason>'; <method> may be '*'. A
    method ending in '?' may skip: for a test whose own assumption depends on timing, the skip
    is allowed but not required, so it is never reported as stale.
    Raises ValueError on a malformed line, so a typo cannot silently allow nothing or everything.
    """
    entries = []
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        head, sep, reason = line.partition(" # ")
        fields = head.split()
        if not sep or not reason.strip() or len(fields) != 2 or "#" not in fields[0]:
            raise ValueError(f"{BASELINE_FILE}:{number}: expected '<class>#<method> <contexts> "
                             f"# <reason>', got: {raw}")
        cls, method = fields[0].split("#", 1)
        may_skip = method.endswith("?")
        patterns = [p for p in fields[1].split(",") if p]
        entries.append((cls, method.rstrip("?"), patterns, reason.strip(), may_skip))
    return entries


def collect(repo_root, context, dirs):
    """(scanned contexts, [(context, class, method)] skipped) from every TEST-*.xml under dirs."""
    scanned, skipped = set(), []
    for directory in dirs:
        for report in sorted(Path(directory).rglob("TEST-*.xml")):
            where = report.parent.resolve().relative_to(repo_root).as_posix()
            ctx = f"{context}/{where}"
            scanned.add(ctx)
            for case in ET.parse(report).getroot().iter("testcase"):
                if case.find("skipped") is not None:
                    skipped.append((ctx, case.get("classname", ""), method_of(case.get("name"))))
    return scanned, skipped


def matches(entry, ctx, cls, method):
    e_cls, e_method, patterns = entry[:3]
    return (e_cls == cls and e_method in ("*", method)
            and any(fnmatch.fnmatchcase(ctx, p) for p in patterns))


def check(entries, scanned, skipped):
    """(errors, allowed) where allowed is [(skip, reason)] and errors are sentences."""
    errors, allowed = [], []
    for ctx, cls, method in sorted(set(skipped)):
        entry = next((e for e in entries if matches(e, ctx, cls, method)), None)
        if entry is None:
            errors.append(f"{cls}#{method} skipped in {ctx} and is not in {BASELINE_FILE}: "
                          f"make it run, or add it with the reason it cannot")
        else:
            allowed.append(((ctx, cls, method), entry[3]))
    for entry in entries:
        cls, method, patterns, _, may_skip = entry
        if may_skip:
            continue
        in_scope = [c for c in scanned if any(fnmatch.fnmatchcase(c, p) for p in patterns)]
        if in_scope and not any(matches(entry, *s) for s in skipped):
            errors.append(f"{cls}#{method} is baselined as skipping in {','.join(patterns)}, but "
                          f"did not skip here: drop the line, or find out why it stopped skipping")
    return errors, allowed

def report_xml(cases):
    """A minimal surefire report: cases are (classname, name, skipped)."""
    body = "".join(
        f'<testcase name="{n}" classname="{c}">{"<skipped/>" if s else ""}</testcase>'
        for c, n, s in cases)
    return f'<testsuite name="x" tests="{len(cases)}">{body}</testsuite>'


def self_test(repo_root):
    """Run the gate on known input; also parse the real baseline. Exit 1 on any surprise."""
    failures = []

    def expect(condition, what):
        if not condition:
            failures.append(what)

    baseline = load_baseline("\n".join([
        "# comment",
        "a.Gatherer#needsJdk24 job/jdk21/* # Gatherers are final in JDK 24",
        "a.Lane#* corpus/*/lane5 # lane five runs library rows only",
        "a.Other#elsewhere other/* # never in scope here",
        "a.Gatherer#luck? job/* # skips when the JDK gives the test nothing to judge",
        "a.Win#locked job/*/ubuntu-latest/*,job/*/macos-latest/* # runs on Windows only",
    ]))
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp).resolve()
        jdk21 = root / "lib" / "target" / "surefire-reports"
        lane5 = root / "corpus" / "lane5"
        for d in (jdk21, lane5):
            d.mkdir(parents=True)
        (jdk21 / "TEST-a.Gatherer.xml").write_text(report_xml([
            ("a.Gatherer", "needsJdk24()", True), ("a.Gatherer", "runs()", False)]))
        (lane5 / "TEST-a.Lane.xml").write_text(report_xml([
            ("a.Lane", "row1()", True), ("a.Lane", "row2()", True), ("a.Lane", "lib()", False)]))

        scanned, skipped = collect(root, "job/jdk21", [root / "lib"])
        errors, allowed = check(baseline, scanned, skipped)
        expect(not errors and len(allowed) == 1, f"a baselined skip passes: {errors}")

        (jdk21 / "TEST-a.New.xml").write_text(report_xml([("a.New", "t()[1]", True)]))
        errors, _ = check(baseline, *collect(root, "job/jdk21", [root / "lib"]))
        expect(len(errors) == 1 and "a.New#t" in errors[0], f"a new skip fails: {errors}")
        (jdk21 / "TEST-a.New.xml").unlink()

        (jdk21 / "TEST-a.Gatherer.xml").write_text(report_xml([("a.Gatherer", "needsJdk24()", False)]))
        errors, _ = check(baseline, *collect(root, "job/jdk21", [root / "lib"]))
        expect(len(errors) == 1 and "did not skip" in errors[0], f"a skip that ran fails: {errors}")

        errors, _ = check(baseline, *collect(root, "job/jdk25", [root / "lib"]))
        expect(not errors, f"an entry for another context is not checked: {errors}")

        (jdk21 / "TEST-a.Gatherer.xml").write_text(report_xml([
            ("a.Gatherer", "needsJdk24()", True), ("a.Gatherer", "luck()", True)]))
        errors, allowed = check(baseline, *collect(root, "job/jdk21", [root / "lib"]))
        expect(not errors and len(allowed) == 2, f"a may-skip entry allows the skip: {errors}")
        (jdk21 / "TEST-a.Gatherer.xml").write_text(report_xml([
            ("a.Gatherer", "needsJdk24()", True), ("a.Gatherer", "luck()", False)]))
        errors, _ = check(baseline, *collect(root, "job/jdk21", [root / "lib"]))
        expect(not errors, f"a may-skip entry that ran is not stale: {errors}")

        errors, allowed = check(baseline, *collect(root, "corpus/jdk21", [root / "corpus"]))
        expect(not errors and len(allowed) == 2, f"a '*' method covers every skip: {errors}")

        (jdk21 / "TEST-a.Gatherer.xml").write_text(report_xml([("a.Gatherer", "needsJdk24()", True)]))
        (jdk21 / "TEST-a.Win.xml").write_text(report_xml([("a.Win", "locked()", False)]))
        errors, _ = check(baseline, *collect(root, "job/jdk21/windows-latest", [root / "lib"]))
        expect(not errors, f"an OS-qualified entry is not checked on the OS it runs on: {errors}")
        errors, _ = check(baseline, *collect(root, "job/jdk21/ubuntu-latest", [root / "lib"]))
        expect(len(errors) == 1 and "a.Win#locked" in errors[0],
               f"an OS-qualified entry that ran where it must skip fails: {errors}")
        (jdk21 / "TEST-a.Win.xml").unlink()

        scanned, _ = collect(root, "job/jdk21", [root / "missing"])
        expect(not scanned, "no report found leaves nothing scanned, which main() refuses")

    for bad in ("a.B#c job/* no reason", "a.B#c # reason without contexts", "a.B job/* # no method"):
        try:
            load_baseline(bad)
            failures.append(f"malformed line accepted: {bad}")
        except ValueError:
            pass
    expect(method_of("demo()[2]") == "demo" and method_of("demo[2]") == "demo",
           "invocation suffixes are stripped")
    try:
        real = load_baseline((repo_root / BASELINE_FILE).read_text(encoding="utf-8"))
        expect(real, f"{BASELINE_FILE} parses but holds no entry")
    except (OSError, ValueError) as e:
        failures.append(str(e))

    for failure in failures:
        print(f"::error::self-test: {failure}")
    if failures:
        return 1
    print(f"self-test passed; {BASELINE_FILE} holds {len(real)} entries")
    return 0


def main(argv):
    if len(argv) == 3 and argv[2] == "--self-test":
        return self_test(Path(argv[1]).resolve())
    if len(argv) < 4:
        print(__doc__)
        return 2
    repo_root = Path(argv[1]).resolve()
    context = argv[2]
    entries = load_baseline((repo_root / BASELINE_FILE).read_text(encoding="utf-8"))
    scanned, skipped = collect(repo_root, context, [Path(d) for d in argv[3:]])
    if not scanned:
        print(f"::error::no TEST-*.xml under {argv[3:]}: the tests did not run, or wrote elsewhere")
        return 1
    errors, allowed = check(entries, scanned, skipped)
    print(f"{context}: {len(scanned)} report directories, {len(skipped)} skipped invocations")
    for (ctx, cls, method), reason in allowed:
        print(f"  baselined skip: {cls}#{method} in {ctx}  ({reason})")
    for error in errors:
        print(f"::error::{error}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
