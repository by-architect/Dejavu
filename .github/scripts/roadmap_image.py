#!/usr/bin/env python3
"""Draws the Dejavu Roadmap board (https://github.com/users/by-architect/projects/1) as an SVG image.

Writes the image to standard output. Needs a token that can read the project in the GH_TOKEN environment variable.
Each column of the board becomes a column of the image, with its cards and their Progress tag, in the board's order.
"""
import json
import os
import sys
import urllib.request
from datetime import datetime, timezone
from html import escape

OWNER = "by-architect"
PROJECT_NUMBER = 1
COLUMN_FIELD = "Status"
PROGRESS_FIELD = "Progress"
MAX_CARDS = 9

WIDTH = 1000
MARGIN = 16
GAP = 10
HEADER = 64
COLUMN_HEADER = 40
CARD_GAP = 8
LINE_HEIGHT = 17
CHARS_PER_LINE = 22
MAX_LINES = 3
CARD_PADDING = 10
TAG_HEIGHT = 20

QUERY = """
query($owner: String!, $number: Int!, $after: String) {
  user(login: $owner) {
    projectV2(number: $number) {
      title
      fields(first: 30) {
        nodes { ... on ProjectV2SingleSelectField { name options { name } } }
      }
      items(first: 100, after: $after) {
        pageInfo { hasNextPage endCursor }
        nodes {
          fieldValues(first: 20) {
            nodes {
              ... on ProjectV2ItemFieldSingleSelectValue {
                name
                field { ... on ProjectV2SingleSelectField { name } }
              }
            }
          }
          content {
            ... on Issue { number title }
            ... on PullRequest { number title }
            ... on DraftIssue { title }
          }
        }
      }
    }
  }
}
"""


