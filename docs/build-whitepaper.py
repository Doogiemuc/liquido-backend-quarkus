#!/usr/bin/env python3
"""
Render docs/liquido-whitepaper.md into the styled whitepaper HTML page.

The markdown file is the single source of truth for TEXT. This script owns the
presentation layer only: it reuses the <title>/font/<style> chrome from the
existing page and rebuilds the entire body from the markdown, so the two can no
longer drift in wording. Re-run it after any markdown edit.

    python3 docs/build-whitepaper.py <source.md> <design.html> <out.html>
"""
import html
import re
import sys

CHIPS = {"Implemented": "chip-ok", "Designed": "chip-warn", "Envisioned": "chip-vision"}


def slug(text):
    s = re.sub(r"<[^>]+>", "", text).lower()
    s = re.sub(r"[^a-z0-9]+", "-", s).strip("-")
    return s


def inline(text):
    """Escape, then apply inline markdown. Order matters: chips before bold."""
    t = html.escape(text, quote=False)
    for label, cls in CHIPS.items():
        t = t.replace(f"**[{label}]**", f'<span class="chip {cls}">{label}</span>')
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    t = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2">\1</a>', t)
    t = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", t)
    t = re.sub(r"(?<![*\w])\*([^*]+)\*(?!\*)", r"<em>\1</em>", t)
    return t


def render_table(rows):
    head, body = rows[0], rows[2:]          # rows[1] is the |---|---| separator
    cells = lambda r: [c.strip() for c in r.strip().strip("|").split("|")]
    out = ['<div class="tablewrap">', "<table>", "  <thead><tr>"]
    out += [f"<th>{inline(c)}</th>" for c in cells(head)]
    out += ["</tr></thead>", "  <tbody>"]
    for r in body:
        out.append("    <tr>" + "".join(f"<td>{inline(c)}</td>" for c in cells(r)) + "</tr>")
    out += ["  </tbody>", "</table>", "</div>"]
    return "\n".join(out)


def convert(md):
    lines = md.split("\n")
    out, i = [], 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if not stripped:
            i += 1
            continue

        # horizontal rules: the design carries separation on the headings themselves
        if stripped == "---":
            i += 1
            continue

        # fenced code -> <pre>
        if stripped.startswith("```"):
            i += 1
            buf = []
            while i < len(lines) and not lines[i].strip().startswith("```"):
                buf.append(html.escape(lines[i], quote=False))
                i += 1
            i += 1
            out.append("<pre>" + "\n".join(buf) + "</pre>")
            continue

        # blockquote -> notice callout
        if stripped.startswith(">"):
            buf = []
            while i < len(lines) and lines[i].strip().startswith(">"):
                buf.append(lines[i].strip().lstrip(">").strip())
                i += 1
            out.append('<div class="notice">\n  <p>' + inline(" ".join(buf)) + "</p>\n</div>")
            continue

        # table
        if stripped.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append(lines[i])
                i += 1
            out.append(render_table(rows))
            continue

        # headings
        m = re.match(r"^(#{1,4})\s+(.*)$", stripped)
        if m:
            level, text = len(m.group(1)), m.group(2)
            body = inline(text)
            if level == 1:
                out.append(f'<h2 class="part" id="{slug(text)}">{body}</h2>')
            else:
                tag = {2: "h2", 3: "h3", 4: "h4"}[level]
                out.append(f'<{tag} id="{slug(text)}">{body}</{tag}>')
            i += 1
            continue

        # lists
        if re.match(r"^[-*]\s+", stripped) or re.match(r"^\d+\.\s+", stripped):
            ordered = bool(re.match(r"^\d+\.\s+", stripped))
            tag = "ol" if ordered else "ul"
            items = []
            pat = r"^\d+\.\s+" if ordered else r"^[-*]\s+"
            while i < len(lines) and re.match(pat, lines[i].strip()):
                items.append(re.sub(pat, "", lines[i].strip()))
                i += 1
            out.append(f"<{tag}>")
            out += [f"  <li>{inline(it)}</li>" for it in items]
            out.append(f"</{tag}>")
            continue

        # paragraph
        buf = []
        while i < len(lines) and lines[i].strip() and not re.match(
            r"^(#{1,4}\s|\||>|```|---$|[-*]\s|\d+\.\s)", lines[i].strip()
        ):
            buf.append(lines[i].strip())
            i += 1
        out.append("<p>" + inline(" ".join(buf)) + "</p>")

    return "\n\n".join(out)


def main():
    src, design, dest = sys.argv[1], sys.argv[2], sys.argv[3]
    md = open(src, encoding="utf-8").read()
    old = open(design, encoding="utf-8").read()

    # Reuse the existing presentation chrome verbatim: <title>, fonts, <style>.
    chrome = old[old.index("<title>"): old.index("</style>") + len("</style>")]

    # Front matter -> masthead. Everything from the first Part heading is body.
    title = re.search(r"^# (.+)$", md, re.M).group(1)
    subtitle = re.search(r"^## (.+)$", md, re.M).group(1)
    version_line = re.search(r"^\*\*(Version [^*]+)\*\*", md, re.M).group(1)
    version, year = [p.strip() for p in version_line.split("—")]

    body_start = md.index("## About this document")
    body = convert(md[body_start:])

    masthead = f'''<header class="masthead">
  <h1 class="wordmark">LIQUI<span>DO</span></h1>
  <p class="subtitle">{html.escape(subtitle.replace("A Whitepaper on ", "A whitepaper on ").replace("Voting", "voting"))}</p>
  <div class="masthead-meta">
    <span><strong>{html.escape(version)}</strong></span>
    <span>{html.escape(year)}</span>
    <span>Ranked Pairs · SHA3-256 · WebAuthn</span>
  </div>
</header>'''

    footer = f'<footer>{html.escape(title)} Whitepaper · {html.escape(version)} · {html.escape(year)} · liquido.vote</footer>'

    page = f'''{chrome}

<div class="wrap">

{masthead}

{body}

{footer}

</div>
'''
    open(dest, "w", encoding="utf-8").write(page)
    print(f"wrote {dest} ({len(page):,} bytes)")


if __name__ == "__main__":
    main()
