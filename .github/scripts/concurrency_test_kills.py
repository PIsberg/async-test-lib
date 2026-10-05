#!/usr/bin/env python3
"""Check that each @ConcurrencyTestFor test kills its class's mutants on its own.

ThreadSafetyClaimsAreTestedConcurrentlyTest requires every @AIThreadSafe class to be named by a
test that runs it concurrently. It cannot tell whether that test would fail when the class
breaks: LicenseGuard's old cacheIsThreadSafe asserted a ConcurrentHashMap's size, which holds
whatever the gate does (#904). Mutation testing asks the question directly (#909). For each
marker pair this runs PIT on the named class with only the marked test, and fails when the share
of mutants that test detects falls below the class's floor in .github/concurrency-kill-floors.txt.

A full PIT run cannot answer it: without the full mutation matrix, mutations.xml records only the
first test that killed a mutant, and the slow @AsyncTest tests are rarely first.

Usage:
    concurrency_test_kills.py <repo-root>                 run PIT per pair, check the floors
    concurrency_test_kills.py <repo-root> --self-test     parser and floor logic on known input

Needs mvn on PATH. Each pair takes one scoped PIT run, about one to three minutes.
"""
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

FLOORS_FILE = ".github/concurrency-kill-floors.txt"
MODULES = ("async-test-lib", "async-test-agent", "async-test-analysis")
MARKER = re.compile(r"@ConcurrencyTestFor\(([^)]*)\)")
CLASS_LITERAL = re.compile(r"\b([A-Z]\w*)\.class\b")
PACKAGE = re.compile(r"^package\s+([\w.]+);", re.MULTILINE)


def fqn(source):
    match = PACKAGE.search(source.read_text(encoding="utf-8"))
    return (match.group(1) + "." if match else "") + source.stem


def pairs(repo_root):
    """[(module, class simple name, class fqn, test fqn)] for every marker in the test trees."""
    main_by_name = {}
    for module in MODULES:
        for source in (repo_root / module / "src" / "main" / "java").rglob("*.java"):
            main_by_name.setdefault(source.stem, fqn(source))
    found = []
    for module in MODULES:
        for test in sorted((repo_root / module / "src" / "test" / "java").rglob("*.java")):
            if test.name == "ConcurrencyTestFor.java":
                continue
            for marker in MARKER.finditer(test.read_text(encoding="utf-8")):
                for name in CLASS_LITERAL.findall(marker.group(1)):
                    found.append((module, name, main_by_name.get(name, name), fqn(test)))
    return found


def load_floors(text):
    """{class simple name: (floor percent, reason)}; raises ValueError on a malformed line."""
    floors = {}
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        head, sep, reason = line.partition(" # ")
        fields = head.split()
        if not sep or len(fields) != 2 or not fields[1].isdigit():
            raise ValueError(f"{FLOORS_FILE}:{number}: expected '<Class> <percent> # <reason>': {raw}")
        floors[fields[0]] = (int(fields[1]), reason.strip())
    return floors


def score(mutations_xml):
    """(detected, total) over every mutation in a PIT mutations.xml."""
    root = ET.parse(mutations_xml).getroot()
    mutations = root.findall("mutation")
    detected = sum(1 for m in mutations if m.get("detected") == "true")
    return detected, len(mutations)


def check(results, floors):
    """Sentences for every pair below its floor, without one, and every floor without a pair.

    results is {class simple name: (detected, total, test fqn)}.
    """
    errors = []
    for name, (detected, total, test) in sorted(results.items()):
        percent = 100 * detected // total if total else 0
        if name not in floors:
            errors.append(f"{name}: {test} detects {percent}% ({detected}/{total}) of its mutants, and "
                          f"{FLOORS_FILE} has no floor for it; add one from this measurement")
        elif percent < floors[name][0]:
            errors.append(f"{name}: {test} detects {percent}% ({detected}/{total}) of its mutants, "
                          f"below the floor of {floors[name][0]}%: the test stopped failing when "
                          f"{name} breaks")
    for name in sorted(set(floors) - set(results)):
        errors.append(f"{FLOORS_FILE} has a floor for {name}, but no @ConcurrencyTestFor names it")
    return errors

