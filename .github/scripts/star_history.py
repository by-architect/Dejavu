#!/usr/bin/env python3
"""Draws Dejavu's GitHub star history as an SVG line chart.

Usage: star_history.py light|dark > stars.svg
Reads the stars from the GitHub API with the token in the GH_TOKEN environment variable (the workflow's own token is
enough for a public repository).
"""
import json
import os
import sys
import urllib.request
from datetime import datetime, timezone
from html import escape

REPO = "by-architect/Dejavu"
WIDTH, HEIGHT = 800, 320
LEFT, RIGHT, TOP, BOTTOM = 56, 24, 56, 44
THEMES = {
    "light": {"ink": "#2E2416", "muted": "#7A6A55", "grid": "#EADBC0", "line": "#C98A1B", "fill": "#F2B84B"},
    "dark": {"ink": "#F6EBD7", "muted": "#AE9B7E", "grid": "#3A2E1F", "line": "#F2B84B", "fill": "#F2B84B"},
}


def star_dates():
    dates, page = [], 1
    while True:
        request = urllib.request.Request(
            f"https://api.github.com/repos/{REPO}/stargazers?per_page=100&page={page}",
            headers={"Accept": "application/vnd.github.star+json", "Authorization": f"Bearer {os.environ['GH_TOKEN']}"},
        )
        with urllib.request.urlopen(request) as response:
            batch = json.load(response)
        dates += [datetime.fromisoformat(s["starred_at"].replace("Z", "+00:00")) for s in batch]
        if len(batch) < 100:
            return sorted(dates)
        page += 1


def draw(dates, theme):
    colors = THEMES[theme]
    now = datetime.now(timezone.utc)
    start = dates[0] if dates else now
    span = max((now - start).total_seconds(), 1)
    top = max(len(dates), 1)
    plot_w, plot_h = WIDTH - LEFT - RIGHT, HEIGHT - TOP - BOTTOM

    def x(when):
        return LEFT + (when - start).total_seconds() / span * plot_w

    def y(count):
        return TOP + plot_h - count / top * plot_h

    points = [(LEFT, y(0))]
    for count, when in enumerate(dates, 1):
        points += [(x(when), y(count - 1)), (x(when), y(count))]
    points.append((LEFT + plot_w, y(len(dates))))
    line = " ".join(f"{px:.1f},{py:.1f}" for px, py in points)
    area = f"{LEFT},{y(0):.1f} {line} {LEFT + plot_w},{y(0):.1f}"

    ticks = sorted({0, top // 2, top})
    grid = "".join(
        f'<line x1="{LEFT}" y1="{y(t):.1f}" x2="{LEFT + plot_w}" y2="{y(t):.1f}" stroke="{colors["grid"]}"/>'
        f'<text x="{LEFT - 10}" y="{y(t) + 4:.1f}" text-anchor="end" fill="{colors["muted"]}" font-size="12">{t}</text>'
        for t in ticks
    )
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{HEIGHT}" viewBox="0 0 {WIDTH} {HEIGHT}" font-family="system-ui, -apple-system, Segoe UI, sans-serif">
<title>Star history of {escape(REPO)}</title>
<text x="{LEFT}" y="30" fill="{colors["ink"]}" font-size="18" font-weight="700">Star history</text>
<text x="{LEFT + plot_w}" y="30" text-anchor="end" fill="{colors["ink"]}" font-size="18" font-weight="700">{len(dates)} stars</text>
{grid}
<polygon points="{area}" fill="{colors["fill"]}" fill-opacity="0.25"/>
<polyline points="{line}" fill="none" stroke="{colors["line"]}" stroke-width="2.5" stroke-linejoin="round"/>
<text x="{LEFT}" y="{HEIGHT - 16}" fill="{colors["muted"]}" font-size="12">{start:%d %b %Y}</text>
<text x="{LEFT + plot_w}" y="{HEIGHT - 16}" text-anchor="end" fill="{colors["muted"]}" font-size="12">{now:%d %b %Y}</text>
</svg>
"""


if __name__ == "__main__":
    sys.stdout.write(draw(star_dates(), sys.argv[1] if len(sys.argv) > 1 else "light"))
