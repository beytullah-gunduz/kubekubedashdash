#!/usr/bin/env python3
from PIL import Image, ImageDraw, ImageFilter
import math
import os
import subprocess
import shutil

RENDER = 2048
C = RENDER // 2

img = Image.new('RGBA', (RENDER, RENDER), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)

BG_DARK = (20, 30, 48, 255)
BG_MID = (25, 42, 68, 255)
draw.rectangle([(0, 0), (RENDER, RENDER)], fill=BG_DARK)

for r in range(700, 0, -1):
    alpha = int(20 * (1 - r / 700))
    overlay_color = (40, 70, 120, alpha)
    draw.ellipse(
        [(C - r, C - r), (C + r, C + r)],
        fill=overlay_color
    )

N = 7
WHITE = (255, 255, 255, 255)
BLUE_GLOW = (61, 144, 206, 40)
start_angle = -math.pi / 2

outer_r = 620
ring_w = 72
inner_r = outer_r - ring_w
hub_r = 145
spoke_hw = 30
tip_len = 85

glow_layer = Image.new('RGBA', (RENDER, RENDER), (0, 0, 0, 0))
glow_draw = ImageDraw.Draw(glow_layer)
glow_expand = 25
glow_color = (61, 144, 206, 70)

glow_draw.ellipse(
    [(C - outer_r - glow_expand, C - outer_r - glow_expand),
     (C + outer_r + glow_expand, C + outer_r + glow_expand)],
    fill=glow_color
)
glow_draw.ellipse(
    [(C - inner_r + glow_expand, C - inner_r + glow_expand),
     (C + inner_r - glow_expand, C + inner_r - glow_expand)],
    fill=(0, 0, 0, 0)
)

for i in range(N):
    a = start_angle + 2 * math.pi * i / N
    dx, dy = math.cos(a), math.sin(a)
    px, py = -dy, dx
    w = spoke_hw + glow_expand
    pts = [
        (C + dx * (hub_r - glow_expand) + px * w, C + dy * (hub_r - glow_expand) + py * w),
        (C + dx * (hub_r - glow_expand) - px * w, C + dy * (hub_r - glow_expand) - py * w),
        (C + dx * (inner_r + glow_expand) - px * w, C + dy * (inner_r + glow_expand) - py * w),
        (C + dx * (inner_r + glow_expand) + px * w, C + dy * (inner_r + glow_expand) + py * w),
    ]
    glow_draw.polygon(pts, fill=glow_color)

glow_draw.ellipse(
    [(C - hub_r - glow_expand, C - hub_r - glow_expand),
     (C + hub_r + glow_expand, C + hub_r + glow_expand)],
    fill=glow_color
)

glow_layer = glow_layer.filter(ImageFilter.GaussianBlur(radius=30))
img = Image.alpha_composite(img, glow_layer)
draw = ImageDraw.Draw(img)

draw.ellipse([(C - outer_r, C - outer_r), (C + outer_r, C + outer_r)], fill=WHITE)
draw.ellipse([(C - inner_r, C - inner_r), (C + inner_r, C + inner_r)], fill=(0, 0, 0, 0))

bg_cutout = Image.new('RGBA', (RENDER, RENDER), (0, 0, 0, 0))
bg_cutout_draw = ImageDraw.Draw(bg_cutout)
bg_cutout_draw.ellipse(
    [(C - inner_r, C - inner_r), (C + inner_r, C + inner_r)],
    fill=BG_DARK
)
img = Image.alpha_composite(img, bg_cutout)
draw = ImageDraw.Draw(img)

for i in range(N):
    a = start_angle + 2 * math.pi * i / N
    dx, dy = math.cos(a), math.sin(a)
    px, py = -dy, dx

    gap = 6
    pts = [
        (C + dx * (hub_r + gap) + px * spoke_hw, C + dy * (hub_r + gap) + py * spoke_hw),
        (C + dx * (hub_r + gap) - px * spoke_hw, C + dy * (hub_r + gap) - py * spoke_hw),
        (C + dx * (inner_r - gap) - px * spoke_hw, C + dy * (inner_r - gap) - py * spoke_hw),
        (C + dx * (inner_r - gap) + px * spoke_hw, C + dy * (inner_r - gap) + py * spoke_hw),
    ]
    draw.polygon(pts, fill=WHITE)

    tip_base_w = spoke_hw * 1.3
    tip_pts = [
        (C + dx * (outer_r + tip_len), C + dy * (outer_r + tip_len)),
        (C + dx * (outer_r - 8) + px * tip_base_w, C + dy * (outer_r - 8) + py * tip_base_w),
        (C + dx * (outer_r - 8) - px * tip_base_w, C + dy * (outer_r - 8) - py * tip_base_w),
    ]
    draw.polygon(tip_pts, fill=WHITE)

draw.ellipse([(C - hub_r, C - hub_r), (C + hub_r, C + hub_r)], fill=WHITE)

inner_hub = 55
draw.ellipse([(C - inner_hub, C - inner_hub), (C + inner_hub, C + inner_hub)], fill=BG_DARK)

master = img.resize((1024, 1024), Image.LANCZOS)

script_dir = os.path.dirname(os.path.abspath(__file__))
master_path = os.path.join(script_dir, "icon_1024.png")
master.save(master_path)
print(f"Saved master icon: {master_path}")

linux_path = os.path.join(script_dir, "icon_512.png")
master.resize((512, 512), Image.LANCZOS).save(linux_path)
print(f"Saved Linux icon: {linux_path}")

ico_path = os.path.join(script_dir, "icon.ico")
ico_sizes = [(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
ico_images = [master.resize(s, Image.LANCZOS) for s in ico_sizes]
ico_images[0].save(ico_path, format='ICO', sizes=ico_sizes, append_images=ico_images[1:])
print(f"Saved Windows icon: {ico_path}")

iconset_dir = os.path.join(script_dir, "icon.iconset")
os.makedirs(iconset_dir, exist_ok=True)

iconset_sizes = {
    "icon_16x16.png": 16,
    "icon_16x16@2x.png": 32,
    "icon_32x32.png": 32,
    "icon_32x32@2x.png": 64,
    "icon_128x128.png": 128,
    "icon_128x128@2x.png": 256,
    "icon_256x256.png": 256,
    "icon_256x256@2x.png": 512,
    "icon_512x512.png": 512,
    "icon_512x512@2x.png": 1024,
}

for name, size in iconset_sizes.items():
    resized = master.resize((size, size), Image.LANCZOS)
    resized.save(os.path.join(iconset_dir, name))

icns_path = os.path.join(script_dir, "icon.icns")
subprocess.run(["iconutil", "-c", "icns", iconset_dir, "-o", icns_path], check=True)
print(f"Saved macOS icon: {icns_path}")

shutil.rmtree(iconset_dir)
print("Cleaned up iconset directory")
print("Done! All icons generated.")
