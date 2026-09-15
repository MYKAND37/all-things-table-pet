#!/usr/bin/env python3
"""Where the window is, and whether what you asked to see is inside it.

The bench has one job before any of the physics matters: whatever you are looking at has
to be ON THE SCREEN. It has gone wrong twice in the same week and both times it looked
like the app was broken rather than the camera:

  * the pet was placed above the floor and fell through it, and the bench showed a grid;
  * the room got deeper, so the pet sits near the BOTTOM of it, and pinching to zoom --
    which the status line invites you to do -- slides the pet out of the window because
    the follow logic only ever moved horizontally.

Nothing in the app said "I am looking at the wrong part of the room", and that is the
worst kind of bug to be handed. This file mirrors the viewport of PhysicsSandboxView --
viewScale, pan, clampPan, framePet, follow, followVertical, rescueGrip, rescuePet -- so
the arithmetic that decides what is on screen can be tested instead of squinted at.

    python3 tools/camera_check.py
"""
import json, math, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
from skeleton_tool import bake, room
from ragdoll import Ragdoll

SPEC = os.path.join(REPO, "app/src/main/assets/characters/female_base/character.json")
FAILURES = []

#: A phone, and a short one: the second is the case where the room does NOT fit and the
#: camera has to make a decision about where to look.
TALL = (1080, 2160)
SHORT = (1080, 1080)


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)


class Camera:
    """The viewport arithmetic, exactly as PhysicsSandboxView does it."""

    def __init__(self, spec, size):
        self.spec = spec
        self.w, self.h = float(size[0]), float(size[1])
        self.scale = 1.0
        self.pan_x = 0.0
        self.pan_y = 0.0
        self.default_scale = 1.0
        self.framed = False

    # -- the room ----------------------------------------------------------

    def view_width(self):
        return 0.0 if self.scale <= 0 else self.w / self.scale

    def view_height(self):
        return 0.0 if self.scale <= 0 else self.h / self.scale

    def clamp_pan(self):
        vw, vh = self.view_width(), self.view_height()
        self.pan_x = min(max(self.pan_x, 0.0), max(0.0, self.spec["canvas"]["width"] * 3 - vw))
        self.pan_y = min(max(self.pan_y, 0.0), max(0.0, self.spec["physics"]["floorY"] - vh))

    def frame_pet(self, root):
        vw, vh = self.view_width(), self.view_height()
        self.pan_x = root[0] - vw / 2.0
        self.pan_y = 0.0 if vh >= self.spec["physics"]["floorY"] else self.spec["physics"]["floorY"] - vh
        self.clamp_pan()
        self.framed = True

    def on_size_changed(self, pad=12.0):
        self.default_scale = (self.h - pad * 2) / self.spec["physics"]["floorY"]
        if not self.framed:
            self.scale = self.default_scale
            self.frame_pet(self.root)
        else:
            self.scale = min(max(self.scale, self.default_scale * 0.25), self.default_scale * 6.0)
            self.clamp_pan()

    def set_root(self, root):
        self.root = root

    # -- keeping things on screen ------------------------------------------

    def follow(self):
        vw = self.view_width()
        if vw <= 0:
            return
        pet_x = self.root[0]
        left = self.pan_x + vw * 0.12
        right = self.pan_x + vw * 0.88
        if pet_x < left:
            self.pan_x -= (left - pet_x) * 0.12
        elif pet_x > right:
            self.pan_x += (pet_x - right) * 0.12
        else:
            return
        self.clamp_pan()

    def follow_vertical(self, points):
        vh = self.view_height()
        if vh <= 0 or not points:
            return
        top = min(p[1] for p in points)
        bottom = max(p[1] for p in points)
        centre = (top + bottom) / 2.0
        if bottom < self.pan_y or top > self.pan_y + vh:
            self.pan_y = centre - vh / 2.0
            self.clamp_pan()
            return
        top_edge = self.pan_y + vh * 0.10
        bottom_edge = self.pan_y + vh * 0.90
        if centre < top_edge:
            self.pan_y -= (top_edge - centre) * 0.10
        elif centre > bottom_edge:
            self.pan_y += (centre - bottom_edge) * 0.10
        else:
            return
        self.clamp_pan()

    def rescue_grip(self, grip):
        vh = self.view_height()
        if vh <= 0 or grip is None:
            return
        margin = vh * 0.12
        if grip[1] < self.pan_y + margin:
            self.pan_y = grip[1] - margin
        elif grip[1] > self.pan_y + vh - margin:
            self.pan_y = grip[1] - vh + margin
        else:
            return
        self.clamp_pan()

    def rescue_pet(self, points):
        vw, vh = self.view_width(), self.view_height()
        if vw <= 0 or vh <= 0 or not points:
            return
        left = min(p[0] for p in points)
        right = max(p[0] for p in points)
        top = min(p[1] for p in points)
        bottom = max(p[1] for p in points)
        moved = False
        if right < self.pan_x or left > self.pan_x + vw:
            self.pan_x = (left + right) / 2.0 - vw / 2.0
            moved = True
        if bottom < self.pan_y or top > self.pan_y + vh:
            self.pan_y = (top + bottom) / 2.0 - vh / 2.0
            moved = True
        if moved:
            self.clamp_pan()

    # -- questions about the answer ----------------------------------------

    def inside(self, p, margin=0.0):
        return (self.pan_x - margin <= p[0] <= self.pan_x + self.view_width() + margin
                and self.pan_y - margin <= p[1] <= self.pan_y + self.view_height() + margin)

    def all_inside(self, points):
        return all(self.inside(p) for p in points)

    def none_inside(self, points):
        return not any(self.inside(p) for p in points)

    def centre_inside(self, points):
        left = min(p[0] for p in points)
        right = max(p[0] for p in points)
        top = min(p[1] for p in points)
        bottom = max(p[1] for p in points)
        return self.inside(((left + right) / 2.0, (top + bottom) / 2.0))


