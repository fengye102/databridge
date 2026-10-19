"""Render two thesis figures from the checked six-group full-copy summary CSV."""

import csv
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

plt.rcParams["svg.hashsalt"] = "databridge-full-copy"


def main() -> None:
    summary = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).with_name("full-copy-summary.csv")
    figures = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).with_name("figures")
    with summary.open(newline="", encoding="utf-8-sig") as handle:
        input_records = list(csv.DictReader(handle))
        records = {(int(row["rows"]), int(row["batch_size"])): row for row in input_records}
    expected = {(rows, batch) for rows in (10_000, 100_000, 1_000_000) for batch in (500, 1000)}
    if len(input_records) != 6 or set(records) != expected:
        raise ValueError(f"Expected six unique row-count/batch-size groups, found {sorted(records)}")
    figures.mkdir(exist_ok=True)

    labels = ["10k", "100k", "1m"]
    x = list(range(len(labels)))
    width = 0.34
    for filename, key, divisor, ylabel, title in (
        ("full-copy-median-time.svg", "median_elapsed_ms", 1000, "Median copy time (s)", "Full-copy time"),
        ("full-copy-throughput.svg", "mean_rows_per_second", 1, "Mean rows per second", "Full-copy throughput"),
    ):
        fig, ax = plt.subplots(figsize=(7.2, 4.2), layout="constrained")
        for offset, batch, color in ((-0.5, 500, "#08766e"), (0.5, 1000, "#1f497d")):
            values = [float(records[(rows, batch)][key]) / divisor for rows in (10_000, 100_000, 1_000_000)]
            ax.bar([position + offset * width for position in x], values, width,
                   label=f"Batch {batch}", color=color)
        ax.set_xticks(x, labels)
        ax.set_xlabel("Source rows")
        ax.set_ylabel(ylabel)
        ax.set_title(title)
        ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.18), ncol=2, frameon=False)
        ax.grid(axis="y", alpha=0.2)
        ax.set_axisbelow(True)
        svg_path = figures / filename
        fig.savefig(svg_path, metadata={"Date": None})
        svg_lines = svg_path.read_text(encoding="utf-8").splitlines()
        svg_path.write_text("\n".join(line.rstrip() for line in svg_lines) + "\n", encoding="utf-8")
        fig.savefig(figures / filename.replace(".svg", ".png"), dpi=180)
        plt.close(fig)
        print(figures / filename)


if __name__ == "__main__":
    main()