def run_pit(repo_root, module, class_fqn, test_fqn):
    """Run PIT on one class (and its nested classes) with one test; return (detected, total)."""
    reports = repo_root / module / "target" / "pit-concurrency" / class_fqn.rsplit(".", 1)[-1]
    shutil.rmtree(reports, ignore_errors=True)
    # The pom's <mutationThreshold> is a literal, which beats -DmutationThreshold, and one class
    # under one test always scores below the whole suite's threshold. So Maven's exit code says
    # nothing here; a fresh mutations.xml is the evidence the run happened, and its score is judged
    # against this class's own floor.
    command = [shutil.which("mvn") or "mvn", "-B", "-q", "-pl", module, "test-compile",
               "org.pitest:pitest-maven:mutationCoverage",
               f"-DtargetClasses={class_fqn},{class_fqn}$*", f"-DtargetTests={test_fqn}",
               f"-DreportsDirectory={reports}", "-Djacoco.skip=true", "-Dlicense.mock.mode=true"]
    print("running:", " ".join(command[4:]), flush=True)
    subprocess.run(command, cwd=repo_root, check=False)
    report = reports / "mutations.xml"
    if not report.is_file():
        raise RuntimeError(f"PIT wrote no {report}: the scoped run failed before mutating; see above")
    return score(report)


def self_test(repo_root):
    failures = []

    def expect(condition, what):
        if not condition:
            failures.append(what)

    with tempfile.TemporaryDirectory() as tmp:
        xml = Path(tmp) / "mutations.xml"
        xml.write_text("<mutations>"
                       "<mutation detected='true' status='KILLED'/>"
                       "<mutation detected='true' status='TIMED_OUT'/>"
                       "<mutation detected='false' status='SURVIVED'/>"
                       "<mutation detected='false' status='NO_COVERAGE'/></mutations>")
        expect(score(xml) == (2, 4), f"score counts detected over all: {score(xml)}")

    floors = load_floors("# c\nGuard 50 # measured 59\nGone 10 # stale\n")
    errors = check({"Guard": (59, 100, "t.GuardTest"), "New": (5, 10, "t.NewTest")}, floors)
    expect(any("New" in e and "no floor" in e for e in errors), f"an unmeasured pair fails: {errors}")
    expect(any("Gone" in e for e in errors), f"a floor without a pair fails: {errors}")
    expect(not any(e.startswith("Guard") for e in errors), f"a pair above its floor passes: {errors}")
    errors = check({"Guard": (40, 100, "t.GuardTest")}, {"Guard": (50, "")})
    expect(errors and "below the floor" in errors[0], f"a pair below its floor fails: {errors}")
    for bad in ("Guard fifty # x", "Guard 50", "Guard # x"):
        try:
            load_floors(bad)
            failures.append(f"malformed line accepted: {bad}")
        except ValueError:
            pass

    found = pairs(repo_root)
    names = {name for _, name, _, _ in found}
    expect({"LicenseGuard", "ConcurrencyRunner"} <= names, f"the marker scan sees the pairs: {found}")
    expect(all("." in cls for _, _, cls, _ in found), f"every target resolves to a main class: {found}")
    try:
        real = load_floors((repo_root / FLOORS_FILE).read_text(encoding="utf-8"))
        expect(set(real) == names, f"{FLOORS_FILE} covers exactly the marked classes: {sorted(real)} vs {sorted(names)}")
    except (OSError, ValueError) as e:
        failures.append(str(e))

    for failure in failures:
        print(f"::error::self-test: {failure}")
    if failures:
        return 1
    print(f"self-test passed; {len(found)} marker pairs")
    return 0


def main(argv):
    repo_root = Path(argv[1]).resolve() if len(argv) > 1 else None
    if repo_root and len(argv) == 3 and argv[2] == "--self-test":
        return self_test(repo_root)
    if not repo_root or len(argv) != 2:
        print(__doc__)
        return 2
    floors = load_floors((repo_root / FLOORS_FILE).read_text(encoding="utf-8"))
    results, failed = {}, []
    for module, name, class_fqn, test_fqn in pairs(repo_root):
        try:
            detected, total = run_pit(repo_root, module, class_fqn, test_fqn)
        except RuntimeError as e:
            # One broken pair (often the marked test failing unmutated) must not hide the rest.
            failed.append(f"{name}: {e}")
            floors.pop(name, None)
            continue
        results[name] = (detected, total, test_fqn)
        print(f"{name}: {test_fqn} detects {detected}/{total}", flush=True)
    errors = failed + check(results, floors)
    for error in errors:
        print(f"::error::{error}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
