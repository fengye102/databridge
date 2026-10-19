"""Render a reproducible ECDF of three real CDC latency runs."""

import csv
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

plt.rcParams["svg.hashsalt"] = "databridge-cdc-latency"


def main() -> None:
    runs = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).with_name("cdc-latency-runs.csv")
    figures = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).with_name("figures")
    with runs.open(newline="", encoding="utf-8-sig") as handle:
        records = list(csv.DictReader(handle))
    if len(records) != 300:
        raise ValueError(f"Expected 300 samples, found {len(records)}")
    groups = {}
    for repetition in (1, 2, 3):
        group = [row for row in records if int(row["repetition"]) == repetition]
        if len(group) != 100 or {int(row["sample"]) for row in group} != set(range(1, 101)):
            raise ValueError(f"Repetition {repetition} must contain samples 1..100 exactly once")
        groups[repetition] = sorted(float(row["latency_ms"]) for row in group)
    if any(value < 0 for values in groups.values() for value in values):
        raise ValueError("Negative latency is invalid")
    pooled = sorted(value for values in groups.values() for value in values)
    p95 = pooled[284]  # nearest-rank ceil(0.95 * 300), zero-indexed

    fig, ax = plt.subplots(figsize=(7.5, 4.5), layout="constrained")
    for repetition, color in ((1, "#08766e"), (2, "#1f497d"), (3, "#be6a2f")):
        values = groups[repetition]
        ax.step(values, [index / 100 for index in range(1, 101)],
                where="post", label=f"Run {repetition}", color=color, linewidth=2)
    ax.axvline(p95, color="#9b2438", linestyle="--", linewidth=1.5,
               label=f"Pooled P95: {p95:.1f} ms")
    ax.set_xlim(left=0)
    ax.set_ylim(0, 1.02)
    ax.set_xlabel("Commit-call to target-observation latency (ms)")
    ax.set_ylabel("Cumulative share")
    ax.set_title("CDC observable latency, 100 ms source update interval")
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.18), ncol=2, frameon=False)
    ax.grid(alpha=0.2)
    ax.set_axisbelow(True)
    figures.mkdir(exist_ok=True)
    svg = figures / "cdc-latency-ecdf.svg"
    fig.savefig(svg, metadata={"Date": None})
    svg.write_text("\n".join(line.rstrip() for line in svg.read_text(encoding="utf-8").splitlines())
                   + "\n", encoding="utf-8")
    png = figures / "cdc-latency-ecdf.png"
    fig.savefig(png, dpi=180)
    plt.close(fig)
    print(svg)
    print(png)


if __name__ == "__main__":
    main()
