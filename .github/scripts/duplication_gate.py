#!/usr/bin/env python3
"""Duplication on new code, the way SonarQube's "Sonar way for agentic AI" gate measures it.

Reads the XML that PMD's CPD writes (target/cpd.xml in each module) and the lines a change adds
under src/main/java, and fails when more than --max-percent of those new lines sit inside a
duplicated block. A duplicate whose other copy is old code counts, because the new copy is what
the change added. Test sources are not measured, as Sonar's default does not either.

  duplication_gate.py --base origin/main [--max-percent 3] <cpd.xml>...
  duplication_gate.py --self-test

Exit 0 passes, 1 fails the gate, 2 is a usage or input error. Written for #1482's quality gate.
"""
import argparse
import os
import re
import subprocess
import sys
# The only input is the cpd.xml this repository's own build writes, never outside XML, and expat
# does not fetch external entities, so the stdlib parser is used rather than adding defusedxml.
import xml.etree.ElementTree as ET  # nosec B405

HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
MAIN_SOURCE = "/src/main/java/"


def added_lines(diff_text):
    """{path: set of added line numbers} from a unified diff with zero context."""
    added, path = {}, None
    for line in diff_text.splitlines():
        if line.startswith("+++ "):
            target = line[4:].strip()
            path = None if target == "/dev/null" else target[2:] if target.startswith("b/") else target
            continue
        match = HUNK.match(line)
        if match and path is not None:
            start, count = int(match.group(1)), int(match.group(2) or "1")
            if count:
                added.setdefault(path, set()).update(range(start, start + count))
    return {p: lines for p, lines in added.items() if MAIN_SOURCE in "/" + p and p.endswith(".java")}


def duplicated_ranges(cpd_xml_text, root):
    """[(path, first, last, tokens)] for every copy of every duplicated block, paths repo-relative."""
    ranges = []
    tree = ET.fromstring(cpd_xml_text)
    for dup in tree.iter():
        if not dup.tag.endswith("duplication"):
            continue
        lines = int(dup.get("lines", "0"))
        tokens = int(dup.get("tokens", "0"))
        for f in dup:
            if not f.tag.endswith("file"):
                continue
            first = int(f.get("line", "0"))
            last = int(f.get("endline") or first + lines - 1)
            ranges.append((relative(f.get("path", ""), root), first, last, tokens))
    return ranges


def relative(path, root):
    path = path.replace("\\", "/")
    root = root.replace("\\", "/").rstrip("/") + "/"
    if path.lower().startswith(root.lower()):
        return path[len(root):]
    return path


def measure(new_lines, ranges):
    """(new line count, duplicated new line count, [(path, first, last)] of blocks that touch new code)."""
    total = sum(len(lines) for lines in new_lines.values())
    duplicated, touched = set(), []
    for path, first, last, _tokens in ranges:
        hit = {n for n in new_lines.get(path, ()) if first <= n <= last}
        if hit:
            duplicated.update((path, n) for n in hit)
            touched.append((path, first, last))
    return total, len(duplicated), touched


def self_test():
    diff = (
        "+++ b/mod/src/main/java/a/A.java\n@@ -0,0 +1,10 @@\n"
        "+++ b/mod/src/main/java/a/B.java\n@@ -5,0 +6 @@\n"
        "+++ b/mod/src/test/java/a/ATest.java\n@@ -0,0 +1,50 @@\n"
        "+++ /dev/null\n@@ -1,3 +0,0 @@\n"
    )
    new = added_lines(diff)
    assert new == {"mod/src/main/java/a/A.java": set(range(1, 11)),
                   "mod/src/main/java/a/B.java": {6}}, new
    cpd = ('<pmd-cpd><duplication lines="4" tokens="120">'
           '<file line="3" endline="6" path="C:\\repo\\mod\\src\\main\\java\\a\\A.java"/>'
           '<file line="40" path="/repo/mod/src/main/java/a/Old.java"/>'
           '</duplication></pmd-cpd>')
    ranges = duplicated_ranges(cpd, "C:/repo")
    assert ranges[0] == ("mod/src/main/java/a/A.java", 3, 6, 120), ranges
    assert ranges[1] == ("/repo/mod/src/main/java/a/Old.java", 40, 43, 120), ranges
    total, dup, touched = measure(new, ranges)
    assert (total, dup) == (11, 4), (total, dup)
    assert touched == [("mod/src/main/java/a/A.java", 3, 6)], touched
    assert measure({}, ranges)[:2] == (0, 0)
    print("duplication_gate self-test: ok")
    return 0


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--base", help="the ref the change is measured against, e.g. origin/main")
    parser.add_argument("--max-percent", type=float, default=3.0)
    parser.add_argument("cpd", nargs="*")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if not args.base or not args.cpd:
        parser.error("--base and at least one cpd.xml are required")
    root = subprocess.run(["git", "rev-parse", "--show-toplevel"], check=True,
                          capture_output=True, text=True).stdout.strip()
    diff = subprocess.run(["git", "diff", "--unified=0", "--no-color", "--no-renames",
                           args.base + "...HEAD", "--", "*.java"],
                          check=True, capture_output=True, text=True).stdout
    new = added_lines(diff)
    ranges = []
    for report in args.cpd:
        if not os.path.isfile(report):
            print(f"duplication_gate: {report} does not exist; did the cpd execution run?", file=sys.stderr)
            return 2
        with open(report, encoding="utf-8") as handle:
            ranges.extend(duplicated_ranges(handle.read(), root))
    total, dup, touched = measure(new, ranges)
    percent = 100.0 * dup / total if total else 0.0
    print(f"duplication on new code: {dup} of {total} new main-source lines ({percent:.2f}%), "
          f"limit {args.max_percent:g}%")
    for path, first, last in touched:
        print(f"  duplicated block touching new code: {path}:{first}-{last}")
    if percent > args.max_percent:
        print(f"::error::Duplication on new code is {percent:.2f}%, above {args.max_percent:g}%. "
              "Extract the repeated code, or see docs/quality-gates/new-code-gate.md.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
