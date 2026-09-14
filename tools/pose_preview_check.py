
"""Verification that the action-list thumbnails actually read as figures.

Mirrors dev.atp.pet.ui.PosePreview: bake the spec, set the saved angles (clamped exactly
as Bone.rotation clamps), run FK, fit the bone segments to a square by their own bounds,
and draw. If a thumbnail of a wave does not look like a wave, the app's does not either.

    python3 tools/pose_preview_check.py
"""
import json, math, sys, os
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
from skeleton_tool import bake, update, tip, clamp_rot, room
from PIL import Image, ImageDraw

SPEC = os.path.join(REPO, "app/src/main/assets/characters/female_base/character.json")
OUT = os.path.join(REPO, "docs/pose_preview_check.png")
R = math.radians

POSES = [
    ("不摆动作", {}),
    ("挥手", {"upperarm_L": R(-150), "forearm_L": R(-25), "head": R(6), "neck": R(-4)}),
    ("坐下", {"hip": R(16), "thigh_L": R(-84), "shin_L": R(84), "thigh_R": R(-84),
              "shin_R": R(84), "spine": R(-6)}),
    ("双手举高", {"upperarm_L": R(-168), "upperarm_R": R(168), "forearm_L": R(-14),
                  "forearm_R": R(14)}),
]

def figure(by_name, order, angles):
    for b in order:
        b.rotation = 0.0
    for name, a in angles.items():
        if name in by_name:
            clamp_rot(by_name[name], a)
        else:
            print("  (no bone named %s)" % name)
    update(order)
    return [(b.wpos, tip(b)) for b in order]

def thumbnail(segs, px, colour):
    xs = [p[0] for s in segs for p in s]
    ys = [p[1] for s in segs for p in s]
    minx, maxx, miny, maxy = min(xs), max(xs), min(ys), max(ys)
    spanx, spany = max(maxx - minx, 1.0), max(maxy - miny, 1.0)
    pad = px * 0.1
    scale = min((px - 2 * pad) / spanx, (px - 2 * pad) / spany)
    ox = (px - spanx * scale) / 2 - minx * scale
    oy = (px - spany * scale) / 2 - miny * scale
    img = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    w = max(2, int(px / 18))
    for (h, t) in segs:
        d.line([ox + h[0] * scale, oy + h[1] * scale,
                ox + t[0] * scale, oy + t[1] * scale], fill=colour, width=w)
    for (h, _t) in segs:
        x, y = ox + h[0] * scale, oy + h[1] * scale
        d.ellipse([x - w * 0.6, y - w * 0.6, x + w * 0.6, y + w * 0.6], fill=colour)
    return img

def main():
    spec = room(json.load(open(SPEC)))
    by_name, order = bake(spec["bones"])
    print("bones %d" % len(order))

    px, gap = 150, 14
    strip = Image.new("RGBA", (len(POSES) * px + (len(POSES) + 1) * gap, px + 2 * gap),
                      (250, 249, 252, 255))
    for i, (name, angles) in enumerate(POSES):
        segs = figure(by_name, order, angles)
        colour = (91, 75, 196, 255) if i == 0 else (110, 86, 207, 255)
        strip.alpha_composite(thumbnail(segs, px, colour), (gap + i * (px + gap), gap))
        print("  %-6s drawn" % name)

    strip.save(OUT)
    print("wrote", OUT)

if __name__ == "__main__":
    main()
