#!/usr/bin/env python3
"""Turn the screenshot generator's raw captures into the landing page's images.

Run the generator first (it drives the app against the built-in demo cluster and writes
raw PNGs into build/screenshots/):

    ./gradlew :composeApp:generateScreenshots
    python3 scripts/site_images.py [--src build/screenshots] [--out docs]

Writes, under --out:

    img/hero-<look>.webp, img/hero-<look>-1000.webp    the hero's ten looks
    img/<feature>.webp                                  the feature tiles (cropped)
    img/retro-crt.webp, img/retro-crt-poster.webp       the Retro power-on loop and its poster
    img/og.png                                          the 1200x630 social card
    screenshots/{overview,fleet,topology}.png           the three images the README shows

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

# Feature captures -> optional crop box (left, top, right, bottom) in dp of the 1640x1160
# window. Multiplied by (image width / 1640) before cropping, so it works at 1x and 2x.
CROPS: dict[str, tuple[int, int, int, int] | None] = {
    "fleet": None,
    "pod-why": (1066, 30, 1640, 930),
    "bulk": (268, 40, 1640, 600),
    "tail": (0, 820, 1100, 1160),
    "port-forward": (523, 308, 1117, 853),
    "palette": (516, 80, 1126, 590),
    "secret": (1066, 30, 1640, 560),
}

HERO_WIDTH = 2000
HERO_SMALL_WIDTH = 1000
FEATURE_MAX_WIDTH = 1600
WEBP_QUALITY = 82
CRT_WIDTH = 1200
CRT_QUALITY = 72
CRT_BEFORE_HOLD_MS = 1200  # the Default look before the switch
CRT_FIRST_HOLD_MS = 600
CRT_LAST_HOLD_MS = 2800
CRT_MIN_FRAME_GAP_MS = 33  # drop frames closer than this (real ms) to the previously kept one
CRT_MIN_DURATION_MS = 20
README_WIDTH = 1640


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


def crop_dp(img: Image.Image, box: tuple[int, int, int, int]) -> Image.Image:
    k = img.width / WINDOW_DP_WIDTH
    left, top, right, bottom = (round(v * k) for v in box)
    return img.crop((max(0, left), max(0, top), min(img.width, right), min(img.height, bottom)))


def missing_inputs(src: Path) -> list[Path]:
    """Every expected capture that is absent, including the CRT frames frames.json lists."""
    needed = [src / f"hero-{slug}.png" for slug in HERO_SLUGS]
    needed += [src / f"{name}.png" for name in CROPS]
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
    for name, box in CROPS.items():
        img = load_rgb(src / f"{name}.png")
        if box is not None:
            img = crop_dp(img, box)
        out = img_dir / f"{name}.webp"
        save_webp(fit_width(img, FEATURE_MAX_WIDTH), out)
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
    args = parser.parse_args()

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
    build_crt(args.src, img_dir, written)
    build_og(hero, img_dir / "og.png", written)
    build_readme_images(args.src, hero, args.out / "screenshots", written)
    print_table(written, args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