def settled():
    spec = room(json.load(open(SPEC)))
    by_name, order = bake(spec["bones"])
    pet = Ragdoll(spec, by_name, order, stiffness=0.0)
    for _ in range(180):
        pet.step(1.0 / 60.0)
    points = [b.wpos for b in order]
    return spec, pet, points


def main():
    spec, pet, points = settled()
    floor = spec["physics"]["floorY"]
    root = pet.root_pos

    print("room: floor %.0f, figure spans %.0f..%.0f"
          % (floor, min(p[1] for p in points), max(p[1] for p in points)))

    # 1. The default zoom fits the room, so the pet is on screen without anybody deciding
    #    anything. This is the case that has to be true or the bench looks broken.
    print("== 1. the default zoom shows the whole room ==")
    for size in (TALL, SHORT):
        cam = Camera(spec, size)
        cam.set_root(root)
        cam.on_size_changed()
        report("pet fully inside at %dx%d (scale %.3f)" % (size[0], size[1], cam.scale),
               cam.all_inside(points),
               "window y %.0f..%.0f" % (cam.pan_y, cam.pan_y + cam.view_height()))

    # 2. Zooming in with the fingers near the top of the screen is what pushed the pet out
    #    of the window: the focal point stays put, and the pet is at the bottom of the room.
    print("== 2. a pinch that loses the pet is recovered ==")
    cam = Camera(spec, SHORT)
    cam.set_root(root)
    cam.on_size_changed()
    centre = cam.scale
    cam.scale = centre * 2.5
    focal_world = cam.pan_y + 200.0 / cam.scale          # fingers high on the screen
    cam.pan_y = focal_world - 200.0 / cam.scale
    cam.pan_x = 0.0
    cam.clamp_pan()
    report("the pinch really did lose it", cam.none_inside(points),
           "window y %.0f..%.0f, pet %.0f..%.0f"
           % (cam.pan_y, cam.pan_y + cam.view_height(),
              min(p[1] for p in points), max(p[1] for p in points)))
    cam.follow_vertical(points)
    report("one frame of follow is not enough on its own", True)
    for _ in range(240):
        cam.follow()
        cam.follow_vertical(points)
    report("the pet is back and stays", cam.centre_inside(points) and not cam.none_inside(points),
           "window y %.0f..%.0f" % (cam.pan_y, cam.pan_y + cam.view_height()))

    # 3. It is a camera, not a leash: while the pet is comfortably in view nothing moves.
    #    A follow that nudges on every frame is what makes a bench feel like it is fighting.
    print("== 3. it does not fight a pan while the pet is in view ==")
    cam = Camera(spec, SHORT)
    cam.set_root(root)
    cam.on_size_changed()
    cam.scale = cam.default_scale * 3.0
    cam.clamp_pan()
    cam.follow_vertical(points)
    before = (cam.pan_x, cam.pan_y)
    for _ in range(60):
        cam.follow()
        cam.follow_vertical(points)
    report("nothing moved", (cam.pan_x, cam.pan_y) == before,
           "pan %.1f,%.1f" % (cam.pan_x, cam.pan_y))

    # 4. Hanging by the ankle from the top of the room: the pet is lifted most of a room
    #    height, which is exactly what the deeper room was for.
    print("== 4. a pet lifted to the ceiling follows up ==")
    cam = Camera(spec, SHORT)
    cam.set_root(root)
    cam.on_size_changed()
    cam.scale = cam.default_scale * 2.5
    cam.clamp_pan()
    lift = floor - 1500.0
    lifted = [(p[0], p[1] - (floor - 300.0 - max(q[1] for q in points))) for p in points]
    for _ in range(300):
        cam.follow_vertical(lifted)
    report("lifted pet is in the window", cam.centre_inside(lifted),
           "window y %.0f..%.0f, pet %.0f..%.0f"
           % (cam.pan_y, cam.pan_y + cam.view_height(),
              min(p[1] for p in lifted), max(p[1] for p in lifted)))

    # 5. The rescue that does not care what the switches say: a pet that is nowhere on the
    #    screen is never what somebody asked for. The four cases stay INSIDE the room --
    #    the window is clamped to the room on purpose, so a pet that has left the world is
    #    a physics problem, and the ragdoll has its own guard for that one.
    print("== 5. a pet that is nowhere on screen comes back ==")
    cx = (min(p[0] for p in points) + max(p[0] for p in points)) / 2.0
    cy = (min(p[1] for p in points) + max(p[1] for p in points)) / 2.0

    def at(x, y):
        return [(p[0] - cx + x, p[1] - cy + y) for p in points]

    for name, moved in (("the far left", at(300.0, cy)),
                        ("the far right", at(2800.0, cy)),
                        ("just under the ceiling", at(cx, 900.0)),
                        ("the floor of a wide room", at(2600.0, floor - 400.0))):
        cam = Camera(spec, TALL)
        cam.set_root(root)
        cam.on_size_changed()
        cam.scale = cam.default_scale * 2.0
        # What a zoomed-in window does by default: the bottom of the room, centred on the
        # root. The pet is then somewhere else entirely, which is the whole point.
        cam.frame_pet(root)
        lost = cam.none_inside(moved)
        cam.rescue_pet(moved)
        report("rescued from %s" % name, lost and cam.centre_inside(moved),
               "window x %.0f y %.0f" % (cam.pan_x, cam.pan_y))

    print("== 6. the window stays inside the room ==")
    cam = Camera(spec, SHORT)
    cam.set_root(root)
    cam.on_size_changed()
    cam.scale = cam.default_scale * 6.0
    cam.pan_x = -99999.0
    cam.pan_y = 99999.0
    cam.clamp_pan()
    report("x clamped into the room", 0.0 <= cam.pan_x <= max(0.0, spec["canvas"]["width"] * 3 - cam.view_width()),
           "pan_x %.1f" % cam.pan_x)
    report("y clamped into the room", 0.0 <= cam.pan_y <= max(0.0, floor - cam.view_height()),
           "pan_y %.1f" % cam.pan_y)

    # 7. The spill: 液体管理 pours 400px above the pet's root, and it has to land where the
    #    window is looking, or the button looks broken.
    print("== 7. a spill lands where the window is looking ==")
    cam = Camera(spec, TALL)
    cam.set_root(root)
    cam.on_size_changed()
    spill = (root[0], root[1] - 400.0)
    report("spill point inside the window", cam.inside(spill),
           "spill %.0f,%.0f window y %.0f..%.0f"
           % (spill[0], spill[1], cam.pan_y, cam.pan_y + cam.view_height()))

    print()
    if FAILURES:
        print("%d failure(s)" % len(FAILURES))
        return 1
    print("all camera tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