def graphql(variables):
    request = urllib.request.Request(
        "https://api.github.com/graphql",
        data=json.dumps({"query": QUERY, "variables": variables}).encode(),
        headers={"Authorization": f"Bearer {os.environ['GH_TOKEN']}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request) as response:
        result = json.load(response)
    if result.get("errors"):
        sys.exit(f"GitHub answered with errors: {result['errors']}")
    return result["data"]["user"]["projectV2"]


def read_board():
    """Returns the board's columns in order, each with its cards as (number, title, progress)."""
    after = None
    columns = None
    while True:
        project = graphql({"owner": OWNER, "number": PROJECT_NUMBER, "after": after})
        if columns is None:
            field = next(f for f in project["fields"]["nodes"] if f and f.get("name") == COLUMN_FIELD)
            columns = {option["name"]: [] for option in field["options"]}
        for item in project["items"]["nodes"]:
            values = {
                value["field"]["name"]: value["name"]
                for value in item["fieldValues"]["nodes"]
                if value and value.get("field")
            }
            column = values.get(COLUMN_FIELD)
            content = item.get("content") or {}
            if column in columns and content.get("title"):
                columns[column].append((content.get("number"), content["title"], values.get(PROGRESS_FIELD)))
        page = project["items"]["pageInfo"]
        if not page["hasNextPage"]:
            return columns
        after = page["endCursor"]


def wrap(text, width=CHARS_PER_LINE, lines=MAX_LINES):
    """Splits [text] into at most [lines] lines of about [width] characters, ending with an ellipsis if cut."""
    result = []
    current = ""
    cut = False
    for word in text.split():
        if len(word) > width:
            word = word[: width - 1] + "…"
        candidate = f"{current} {word}".strip()
        if len(candidate) <= width:
            current = candidate
            continue
        result.append(current)
        current = word
        if len(result) == lines:
            cut = True
            current = ""
            break
    if current:
        result.append(current)
    if cut:
        last = result[-1]
        result[-1] = (last if len(last) < width else last[: width - 1]) + "…"
    return result


def card_height(title):
    return CARD_PADDING * 2 + LINE_HEIGHT * len(wrap(title)) + 6 + TAG_HEIGHT


def draw(columns):
    count = len(columns)
    column_width = (WIDTH - 2 * MARGIN - GAP * (count - 1)) / count
    heights = []
    for cards in columns.values():
        shown = cards[:MAX_CARDS]
        height = COLUMN_HEADER + sum(card_height(title) + CARD_GAP for _, title, _ in shown)
        if len(cards) > len(shown):
            height += LINE_HEIGHT + CARD_GAP
        heights.append(height + CARD_GAP)
    height = HEADER + max(heights, default=COLUMN_HEADER) + MARGIN
    updated = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{height:.0f}" '
        f'viewBox="0 0 {WIDTH} {height:.0f}" role="img" aria-label="Dejavu roadmap board">',
        "<style>",
        "text { font-family: -apple-system, 'Segoe UI', Helvetica, Arial, sans-serif; fill: #1f2328; }",
        ".page { fill: #ffffff; } .column { fill: #f6f8fa; stroke: #d1d9e0; } .card { fill: #ffffff; stroke: #d1d9e0; }",
        ".muted { fill: #59636e; } .title { font-size: 20px; font-weight: 600; }",
        ".name { font-size: 14px; font-weight: 600; } .count { font-size: 12px; } .text { font-size: 13px; }",
        ".number { font-size: 11px; } .tag { font-size: 11px; font-weight: 600; }",
        ".waiting { fill: #eaeef2; } .waiting-text { fill: #59636e; }",
        ".working { fill: #fff1c2; } .working-text { fill: #7d4e00; }",
        ".done { fill: #dafbe1; } .done-text { fill: #1a7f37; }",
        "@media (prefers-color-scheme: dark) {",
        "  text { fill: #f0f6fc; } .page { fill: #0d1117; } .column { fill: #151b23; stroke: #3d444d; }",
        "  .card { fill: #0d1117; stroke: #3d444d; } .muted { fill: #9198a1; }",
        "  .waiting { fill: #262c36; } .waiting-text { fill: #9198a1; }",
        "  .working { fill: #3b2e10; } .working-text { fill: #e3b341; }",
        "  .done { fill: #12301c; } .done-text { fill: #3fb950; }",
        "}",
        "</style>",
        f'<rect class="page" width="{WIDTH}" height="{height:.0f}" rx="12"/>',
        f'<text class="title" x="{MARGIN}" y="34">Dejavu Roadmap</text>',
        f'<text class="muted count" x="{MARGIN}" y="54">What is done, what is being worked on and what comes next. '
        f"Updated {updated}.</text>",
    ]
    tags = {"Waiting": "waiting", "Working on": "working", "Done": "done"}
    for index, (name, cards) in enumerate(columns.items()):
        x = MARGIN + index * (column_width + GAP)
        parts.append(
            f'<rect class="column" x="{x:.1f}" y="{HEADER}" width="{column_width:.1f}" '
            f'height="{heights[index]:.0f}" rx="10"/>'
        )
        parts.append(f'<text class="name" x="{x + 12:.1f}" y="{HEADER + 25}">{escape(name)}</text>')
        parts.append(
            f'<text class="muted count" x="{x + column_width - 12:.1f}" y="{HEADER + 25}" '
            f'text-anchor="end">{len(cards)}</text>'
        )
        y = HEADER + COLUMN_HEADER
        for number, title, progress in cards[:MAX_CARDS]:
            lines = wrap(title)
            card = card_height(title)
            parts.append(
                f'<rect class="card" x="{x + 8:.1f}" y="{y}" width="{column_width - 16:.1f}" height="{card}" rx="8"/>'
            )
            for line_index, line in enumerate(lines):
                baseline = y + CARD_PADDING + 12 + line_index * LINE_HEIGHT
                parts.append(f'<text class="text" x="{x + 18:.1f}" y="{baseline}">{escape(line)}</text>')
            tag_y = y + CARD_PADDING + LINE_HEIGHT * len(lines) + 6
            if number:
                parts.append(f'<text class="muted number" x="{x + 18:.1f}" y="{tag_y + 14}">#{number}</text>')
            tag = tags.get(progress)
            if tag:
                tag_width = 10 + 6.2 * len(progress)
                tag_x = x + column_width - 18 - tag_width
                parts.append(
                    f'<rect class="{tag}" x="{tag_x:.1f}" y="{tag_y}" width="{tag_width:.1f}" '
                    f'height="{TAG_HEIGHT}" rx="10"/>'
                )
                parts.append(
                    f'<text class="tag {tag}-text" x="{tag_x + tag_width / 2:.1f}" y="{tag_y + 14}" '
                    f'text-anchor="middle">{escape(progress)}</text>'
                )
            y += card + CARD_GAP
        hidden = len(cards) - MAX_CARDS
        if hidden > 0:
            parts.append(f'<text class="muted count" x="{x + 18:.1f}" y="{y + 13}">and {hidden} more</text>')
    parts.append("</svg>")
    return "\n".join(parts)


if __name__ == "__main__":
    print(draw(read_board()))
