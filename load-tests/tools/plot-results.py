#!/usr/bin/env python3
"""
Generate comparison graphs for async-test-lib load-test results.

Usage:
    python tools/plot-results.py

Reads results/<version>/throughput.csv, memory.csv, jmh.json for every version
subdirectory it finds, then writes five PNG files into results/_plots/.

Install deps once:
    python -m pip install matplotlib numpy
"""

import json
import re
import sys
from pathlib import Path

try:
    import matplotlib.pyplot as plt
    import numpy as np
except ImportError:
    sys.exit("Missing dependencies — run: python -m pip install matplotlib numpy")

RESULTS_DIR = Path(__file__).parent.parent / "results"
PLOTS_DIR = RESULTS_DIR / "_plots"
PLOTS_DIR.mkdir(parents=True, exist_ok=True)

# Colour palette (one per version, wraps if >9 versions)
COLORS = [
    "#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd",
    "#8c564b", "#e377c2", "#7f7f7f", "#bcbd22",
]


# ── Helpers ──────────────────────────────────────────────────────────────────

def find_versions() -> list[str]:
    vers = sorted(
        d.name for d in RESULTS_DIR.iterdir()
        if d.is_dir() and re.match(r"\d+\.\d+", d.name)
    )
    if not vers:
        print("No version directories found in", RESULTS_DIR)
    return vers


def load_csv(path: Path) -> list[dict]:
    """Parse a comment-stripped CSV into a list of dicts keyed by header names."""
    if not path.exists():
        return []
    lines = [l for l in path.read_text().splitlines() if l and not l.startswith("#")]
    if not lines:
        return []
    headers = lines[0].split(",")
    rows = []
    for line in lines[1:]:
        vals = line.split(",")
        if len(vals) == len(headers):
            rows.append(dict(zip(headers, vals)))
    return rows


