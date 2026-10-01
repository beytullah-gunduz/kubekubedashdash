#!/usr/bin/env python3
"""Turn the screenshot generator's raw captures into the landing page's images.

Run the generator first (it drives the app against the built-in demo cluster and writes
raw PNGs into build/screenshots/):

    ./gradlew :composeApp:generateScreenshots
    python3 scripts/site_images.py [--src build/screenshots] [--out docs] [--icons-only]

Writes, under --out:

    img/hero-<look>.webp, img/hero-<look>-1000.webp    the hero's ten looks
    img/<feature>.webp                                  the feature tiles (cropped, some stitched)
    img/<feature>-mobile.webp                           the phone-width crops of the wide tiles
    img/retro-crt.webp, img/retro-crt-poster.webp       the Retro power-on loop and its poster
    img/retro-mode.png                                  the "RETRO MODE" eyebrow, in the Retro font
    img/og.png                                          the 1200x630 social card
    img/mark-64.png, img/apple-touch-icon.png           the brand mark and the iOS home-screen icon
    favicon.ico                                         16/32/48 px, from docs/favicon.png
    screenshots/{overview,fleet,topology}.png           the three images the README shows

--icons-only writes just the three icon files (no captures needed). The icons always come from
the repo's own docs/favicon.png, which is only read, never overwritten.

Needs Python 3.10+ and Pillow 10+ (with WebP support); no other tools. Not run in CI: the
captures need the real app on a real display, so the converted images are committed.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

REPO = Path(__file__).resolve().parent.parent
FONT_DIR = REPO / "composeApp" / "src" / "desktopMain" / "composeResources" / "font"

# The generator's window is 1640x1160 dp; a Retina capture is exactly twice that in pixels.
WINDOW_DP_WIDTH = 1640

HERO_SLUGS = [
    "default-dark",
    "default-light",
    "catppuccin",
    "nord",
    "dracula",
    "gruvbox",
    "solarized",
    "high-contrast",
    "monochrome",
    "retro",
]

# A crop box (left, top, right, bottom) in dp of the 1640x1160 window.
Box = tuple[int, int, int, int]

# Feature captures -> optional crop. A box is multiplied by (image width / 1640) before
# cropping, so it works at 1x and 2x. A list of boxes is a stitch: each part is cropped the same
# way and the parts are stacked top to bottom with no gap (all parts share left and right).
CROPS: dict[str, Box | list[Box] | None] = {
    "fleet": None,
    "pod-why": [(1074, 80, 1640, 332), (1074, 708, 1640, 932)],  # header + warning, container card
    "bulk": (292, 40, 1640, 600),
    "tail": [(0, 808, 1100, 911), (0, 923, 1100, 1160)],  # drawer header, first whole line on
    "port-forward": (547, 332, 1093, 828),  # dialog + 16 dp scrim
    "palette": (516, 80, 1126, 590),
    "secret": (1070, 80, 1640, 445),  # pane header to end of YAML
}

# Phone-width crops of the wide tiles, written as img/<name>-mobile.webp. These are dp boxes of
# the same capture, not output sizes; the page's width/height attributes follow the output.
MOBILE_CROPS: dict[str, Box | list[Box]] = {
    "fleet": (0, 405, 900, 1040),  # cluster cards + the event feed's left columns
    "bulk": (292, 40, 1020, 600),  # checkboxes, names, statuses, "6 pods selected"
    "tail": [(0, 808, 620, 911), (0, 923, 620, 1160)],
}

HERO_WIDTH = 2000
HERO_SMALL_WIDTH = 1000
FEATURE_MAX_WIDTH = 1600
MOBILE_MAX_WIDTH = 900
WEBP_QUALITY = 82
CRT_WIDTH = 1200
CRT_QUALITY = 72
CRT_BEFORE_HOLD_MS = 1200  # the Default look before the switch
CRT_FIRST_HOLD_MS = 600
CRT_LAST_HOLD_MS = 2800
CRT_MIN_FRAME_GAP_MS = 33  # drop frames closer than this (real ms) to the previously kept one
CRT_MIN_DURATION_MS = 20
README_WIDTH = 1640

# Icons, all derived from the repo's own 512 px docs/favicon.png (read only).
FAVICON_SOURCE = REPO / "docs" / "favicon.png"
MARK_SIZE = 64
APPLE_TOUCH_SIZE = 180
APPLE_TOUCH_UNDERLAY = "#141e30"  # iOS paints transparency black, so the icon gets a navy base
FAVICON_ICO_SIZES = [(16, 16), (32, 32), (48, 48)]

# The Retro mode eyebrow, rendered once here instead of shipping the 70 KB Sixtyfour web font.
RETRO_EYEBROW_TEXT = "RETRO MODE"
RETRO_EYEBROW_FONT = FONT_DIR / "sixtyfour_regular.ttf"
RETRO_EYEBROW_SIZE = 24  # px; the page shows it at half size for 2x sharpness
RETRO_EYEBROW_SPACING = 3  # px between glyphs
RETRO_EYEBROW_COLOR = "#33ff66"
RETRO_EYEBROW_PAD = 2


def load_rgb(path: Path) -> Image.Image:
    with Image.open(path) as im:
        return im.convert("RGB")


def fit_width(img: Image.Image, width: int) -> Image.Image:
    """Scale down to `width` px wide; never upscale (a narrower source keeps its width)."""
    if img.width <= width:
        return img
    return img.resize((width, round(img.height * width / img.width)), Image.Resampling.LANCZOS)


def save_webp(img: Image.Image, path: Path, quality: int = WEBP_QUALITY) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "WEBP", quality=quality, method=6)


def crop_dp(img: Image.Image, box: Box) -> Image.Image:
    k = img.width / WINDOW_DP_WIDTH
    left, top, right, bottom = (round(v * k) for v in box)
    return img.crop((max(0, left), max(0, top), min(img.width, right), min(img.height, bottom)))


def crop_or_stitch(img: Image.Image, spec: Box | list[Box]) -> Image.Image:
    """Crop one dp box, or crop each box of a list and stack the parts with no gap."""
    if not isinstance(spec, list):
        return crop_dp(img, spec)
    assert spec, "a stitched crop needs at least one box"
    assert len({(b[0], b[2]) for b in spec}) == 1, f"stitched parts must share left/right: {spec}"
    parts = [crop_dp(img, b) for b in spec]
    stitched = Image.new("RGB", (parts[0].width, sum(p.height for p in parts)))
    y = 0
    for part in parts:
        stitched.paste(part, (0, y))
        y += part.height
    return stitched


def missing_inputs(src: Path) -> list[Path]:
    """Every expected capture that is absent, including the CRT frames frames.json lists."""
    needed = [src / f"hero-{slug}.png" for slug in HERO_SLUGS]
    needed += [src / f"{name}.png" for name in {*CROPS, *MOBILE_CROPS}]
    needed.append(src / "topology.png")  # README only; the page has no topology tile
    frames_json = src / "crt" / "frames.json"
    needed.append(frames_json)
    if frames_json.is_file():
        try:
            entries = json.loads(frames_json.read_text())
            needed += [src / "crt" / e["file"] for e in entries]
            if not entries:
                needed.append(src / "crt" / "<at least one frame in frames.json>")
        except (ValueError, KeyError, TypeError) as e:
            print(f"error: {frames_json} is not a list of {{file, t}} objects ({e})", file=sys.stderr)
            sys.exit(1)
    return [p for p in needed if not p.is_file()]


def build_heroes(src: Path, img_dir: Path, written: list[Path]) -> Image.Image:
    """Hero set: 2000 px and 1000 px WebP per look. Returns the default-dark capture for reuse."""
    default_dark: Image.Image | None = None
    for slug in HERO_SLUGS:
        img = load_rgb(src / f"hero-{slug}.png")
        if img.width < HERO_WIDTH:
            print(
                f"warning: hero-{slug}.png is {img.width} px wide, below {HERO_WIDTH} "
                "(only a Retina display reaches it); kept at its own width, not upscaled",
                file=sys.stderr,
            )
        big = img_dir / f"hero-{slug}.webp"
        small = img_dir / f"hero-{slug}-1000.webp"
        save_webp(fit_width(img, HERO_WIDTH), big)
        save_webp(fit_width(img, HERO_SMALL_WIDTH), small)
        written += [big, small]
        if slug == "default-dark":
            default_dark = img
    assert default_dark is not None
    return default_dark


def build_features(src: Path, img_dir: Path, written: list[Path]) -> None:
    for name, spec in CROPS.items():
        img = load_rgb(src / f"{name}.png")
        if spec is not None:
            img = crop_or_stitch(img, spec)
        out = img_dir / f"{name}.webp"
        save_webp(fit_width(img, FEATURE_MAX_WIDTH), out)
        written.append(out)


def build_mobile_features(src: Path, img_dir: Path, written: list[Path]) -> None:
    """Phone crops of the wide tiles: img/<name>-mobile.webp, at most 900 px wide."""
    for name, spec in MOBILE_CROPS.items():
        img = crop_or_stitch(load_rgb(src / f"{name}.png"), spec)
        out = img_dir / f"{name}-mobile.webp"
        save_webp(fit_width(img, MOBILE_MAX_WIDTH), out)
        written.append(out)


def build_crt(src: Path, img_dir: Path, written: list[Path]) -> None:
    """Animated WebP of the real Retro power-on, plus a still poster of its last frame."""
    crt_dir = src / "crt"
    entries = json.loads((crt_dir / "frames.json").read_text())
    entries.sort(key=lambda e: e["t"])

    # Stream the frames: a full-size RGB frame is ~22 MB, so only the small copies are kept.
    first: Image.Image | None = None
    leading = True
    kept: list[tuple[float, Image.Image]] = []  # (real ms, resized frame)
    for e in entries[:-1]:
        full = load_rgb(crt_dir / e["file"])
        if first is None:
            first = full
        # The style switch recomposes before the power-on starts, so the capture opens with a
        # run of frames identical to frame 0; drop that run.
        if leading and ImageChops.difference(first, full).getbbox() is None:
            continue
        leading = False
        if kept and e["t"] - kept[-1][0] < CRT_MIN_FRAME_GAP_MS:
            continue
        kept.append((float(e["t"]), fit_width(full, CRT_WIDTH)))

    last_entry = entries[-1]
    last_full = load_rgb(crt_dir / last_entry["file"])
    if first is not None and leading and ImageChops.difference(first, last_full).getbbox() is None:
        print(
            "warning: every CRT frame is identical; the capture never saw the power-on",
            file=sys.stderr,
        )
    last_small = fit_width(last_full, CRT_WIDTH)
    frames = kept + [(float(last_entry["t"]), last_small)]

    durations = [max(CRT_MIN_DURATION_MS, round(b[0] - a[0])) for a, b in zip(frames, frames[1:])]
    durations.append(CRT_LAST_HOLD_MS)
    # "Prepend the first frame held 600 ms" == the first frame's own duration plus the hold.
    durations[0] += CRT_FIRST_HOLD_MS
    # Open on the Default look the switch starts from, so the loop shows the switch itself.
    if first is not None:
        frames.insert(0, (0.0, fit_width(first, CRT_WIDTH)))
        durations.insert(0, CRT_BEFORE_HOLD_MS)

    out = img_dir / "retro-crt.webp"
    out.parent.mkdir(parents=True, exist_ok=True)
    frames[0][1].save(
        out,
        "WEBP",
        save_all=True,
        append_images=[f for _, f in frames[1:]],
        duration=durations,
        loop=0,
        quality=CRT_QUALITY,
        method=6,
    )
    poster = img_dir / "retro-crt-poster.webp"
    save_webp(last_small, poster)
    written += [out, poster]
    print(f"CRT loop: {len(entries)} captured frames -> {len(frames)} kept, {sum(durations)} ms", file=sys.stderr)


def build_icons(out: Path, written: list[Path]) -> None:
    """Brand mark, iOS home-screen icon and favicon.ico, all from docs/favicon.png (read only)."""
    if not FAVICON_SOURCE.is_file():
        print(f"error: {FAVICON_SOURCE} not found", file=sys.stderr)
        sys.exit(1)
    with Image.open(FAVICON_SOURCE) as opened:
        src = opened.convert("RGBA")
    img_dir = out / "img"
    img_dir.mkdir(parents=True, exist_ok=True)

    mark = img_dir / "mark-64.png"
    src.resize((MARK_SIZE, MARK_SIZE), Image.Resampling.LANCZOS).save(mark, "PNG", optimize=True)

    touch = img_dir / "apple-touch-icon.png"
    flat = Image.alpha_composite(Image.new("RGBA", src.size, APPLE_TOUCH_UNDERLAY), src).convert("RGB")
    flat.resize((APPLE_TOUCH_SIZE, APPLE_TOUCH_SIZE), Image.Resampling.LANCZOS).save(touch, "PNG", optimize=True)

    ico = out / "favicon.ico"
    src.save(ico, sizes=FAVICON_ICO_SIZES)
    written += [mark, touch, ico]


def build_retro_eyebrow(img_dir: Path, written: list[Path]) -> None:
    """"RETRO MODE" in Sixtyfour, drawn glyph by glyph with fixed spacing, trimmed to its ink."""
    text = RETRO_EYEBROW_TEXT
    font = ImageFont.truetype(str(RETRO_EYEBROW_FONT), RETRO_EYEBROW_SIZE)
    width = int(sum(font.getlength(c) for c in text)) + RETRO_EYEBROW_SPACING * (len(text) - 1) + 8
    canvas = Image.new("RGBA", (width, 48), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    x = 4.0
    for ch in text:
        draw.text((x, 4), ch, font=font, fill=RETRO_EYEBROW_COLOR)
        x += font.getlength(ch) + RETRO_EYEBROW_SPACING
    bbox = canvas.getbbox()
    assert bbox is not None, "the eyebrow rendered no ink"
    pad = RETRO_EYEBROW_PAD
    out = img_dir / "retro-mode.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    canvas.crop((bbox[0] - pad, bbox[1] - pad, bbox[2] + pad, bbox[3] + pad)).save(out, "PNG", optimize=True)
    written.append(out)


def build_og(hero: Image.Image, out: Path, written: list[Path]) -> None:
    """1200x630 social card: title, tagline, and the top of the default-dark hero."""
    canvas = Image.new("RGB", (1200, 630), "#0a1322")
    shot = fit_width(hero, 1100)
    draw = ImageDraw.Draw(canvas)
    x, y = 50, 210
    # 1 px border just outside the screenshot; the screenshot runs off the canvas bottom.
    draw.rectangle([x - 1, y - 1, x + shot.width, y + shot.height], outline="#2c4a6b", width=1)
    canvas.paste(shot, (x, y))
    title = ImageFont.truetype(str(FONT_DIR / "inter_semibold.ttf"), 56)
    subtitle = ImageFont.truetype(str(FONT_DIR / "inter_regular.ttf"), 28)
    draw.text((50, 48), "KubeKubeDashDash", font=title, fill="#e9eff7")
    draw.text((50, 120), "Operate your whole Kubernetes fleet from one desktop app.", font=subtitle, fill="#93a4bd")
    out.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out, "PNG", optimize=True)
    written.append(out)


def build_readme_images(src: Path, hero: Image.Image, shots_dir: Path, written: list[Path]) -> None:
    shots_dir.mkdir(parents=True, exist_ok=True)
    sources = {
        "overview": hero,
        "fleet": load_rgb(src / "fleet.png"),
        "topology": load_rgb(src / "topology.png"),
    }
    for name, img in sources.items():
        out = shots_dir / f"{name}.png"
        fit_width(img, README_WIDTH).save(out, "PNG", optimize=True)
        written.append(out)


def print_table(written: list[Path], root: Path) -> None:
    rows = []
    for path in written:
        with Image.open(path) as im:
            size = f"{im.width} x {im.height}"
            if path.suffix == ".ico":
                size = "ico " + ", ".join(f"{w}" for w, _ in sorted(im.info.get("sizes", [im.size])))
        rows.append((str(path.relative_to(root)), size, f"{path.stat().st_size / 1024:.0f}"))
    name_w = max(len(r[0]) for r in rows + [("file", "", "")])
    size_w = max(len(r[1]) for r in rows + [("", "width x height", "")])
    print(f"{'file':<{name_w}}  {'width x height':<{size_w}}  {'KB':>6}")
    for name, size, kb in rows:
        print(f"{name:<{name_w}}  {size:<{size_w}}  {kb:>6}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Convert raw screenshot captures into the landing page's images.")
    parser.add_argument("--src", type=Path, default=REPO / "build" / "screenshots", help="generator output (default: build/screenshots)")
    parser.add_argument("--out", type=Path, default=REPO / "docs", help="site root to write img/ and screenshots/ into (default: docs)")
    parser.add_argument("--icons-only", action="store_true", help="write only mark-64.png, apple-touch-icon.png and favicon.ico (needs no captures)")
    args = parser.parse_args()

    if args.icons_only:
        icons: list[Path] = []
        build_icons(args.out, icons)
        print_table(icons, args.out)
        return 0

    missing = missing_inputs(args.src)
    if missing:
        print(f"error: {len(missing)} expected capture(s) missing from {args.src}:", file=sys.stderr)
        for path in missing:
            print(f"  {path.name if path.parent == args.src else Path(path.parent.name) / path.name}", file=sys.stderr)
        print("Run `./gradlew :composeApp:generateScreenshots` first.", file=sys.stderr)
        return 1

    img_dir = args.out / "img"
    written: list[Path] = []
    hero = build_heroes(args.src, img_dir, written)
    build_features(args.src, img_dir, written)
    build_mobile_features(args.src, img_dir, written)
    build_retro_eyebrow(img_dir, written)
    build_icons(args.out, written)
    build_crt(args.src, img_dir, written)
    build_og(hero, img_dir / "og.png", written)
    build_readme_images(args.src, hero, args.out / "screenshots", written)
    print_table(written, args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
