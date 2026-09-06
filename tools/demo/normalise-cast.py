#!/usr/bin/env python3
"""Reduce an asciinema cast to the text the demo actually says.

The `Update demo GIF` workflow used to compare `docs/diagrams/demo.cast` byte for byte, and
that comparison can never come out equal: the cast is a live capture of a running JVM. Issue
#486 measured it - all 14 lines of the file differed between two runs, for four independent
reasons, and only the first is cosmetic:

  1. the header carries the wall clock of the recording;
  2. every event is prefixed with elapsed seconds to six decimals, which track runner speed;
  3. the split of output into events follows PTY read timing rather than content, so the same
     text arrives as one event on one run and two on the next;
  4. the recorded text itself differs, because the demo is a recording of an actual race and
     the race lands differently each time.

So the workflow proposed a pull request after every qualifying merge, each one a diff of one
binary and fourteen timing lines. This script fixes the cause: it produces a normalised form
that changes when the demo's *output* changes and not otherwise.

  python3 tools/demo/normalise-cast.py docs/diagrams/demo.cast

What it does, in order: takes only the "o" (output) events, joins their payloads into one blob
so the event split cannot matter, strips ANSI control sequences, then masks the things a rerun
legitimately varies. The masking is the fiddly part and is deliberately narrow: mask too little
and the pull request still comes back at random, mask too much and a real change to the demo
output stops being noticed.
"""

import json
import re
import sys

# CSI and OSC escape sequences. The demo is colourful, and colour is not content.
ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]"
                  r"|\x1b\][^\x07\x1b]*(?:\x07|\x1b\x5c)"
                  r"|\x1b[@-Z\x5c-_]")

MASKS = [
    # Identity hashes: java.lang.Object@1b6d3586. Different every JVM, never content.
    (re.compile(r"@[0-9a-f]{4,}\b"), "@HASH"),
    # Thread ids and worker names. How many threads is content and is left alone; which
    # numbered thread did a thing is not, because the scheduler picks.
    (re.compile(r"\bthread \d+\b", re.IGNORECASE), "thread N"),
    (re.compile(r"\basync-test-worker-\d+\b"), "async-test-worker-N"),
    (re.compile(r"\bThread-\d+\b"), "Thread-N"),
    # Durations. A demo that takes 9.7s on one runner and 11.2s on another says the same thing.
    (re.compile(r"\b\d+(?:\.\d+)?\s?ms\b"), "Nms"),
    (re.compile(r"\b\d+(?:\.\d+)?\s?s\b"), "Ns"),
]

# The access-sequence lines the race detector prints, e.g.
#   thread N write followed by thread N read
# Which verb follows which is the race landing one way or the other, and that is exactly what a
# rerun changes. The line's presence and shape still have to match, so only the verbs go.
ACCESS_SEQUENCE = re.compile(r"(?<=thread N )(write|read)(?=( followed by|\s*$))")


def normalise(path):
    """Returns the demo's output text with what a rerun legitimately varies masked out."""
    payloads = []
    with open(path, encoding="utf-8") as cast:
        # The first line is the header: version, geometry, and the recording's wall clock.
        # Nothing in it is content, so it is skipped entirely rather than partly masked.
        cast.readline()
        for line in cast:
            line = line.strip()
            if not line:
                continue
            event = json.loads(line)
            # [elapsed, kind, payload]. Only "o" is output; input and resize events say nothing
            # about what the demo printed.
            if len(event) >= 3 and event[1] == "o":
                payloads.append(event[2])

    text = ANSI.sub("", "".join(payloads))
    for pattern, replacement in MASKS:
        text = pattern.sub(replacement, text)
    # Applied after the thread mask, because it anchors on the masked form.
    text = "\n".join(ACCESS_SEQUENCE.sub("ACCESS", part) for part in text.split("\n"))
    # A PTY flushes where it likes, so trailing whitespace is not content either.
    text = text.replace("\r\n", "\n")
    return "\n".join(part.rstrip() for part in text.split("\n")).rstrip() + "\n"


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: normalise-cast.py <cast>", file=sys.stderr)
        raise SystemExit(2)
    # Written as bytes: the demo prints emoji severity markers, and a runner whose stdout
    # encoding is not UTF-8 would otherwise fail on them rather than on any real difference.
    sys.stdout.buffer.write(normalise(sys.argv[1]).encode("utf-8"))
