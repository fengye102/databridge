"""Render the implemented single-table copy/CDC handoff for the thesis."""

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch


OUTPUT = Path(__file__).with_name("databridge-data-flow.png")
fig, ax = plt.subplots(figsize=(10.2, 3.7), dpi=180)
fig.patch.set_facecolor("white")
ax.set_xlim(0, 10.2)
ax.set_ylim(0, 3.7)
ax.axis("off")


def box(x, y, width, height, label, fill):
    ax.add_patch(
        FancyBboxPatch(
            (x, y), width, height,
            boxstyle="round,pad=0.12,rounding_size=0.14",
            linewidth=1.3, edgecolor="#24465c", facecolor=fill,
        )
    )
    ax.text(x + width / 2, y + height / 2, label,
            ha="center", va="center", fontsize=10, color="#142c3b")


def arrow(start, end, label=None, label_offset=(0, 0)):
    ax.add_patch(FancyArrowPatch(start, end, arrowstyle="-|>", mutation_scale=16,
                                 linewidth=1.6, color="#297e82"))
    if label:
        ax.text((start[0] + end[0]) / 2 + label_offset[0],
                (start[1] + end[1]) / 2 + label_offset[1], label,
                fontsize=8.2, color="#24465c", ha="center", va="center")


box(0.25, 1.38, 1.5, 0.92, "MySQL\nsource table", "#e8f3f1")
box(2.65, 2.36, 1.65, 0.72, "FullCopyWorker", "#e8f3f1")
box(2.65, 0.60, 1.65, 0.72, "Debezium Engine", "#e8f3f1")
box(5.15, 0.60, 1.55, 0.72, "cdc_event\n(PostgreSQL)", "#e9eef8")
box(7.35, 0.60, 1.55, 0.72, "EventApplier", "#e8f3f1")
box(8.15, 2.36, 1.7, 0.72, "PostgreSQL\ntarget table", "#e9eef8")

arrow((1.77, 2.06), (2.58, 2.62), "JDBC", (0.0, 0.18))
arrow((4.32, 2.72), (8.08, 2.72), "full copy only before handoff", (0, 0.22))
arrow((1.77, 1.58), (2.58, 0.96), "Binlog", (0, -0.18))
arrow((4.32, 0.96), (5.08, 0.96), "persist", (0, 0.2))
arrow((6.72, 0.96), (7.28, 0.96))
arrow((8.90, 1.05), (9.00, 2.28), "replay after copy", (0.60, 0))

ax.text(5.10, 3.42, "TaskCoordinator: capture ready → copy → ordered replay → streaming",
        ha="center", va="center", fontsize=10, fontweight="bold", color="#143a4c")
fig.savefig(OUTPUT, bbox_inches="tight", pad_inches=0.12)
plt.close(fig)
print(OUTPUT)