def load_jmh(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with open(path) as f:
        return json.load(f)


def color(idx: int) -> str:
    return COLORS[idx % len(COLORS)]


def save(name: str) -> None:
    dest = PLOTS_DIR / name
    plt.savefig(dest, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"  Wrote {dest}")


# ── Plot 1: Throughput vs thread count (no-detector baseline) ────────────────

def plot_throughput_vs_threads(versions: list[str]) -> None:
    fig, ax = plt.subplots(figsize=(8, 5))
    plotted = False

    for idx, ver in enumerate(versions):
        rows = load_csv(RESULTS_DIR / ver / "throughput.csv")
        # filter: invocations==10, detectAll==false
        pts = [r for r in rows if r["invocations"] == "10" and r["detectAll"] == "false"]
        if not pts:
            continue
        pts.sort(key=lambda r: int(r["threads"]))
        xs = [int(r["threads"]) for r in pts]
        ys = [int(r["throughputRoundsPerSec"]) for r in pts]
        ax.plot(xs, ys, marker="o", label=ver, color=color(idx))
        plotted = True

    if not plotted:
        plt.close()
        return

    ax.set_title("Throughput vs Thread Count (10 invocations, no detectors)")
    ax.set_xlabel("Threads")
    ax.set_ylabel("Test rounds / second")
    ax.set_xscale("log", base=2)
    ax.set_xticks([2, 4, 8, 16])
    ax.get_xaxis().set_major_formatter(plt.ScalarFormatter())
    ax.legend()
    ax.grid(True, alpha=0.3)
    save("throughput-vs-threads.png")


# ── Plot 2: Throughput by release (grouped bar, threads=4 baseline) ──────────

def plot_throughput_by_release(versions: list[str]) -> None:
    label_map = {
        ("2",  "10",  "false"): "t2 i10 none",
        ("4",  "10",  "false"): "t4 i10 none",
        ("8",  "10",  "false"): "t8 i10 none",
        ("2",  "100", "false"): "t2 i100 none",
        ("4",  "100", "false"): "t4 i100 none",
        ("8",  "100", "false"): "t8 i100 none",
    }
    benchmarks = list(label_map.values())
    n_bench = len(benchmarks)
    n_ver = len(versions)
    if n_ver == 0:
        return

    data = {ver: {} for ver in versions}
    for ver in versions:
        rows = load_csv(RESULTS_DIR / ver / "throughput.csv")
        for r in rows:
            key = (r["threads"], r["invocations"], r["detectAll"])
            if key in label_map:
                data[ver][label_map[key]] = int(r["throughputRoundsPerSec"])

    valid_versions = [v for v in versions if any(data[v].values())]
    if not valid_versions:
        return

    x = np.arange(n_bench)
    width = 0.8 / len(valid_versions)
    fig, ax = plt.subplots(figsize=(12, 6))

    for i, ver in enumerate(valid_versions):
        vals = [data[ver].get(b, 0) for b in benchmarks]
        offset = (i - len(valid_versions) / 2 + 0.5) * width
        ax.bar(x + offset, vals, width, label=ver, color=color(i))

    ax.set_title("Throughput by Release (no detectors)")
    ax.set_xlabel("Configuration")
    ax.set_ylabel("Test rounds / second (higher is better)")
    ax.set_xticks(x)
    ax.set_xticklabels(benchmarks, rotation=20, ha="right")
    ax.legend()
    ax.grid(True, axis="y", alpha=0.3)
    save("throughput-by-release.png")


# ── Plot 3: Detector overhead by release (JMH grouped bar) ───────────────────

def plot_detector_overhead_by_release(versions: list[str]) -> None:
    # JMH benchmark names we care about
    bench_labels = {
        "frameworkOverhead_t2_noDetectors": "t2 none",
        "frameworkOverhead_t4_noDetectors": "t4 none",
        "frameworkOverhead_t8_noDetectors": "t8 none",
        "detectorOverhead_t2_allDetectors": "t2 all",
        "detectorOverhead_t4_allDetectors": "t4 all",
        "detectorOverhead_t8_allDetectors": "t8 all",
    }
    benchmarks = list(bench_labels.values())
    valid_versions = []
    data = {}

    for ver in versions:
        records = load_jmh(RESULTS_DIR / ver / "jmh.json")
        if not records:
            continue
        vdata = {}
        for rec in records:
            bname = rec.get("benchmark", "").split(".")[-1]
            if bname in bench_labels:
                vdata[bench_labels[bname]] = {
                    "score": rec["primaryMetric"]["score"],
                    "error": rec["primaryMetric"]["scoreError"],
                }
        if vdata:
            valid_versions.append(ver)
            data[ver] = vdata

    if not valid_versions:
        return

    x = np.arange(len(benchmarks))
    width = 0.8 / len(valid_versions)
    fig, ax = plt.subplots(figsize=(12, 6))

    for i, ver in enumerate(valid_versions):
        scores = [data[ver].get(b, {}).get("score", 0) for b in benchmarks]
        errors = [data[ver].get(b, {}).get("error", 0) for b in benchmarks]
        offset = (i - len(valid_versions) / 2 + 0.5) * width
        ax.bar(x + offset, scores, width, yerr=errors, capsize=3,
               label=ver, color=color(i))

    ax.set_yscale("log")
    ax.set_title("Framework & Detector Overhead by Release (JMH, avg time, ms/op, log scale)")
    ax.set_xlabel("Benchmark")
    ax.set_ylabel("avg ms/op (lower is better, log scale)")
    ax.set_xticks(x)
    ax.set_xticklabels(benchmarks, rotation=15, ha="right")
    ax.legend()
    ax.grid(True, axis="y", alpha=0.3)
    save("detector-overhead-by-release.png")


# ── Plot 4: Detector overhead detail (linear, latest version) ────────────────

def plot_detector_overhead_detail(versions: list[str]) -> None:
    bench_pairs = [
        ("frameworkOverhead_t2_noDetectors", "detectorOverhead_t2_allDetectors", "2 threads"),
        ("frameworkOverhead_t4_noDetectors", "detectorOverhead_t4_allDetectors", "4 threads"),
        ("frameworkOverhead_t8_noDetectors", "detectorOverhead_t8_allDetectors", "8 threads"),
    ]

    for ver in reversed(versions):  # prefer latest
        records = load_jmh(RESULTS_DIR / ver / "jmh.json")
        if not records:
            continue

        jmh = {rec["benchmark"].split(".")[-1]: rec for rec in records}
        labels, scores_none, errors_none, scores_all, errors_all = [], [], [], [], []
        for none_key, all_key, label in bench_pairs:
            if none_key not in jmh or all_key not in jmh:
                continue
            labels.append(label)
            scores_none.append(jmh[none_key]["primaryMetric"]["score"])
            errors_none.append(jmh[none_key]["primaryMetric"]["scoreError"])
            scores_all.append(jmh[all_key]["primaryMetric"]["score"])
            errors_all.append(jmh[all_key]["primaryMetric"]["scoreError"])

        if not labels:
            continue

        x = np.arange(len(labels))
        width = 0.35
        fig, ax = plt.subplots(figsize=(9, 5))
        ax.bar(x - width / 2, scores_none, width, yerr=errors_none, capsize=4,
               label="No detectors", color="#1f77b4")
        ax.bar(x + width / 2, scores_all,  width, yerr=errors_all,  capsize=4,
               label="All detectors", color="#ff7f0e")

        ax.set_title(f"Detector Overhead Detail — v{ver} (JMH, avg time, ms/op)")
        ax.set_xlabel("Thread count")
        ax.set_ylabel("avg ms/op (lower is better)")
        ax.set_xticks(x)
        ax.set_xticklabels(labels)
        ax.legend()
        ax.grid(True, axis="y", alpha=0.3)
        save("detector-overhead-detail.png")
        break  # only generate for latest version that has JMH data


# ── Plot 5: Memory overhead vs invocations ────────────────────────────────────

def plot_memory_overhead(versions: list[str]) -> None:
    fig, ax = plt.subplots(figsize=(9, 5))
    plotted = False

    for idx, ver in enumerate(versions):
        rows = load_csv(RESULTS_DIR / ver / "memory.csv")
        none_pts = sorted(
            [r for r in rows if r.get("threads") == "4"],
            key=lambda r: int(r["invocations"])
        )
        if not none_pts:
            continue

        xs = [int(r["invocations"]) for r in none_pts]
        overhead = [float(r["overheadKB"]) / 1024 for r in none_pts]  # → MB
        ax.plot(xs, overhead, marker="o", label=ver, color=color(idx))
        plotted = True

    if not plotted:
        plt.close()
        return

    ax.set_title("Detector Memory Overhead vs Invocations (threads=4)")
    ax.set_xlabel("Invocations (log scale)")
    ax.set_ylabel("Peak heap overhead vs no-detector run (MB)")
    ax.set_xscale("log")
    ax.get_xaxis().set_major_formatter(plt.ScalarFormatter())
    ax.legend()
    ax.grid(True, alpha=0.3)
    save("memory-overhead-vs-invocations.png")


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    versions = find_versions()
    if not versions:
        sys.exit(1)
    print(f"Versions found: {versions}")
    print(f"Writing plots to {PLOTS_DIR}\n")

    plot_throughput_vs_threads(versions)
    plot_throughput_by_release(versions)
    plot_detector_overhead_by_release(versions)
    plot_detector_overhead_detail(versions)
    plot_memory_overhead(versions)

    print("\nDone.")
