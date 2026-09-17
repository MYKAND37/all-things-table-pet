#!/usr/bin/env python3
"""
Reference implementation of the articulated-body physics.

The character is not one rigid block: every joint carries its own angle and angular
momentum, so holding it up by the hand lets the rest of it hang.

Two things carry most of the design weight:

  * Gravity torque per joint is summed over the joint's whole subtree, so a heavy lower
    body actually pulls on the spine the way it should.

  * The finger does not move the character. It applies a FORCE at the grabbed point,
    which reaches the root as a linear pull and every joint on the chain to the grabbed
    bone as a torque. Moving the root directly was the first attempt and it was wrong:
    the whole figure rigidly followed the finger, and it would happily settle upside
    down with the body balanced above the hand.

Stiffness is the dial between the two behaviours the project wants: at zero the figure
is a limp ragdoll, cranked up it holds a pose.

This file is the reference the Kotlin engine mirrors, and it carries the tests.
"""
import json
import math
import random
import sys

from skeleton_tool import bake, update, tip, norm_angle, room

# Angular spring constant at stiffness = 1.0, in 1/s^2. At this value a bone returns to
# its target in roughly a tenth of a second: holding a pose, not a stiff servo.
K_MAX = 400.0
SPRING_ZETA = 1.0

LINEAR_DAMP = 0.6          # per second, on the root
ANGULAR_DAMP = 0.9         # per second, on every joint

# How hard the grab pulls the chain towards the finger before the root starts sliding.
# More iterations reach further up the chain; six settles well inside a frame.
PIN_IK_ITERATIONS = 6

# Gauss-Seidel passes over the pins. One finger does not care; two fingers pulling in
# opposite directions need a couple of rounds before they agree.
PIN_OUTER = 3

# How much of the position error the JOINTS take, against the root taking it all.
#
# 1.0 is the least-squares solution: the cheapest rotation set that lands on the finger.
# It is also wrong on its own -- it folds whatever is cheapest (a knee) rather than
# letting the body hang, because folding a knee is cheaper than turning a body. The root
# translation is exact, so the joints do not have to do anything at all, and the shape of
# a hanging figure is gravity's business, not the finger's. A little joint give is kept
# for the cases where the hand should drag a limb along with it rather than the body.
PIN_JOINT_GAIN = 1.0

#: How fast the pin's IK may turn a joint, in radians per second. See _solve_pin_aim.
#:
#: A rate rather than a per-frame step, so that the same drag feels the same at 30, 60 and
#: 120 fps: a per-frame limit would be twice as generous on a fast phone.
MAX_IK_RATE = 9.0

#: How short a lever arm stops being a lever, in px. See _solve_pin_aim.
#:
#: Turning a joint moves the point it is holding by |r| px per radian, where r runs from the
#: joint to the grabbed point. A finger that took hold 0.5 px from the joint -- which is what
#: a grab AT a joint is, and the offset is a distance along the bone, so it happens -- cannot
#: be moved by turning that joint at all. The aim does not care, because the aim is an ANGLE:
#: it asks for |e| / |r| radians, so a 1.4 px position error asks for 70 degrees, and its SIGN
#: flips the moment that sub-pixel error changes side. The joint then spends its whole
#: per-frame allowance every frame, turning one way and back.
#:
#: Measured on the hip dragged along the floor with the pin 0.5 px along the root bone: the
#: root turned +8.13, -8.13, +8.13 degrees per frame -- the full MAX_IK_RATE * dt of 8.59 --
#: changing sign on 54 of 179 frames, and the worst bone in the body alternated by 15.5 px per
#: frame. That is the buzz, and it is not the solver being stiff: it is a joint being asked to
#: satisfy a position constraint with a lever that cannot move anything, so the only thing it
#: still does is rotate the FIGURE, which the finger is not holding.
#:
#: 6 px, because a joint spending its entire allowance moves the grabbed point by at most
#: |r| * MAX_IK_RATE * dt, which is |r| * 0.15 at 60 Hz: under a pixel of movement, twice the
#: 0.5 px at which the solver calls the pin satisfied. A length rather than a rate, so it does
#: not move with the frame rate, and short enough that it can only ever switch off the GRABBED
#: bone: every ancestor's lever is the chain out to the grab -- hundreds of px -- so the pull
#: up the chain, which is the straightening, is untouched. tools/drag_check.py pins the
#: grabbed-at-the-joint cases (offset 0) down; tools/carry_check.py pins the hanging down.
LEVER_MIN = 6.0

# "aim" = cyclic coordinate descent: every joint turns to line up with the pull, which is
#         what straightens a limp limb you dangle by its end.
# "lss" = least squares: moves the least body, which means folding whatever folds cheapest.
PIN_MODE = "aim"

# How much of its turn the ROOT takes, against every other joint in the chain.
# 0.15 is where both behaviours survive: below about 0.1 a pair of legs cannot be splayed
# against the hip's own limits, above about 0.3 the pin starts winning against gravity and
# a figure held by the ankle stops hanging. tools/drag_check.py pins both ends down.
ROOT_PIN_GAIN = 0.15

# How much of the finger's pull the ROOT takes, before the chain is solved at all.
#
# This is the difference between POSING a limb and CARRYING the figure, and it is the whole
# of "I grab its leg and lift, and the pet comes up with my hand and hangs". The chain solve
# below can always reach the finger by bending something -- grab an ankle and lift and it
# rotates the hip, which lifts the foot and leaves the body standing on the other leg,
# leaning, exactly like a bow. The body only comes up if the ROOT takes part of the pull.
#
# Not 1.0: pulling the root all the way is exact and leaves the chain with nothing to do, so
# a limb can never be posed and a hanging arm keeps whatever shape it had. Not 0.0: the
# figure never leaves the ground and cannot be picked up at all. What is left of the error
# after this is solved by the chain, and the last of it by sliding the root -- so the grip
# still lands exactly on the finger, it just gets there with the body behind it.
CARRY_GAIN = 0.85

# Floor resolution passes per step, and the largest turn one pass may apply.
GROUND_PASSES = 4
MAX_TURN_STEP = 0.35

# A whisper of noise on the joints while the figure is limp, in radians per step.
#
# An exactly symmetrical limp figure standing on straight legs is in equilibrium and
# stands there forever, which reads as a statue rather than a ragdoll. Real ones topple
# because nothing is ever perfectly balanced, and upright is an UNSTABLE equilibrium, so
# this does not need to be visible: it grows on its own until the figure falls over.
ANGULAR_NOISE = 2.0e-5

# The same whisper, on the root. Joint noise alone is not enough: a folded figure can end
# up with every joint sitting on its limit, where the noise is simply clamped away, and it
# then balances on one foot forever. The upright pose is an unstable equilibrium, so a
# nudge that is far too small to see decides which way it topples.
LINEAR_NOISE = 0.6         # px/s^2

# A deliberately unphysical nudge, and the only one in this file.
#
# A limp figure that lands on its feet stands: the foot collider is a 68px capsule, which
# is a real support polygon, and the pose is a genuine equilibrium. That is correct
# physics and it looks like a statue, which is not what a limp setting is for. So once the
# figure has stopped moving, a limp one is nudged until it topples. It only applies below
# 0.25 stiffness, only while grounded, and only when nearly still, so it never fights a
# throw or a drag.
# How strongly a resting limp joint amplifies its own deviation, in 1/s^2. A real joint
# has no static friction: whatever load it is under, it gives, and a figure whose joints
# all give folds up. Without this a limp figure that lands on its feet is a rigid statue
# balanced on a support polygon, because every joint has found its zero-torque angle and
# nothing ever disturbs it. Applied only when limp, grounded and nearly still, so it never
# fights a drag or a throw.
# Measured, not guessed: a limp figure that starts standing takes 5.2s to be visibly down at
# 10.0 and 12.8s at 6.0, and it is the difference between "it slumped" and "it is melting".
# Higher is not better -- above about 14 the settled figure keeps twitching, which reads as a
# bug rather than as a limp body. See tools/ragdoll.py test 3.
COLLAPSE_GAIN = 10.0

#: How far over a figure has to be turned before the finger, and not the floor, is what is
#: holding it up, in radians. Not a quarter turn: a figure that has been laid on its side is
#: on the floor, and the floor resolves it exactly as it always did. This is the line
#: between standing on the ground and hanging off a hand.
UPSIDE_DOWN = 2.0

#: How far back from "turned over" a figure has to come before it stops counting as hanging.
#: See Ragdoll.hanging: this is hysteresis, and it is worth 70x rather than being a nicety.
HANGING_OFF = 1.65

# How far a CARRIED figure may hang below the floor line, in px.
#
# This is the whole difference between "picked up by the ankle and hanging upside down" and
# "bent over at the waist". The figure is 1690px tall and its head is 1300px from its ankle,
# so hanging head-down needs the ankle 1300px above the floor -- and the arena is only 1980
# deep. Lifting the ankle by anything less leaves the head on the floor first, where the
# ground does what it is supposed to do and turns the spine out of the way: the figure folds
# instead of turning over, and no amount of gravity about the finger can help, because the
# head is standing on the ground.
#
# A hand that has taken the weight is holding the figure up. The floor has nothing left to
# say about it, and it says it anyway -- which is the bug. So while a finger holds the
# figure the floor stops being a wall and becomes a cushion: the figure may hang this far
# below the line, and only past that does the floor push back. It is still a floor, so a
# figure cannot be shoved through it, but it is no longer in the way of a body hanging off
# a hand.
CARRY_SINK = 460.0

# Hard ceilings on the integrator itself. Not physics: an explicit integrator that is
# fed a bad number should saturate rather than turn the figure into NaN.
MAX_SPEED = 20000.0        # px/s
MAX_OMEGA = 40.0           # rad/s


class Ragdoll:
    def __init__(self, spec, by_name, order, stiffness=0.0):
        self.spec = spec
        self.by_name = by_name
        self.order = order
        #: How much of MAX_IK_RATE each joint has already spent THIS frame. See _apply_pins:
        #: the budget is per frame, not per iteration.
        self.ik_spent = {}
        physics = spec.get("physics", {})
        self.gravity = float(physics.get("gravity", 2400.0))
        self.gravity_scale = 1.0
        self.floor = float(physics.get("floorY", 2048.0))
        self.ceiling = 0.0
        self.wall_left = 0.0
        # The room's width, which is the APP's room and not the artwork's: CharacterSpec
        # reads physics.worldWidth, and skeleton_tool.room() is where the reference's room is
        # described -- it sets this key, so every test that goes through room() is walled
        # where the phone walls the pet.
        #
        # The fallback repeats the APP's default (CharacterSpec.kt: canvasW * 3) instead of
        # having a second opinion of its own, and that is the point: this line used to be
        # plain canvas.width with no key and no default, so the reference had a wall at 1024
        # where the app has none, and a fortnight of horizontal-drag readings were taken
        # against it. A fallback that silently picks a different number is how it went
        # unnoticed; one that picks the app's number can only be wrong for a spec the app
        # would also get wrong.
        self.wall_right = float(physics.get("worldWidth",
                                           float(spec["canvas"]["width"]) * 3.0))
        self.restitution = 0.2
        self.ground_friction = 2.5

        # Colliders. A bone with no explicit one gets a capsule sized off the head
        # height, so a package that says nothing still behaves sensibly.
        default_r = float(spec["headHeight"]) * 0.18
        self.collider = {}
        #: The per-part switch: a bone the world cannot feel. See BoneSpec.collides.
        self.solid = {}
        for b in spec["bones"]:
            c = b.get("collider") or {}
            self.collider[b["name"]] = (
                c.get("type", "capsule"),
                float(c.get("radius", default_r)),
            )
            #: Does the world feel this part? See BoneSpec.collides. Absent means yes, so a
            #: rig written before the switch existed behaves exactly as it always did.
            self.solid[b["name"]] = b.get("collides", True)

        # Mass from collider area: fat limbs are heavy, which is what makes a
        # belly-down character settle the way you expect.
        self.mass = {}
        for b in order:
            kind, r = self.collider[b.name]
            if kind == "circle":
                area = math.pi * r * r
            else:
                area = b.length * 2.0 * r + math.pi * r * r
            self.mass[b.name] = max(area, 1.0)
        self.total_mass = sum(self.mass.values())

        self.stiffness = dict((b.name, float(stiffness)) for b in order)
        self.figure_stiffness = float(stiffness)
        self.rng = random.Random(20260913)

        # The skeleton model only stores parent links, so build the child links and the
        # subtree lists here. Neither changes, so this happens once.
        self.children = dict((b.name, []) for b in order)
        for b in order:
            if b.parent is not None:
                self.children[b.parent.name].append(b)
        self.subtrees = {}
        self.subtree_names = {}
        for b in order:
            out = []

            def walk(x, out=out):
                out.append(x)
                for c in self.children[x.name]:
                    walk(c)

            walk(b)
            self.subtrees[b.name] = out
            self.subtree_names[b.name] = set(x.name for x in out)

        update(order)
        # The room is deeper than the artwork's ground line, and the figure stands on the
        # FLOOR, not where the drawing's ground line happens to be: see skeleton_tool.room.
        # The rig is authored in the ART canvas' coordinates and the room is deeper than the
        # art canvas, so the figure is stood on the real floor by offsetting it: root_home is
        # the authored root (art coordinates) and STAND is how far the floor is below it.
        #
        # Getting this wrong is invisible in the numbers and obvious on the screen: the figure
        # is placed a body's height above the floor, falls, and -- because a fast landing is
        # resolved by turning limbs out of the floor rather than by lifting the root -- sinks
        # through it and is never seen again.
        self.rig_root = order[0].wpos
        self.stand = float(physics.get("roomAir", 0.0))
        self.root_home = (self.rig_root[0], self.rig_root[1] + self.stand)
        self.root_pos = list(self.root_home)
        # The root carries an explicit velocity. A Verlet integrator reads any positional
        # correction as velocity, and one large correction then squares its way to
        # infinity in under a second.
        self.root_vel = [0.0, 0.0]

        self.ang = dict((b.name, 0.0) for b in order)
        self.ang_prev = dict((b.name, 0.0) for b in order)
        # Put the figure into world coordinates before anything measures it: collider_low,
        # the centre of mass and the standing span are all read straight after this, and in
        # art coordinates every one of them is a body's height out.
        self.fk()
        # How tall the figure is standing. Used to tell "still up" from "already down",
        # which is what stops the limp give-way feedback once the figure has collapsed.
        self.standing_span = (max(self.collider_low(b) for b in self.order)
                              - min(b.wpos[1] for b in self.order))
        self.target = dict((b.name, 0.0) for b in order)
        self.grounded = False
        self.pin_point = (0.0, 0.0)
        self.pin_chain = []
        self.pin_last = None
        # Subtree inertia about each joint, filled in every step. The pin solver needs it
        # to know what each joint would have to swing.
        self.inertia = dict((b.name, 1.0) for b in order)
        self.pin_last_target = None
        self.pinned = False
        #: Whether the figure is being treated as hanging right now. Stateful on purpose:
        #: this is the hysteresis in hanging(), and a plain threshold here is a vibration.
        self._hanging = False
        self.pin_targets = []
        # Velocity of the finger, handed to the caller so a release can throw.
        self.pin_vel = [0.0, 0.0]

    def release(self):
        """Let go: keep whatever speed the drag had."""
        self.root_vel[0] = self.pin_vel[0]
        self.root_vel[1] = self.pin_vel[1]
        self.pin_last = None
        self.pin_vel = [0.0, 0.0]

    # -- helpers ------------------------------------------------------------

    def _offset(self):
        """
        Where the root is now, as a displacement of the ART-space rig.

        Measured from rig_root, not from root_home. root_home is where the figure STANDS (the
        rig's origin plus the air) and the bones are authored around rig_root, so subtracting
        root_home would cancel the air out and put the pet in the drawing's coordinates -- a
        body's height above the floor it is supposed to stand on, which is a bug that shipped
        once already.
        """
        return (self.root_pos[0] - self.rig_root[0],
                self.root_pos[1] - self.rig_root[1])

    def fk(self):
        for b in self.order:
            b.rotation = self.ang[b.name]
        update(self.order, self._offset())

    def subtree(self, bone):
        return self.subtrees[bone.name]

    def com(self, bone):
        h = bone.wpos
        t = tip(bone)
        return ((h[0] + t[0]) / 2.0, (h[1] + t[1]) / 2.0)

    def collider_low(self, bone):
        kind, r = self.collider[bone.name]
        if kind == "circle":
            return self.com(bone)[1] + r
        return max(bone.wpos[1], tip(bone)[1]) + r

    def limits_of(self, bone):
        """
        What this joint is allowed to do, which is not always what the file says.

        The root bone's rotation IS the figure's global orientation. Its authored limits
        are a stand-in for the one thing that actually has an opinion about that -- the
        ground -- so they apply whenever the figure is on its own. While a finger is
        holding it, the authored limit is what stopped a figure picked up by the ankle
        from hanging: the solver could rotate every joint EXCEPT the one that would have
        turned the body over, so it folded the legs instead and left the torso sticking
        out sideways.

        Deliberately not "while airborne": a figure in free fall keeps its authored limits
        so that it lands the way it always did. Losing that made every landing sprawl.
        """
        if bone.parent is None and self.pinned:
            return (-math.pi, math.pi)
        return (bone.min_a, bone.max_a)

    def _hanging_set(self, bone, gripped):
        """
        What a joint is carrying: the bones whose weight pulls on it.

        This is the subtree -- the marionette model, every joint feels what dangles from it
        -- UNLESS the finger is holding something inside that subtree. Then the pull is on
        the far side: the joint is not holding that body up, that body is holding THE JOINT
        up, and the weight on it is everything ELSE.

        Ignoring this is why picking the figure up by the ankle bent it like a bow instead of
        turning it over. The hip's subtree is the legs, so the hip felt the weight of a pair
        of legs and nothing else -- and the torso, which is a separate branch off the same
        root, was never felt anywhere except at its own neck. So the hip had no reason to
        turn the body over, the torso had no reason to hang, and the whole figure folded at
        the waist, which is exactly what it looked like: bowing.

        Nothing changes while no finger is holding anything: the subtree is the answer, and
        a figure falling on its own behaves exactly as it always did.
        """
        if gripped is None:
            return self.subtree(bone)
        sub = self.subtree(bone)
        if gripped.name not in self.subtree_names[bone.name]:
            return sub
        skip = self.subtree_names[bone.name]
        return [b for b in self.order if b.name not in skip]

    def chain_to_root(self, bone):
        """The bone plus every ancestor, which is what the pin's force acts through."""
        out = []
        b = bone
        while b is not None:
            out.append(b)
            b = b.parent
        return out

    # -- the step -----------------------------------------------------------

    def step(self, dt, pin=None):
        """pin: None, a single (bone_name, (x, y)), or a list of them.

        One finger or five; the solver is the same, and it runs them against each other
        so two hands pulling in opposite directions both get their say.
        """
        held = self._normalise(pin)
        self.pinned = bool(held)
        self.pin_targets = [p[1] for p in held]
        self.pin_offsets = [p[2] for p in held]
        self.fk()

        self.pin_chain = []
        # The app's gravity, as a multiple of the character's own: see Ragdoll.gravityScale
        # in Kotlin. Live, because the settings screen is where it is turned.
        g = self.gravity * self.gravity_scale

        # Gravity torque about each bone's head, over the weight the joint is actually
        # carrying -- which is not always its own subtree. See _hanging_set.
        one_pin = len(self.pin_targets) == 1
        gripped = self.by_name.get(held[0][0]) if (one_pin and held) else None
        alpha = {}
        for b in self.order:
            hx, hy = b.wpos
            tau = 0.0
            inertia = 0.0
            for d in self._hanging_set(b, gripped):
                cx, cy = self.com(d)
                m = self.mass[d.name]
                tau += m * g * (cx - hx)
                dx, dy = cx - hx, cy - hy
                inertia += m * (dx * dx + dy * dy)
            alpha[b.name] = tau / max(inertia, 1e-6)
            # Kept as well: the pin solver distributes the correction over the chain by
            # how much body each joint would have to move, and this is that quantity.
            self.inertia[b.name] = max(inertia, 1e-6)
        self.alpha = alpha

        if len(self.pin_targets) == 1:
            # A body held at ONE point is a pendulum about that point, and that is the only
            # thing left with an opinion about which way up the figure is: its feet are off
            # the ground and the pin does not care about orientation at all. Without this,
            # gravity's torque about the PELVIS is the only term there is, and a figure
            # hanging from an ankle settles with its weight below its pelvis -- folded up
            # next to the hand -- instead of below the hand.
            #
            # Two pins are not a pendulum, they are a hanger: the body is held at both ends
            # and there is nothing left to swing about, so the torque is not applied.
            px, py = self.pin_targets[0]
            tau = 0.0
            inertia = 0.0
            for b in self.order:
                cx, cy = self.com(b)
                m = self.mass[b.name]
                tau += m * g * (cx - px)
                dx, dy = cx - px, cy - py
                inertia += m * (dx * dx + dy * dy)
            self.alpha[self.order[0].name] = tau / max(inertia, 1e-6)

        # Joints.
        noise = ANGULAR_NOISE * (1.0 - self.figure_stiffness)
        if noise > 0.0:
            for b in self.order:
                self.ang[b.name] += self.rng.uniform(-1.0, 1.0) * noise

        # A limp figure that has come to rest gives way instead of standing there. It
        # stops giving once it is already down, or it would never actually come to rest.
        slow = abs(self.root_vel[0]) < 30.0 and abs(self.root_vel[1]) < 30.0
        span = (max(self.collider_low(b) for b in self.order)
                - min(b.wpos[1] for b in self.order))
        # Deliberately not while a finger is holding the figure AT ALL: "a limp body that has
        # come to rest standing up should fall over" is a statement about a body standing on
        # its own. One that is being carried is not standing, and this feedback is strong
        # enough that firing it mid-hang throws the figure out of the pose entirely.
        #
        # This is `!pinned`, which is what Ragdoll.kt has always said. The reference had
        # drifted to `not hanging()` -- a DIFFERENT condition, and a weaker one: it let the
        # feedback run while a finger held a figure that was still on its feet. Every
        # measurement taken through this file was therefore taken on a system with one more
        # strong feedback term than the app has. mirror_check cannot see this: it compares
        # constants, and a condition is not a constant.
        giving = (self.figure_stiffness < 0.25 and self.grounded and slow
                  and not self.pinned
                  and span > 0.55 * self.standing_span)
        give = COLLAPSE_GAIN * (1.0 - self.figure_stiffness) if giving else 0.0

        for b in self.order:
            name = b.name
            theta = self.ang[name]
            prev = self.ang_prev[name]
            omega = theta - prev
            # MAX_OMEGA is rad/s; omega here is a per-step difference.
            cap = MAX_OMEGA * dt
            if omega > cap:
                omega = cap
            elif omega < -cap:
                omega = -cap
            a = alpha[name]
            if giving and b.parent is not None:
                # Positive feedback on the joint's own deviation. A joint with no static
                # friction cannot hold an angle, so whatever it has already given, it
                # gives more of. Without this a limp figure that lands on its feet is a
                # rigid statue balanced on a support polygon: every joint has found its
                # zero-torque angle and nothing ever disturbs it.
                #
                # Never on the root. Its angle is not a joint's deviation, it is the
                # figure's orientation in the world, and a term proportional to THAT is a
                # torque of several rad/s^2 in whatever direction the body happens to be
                # lying -- which is a kick, not a give.
                a += theta * give
            k = self.stiffness[name] * K_MAX
            if k > 0.0:
                # theta - ang_prev is a per-step difference; the spring needs rad/s.
                c = 2.0 * math.sqrt(k) * SPRING_ZETA
                a += -k * (theta - self.target[name]) - c * (omega / dt)
            new_theta = theta + omega * (1.0 - ANGULAR_DAMP * dt) + a * dt * dt
            lo, hi = self.limits_of(b)
            if new_theta < lo:
                new_theta = lo
            elif new_theta > hi:
                new_theta = hi
            # Keep the angle we came FROM. Verlet reads the difference between the two
            # stored angles as the velocity, so writing anything else here (for instance
            # the old previous value, which reads like the same thing) freezes the
            # velocity at whatever it was and the integrator diverges.
            self.ang_prev[name] = theta
            self.ang[name] = new_theta

        if noise > 0.0:
            self.root_vel[0] += self.rng.uniform(-1.0, 1.0) * LINEAR_NOISE * dt
        self.root_vel[1] += g * dt
        damp = 1.0 - LINEAR_DAMP * dt
        self.root_vel[0] *= damp
        self.root_vel[1] *= damp

        speed = math.hypot(self.root_vel[0], self.root_vel[1])
        if speed > MAX_SPEED:
            scale = MAX_SPEED / speed
            self.root_vel[0] *= scale
            self.root_vel[1] *= scale

        self.root_pos[0] += self.root_vel[0] * dt
        self.root_pos[1] += self.root_vel[1] * dt

        self.fk()
        self._ground(dt)
        # Refresh before pinning: the ground may have moved the root, and the pin needs
        # the grabbed point's CURRENT position or the correction it applies is off by
        # exactly whatever the ground just did.
        self.fk()

        held = self._normalise(pin)
        if held:
            self._pins(dt, held)
            self.carry_floor(dt)
            # Running _ground(dt) again HERE -- giving the floor the last word of the frame --
            # was tried while chasing a vibration, and reverted. It changed one case's
            # alternation ratio from 1.97 to 0.87 and cost the two-finger split 60 px of
            # reach, and it was not the cure: the vibration was a threshold in hanging(), and
            # a second ground pass cannot fix a switch. See hanging().

        self.fk()

    def _normalise(self, pins):
        """
        One finger, five fingers, or nothing at all.

        A pin is (bone, target) or (bone, target, offset), where offset is how far along
        the bone the finger landed -- 0 is the joint, the bone's length is the tip.
        """
        if pins is None:
            return []
        if isinstance(pins, tuple) and pins and isinstance(pins[0], str):
            pins = [pins]
        out = []
        for p in pins:
            out.append((p[0], p[1], float(p[2]) if len(p) > 2 else 0.0))
        return out

    def _grip(self, bone, offset):
        """
        The point on a bone a finger is actually holding.

        This is not a detail. A pin used to hold the bone's HEAD -- its joint -- whatever
        the finger had touched, and the head of the thigh IS THE HIP. So grabbing a leg
        anywhere along its length pinned the top of the body, lifting the figure upright by
        the pelvis, and no amount of gravity could ever turn it over: there was nothing
        above the grip to hang. Taking hold of the point under the finger is the whole
        difference between lifting a ragdoll by its leg and picking it up by the waist.
        """
        if offset == 0.0:
            return bone.wpos
        a = bone.wrot
        return (bone.wpos[0] + math.cos(a) * offset, bone.wpos[1] + math.sin(a) * offset)

    def _pins(self, dt, pins):
        """
        Hold every grabbed joint under its finger.

        Three attempts got here, and each one is worth knowing about.

        A pure FORCE at the grabbed point is what a mouse joint normally is, and it was
        tried first. It does not converge on a limp articulated body: the equilibrium is
        degenerate, because the force depends only on how far the hand is from the finger,
        and "one spring-length short" is satisfied wherever the rest of the body happens
        to be. The figure drifted into the ceiling and stayed there, and every gain, every
        stiffness and every cap produced the byte-identical wrong pose.

        Translating the root is exact and stable, but it is too clever by half: the chain
        never has to move at all, because dragging the root drags the hand with it. Grab
        the hand of a limp figure that way and the arm stays hanging exactly where it was,
        so the body ends up balanced ABOVE the finger instead of dangling below it.

        Plain cyclic coordinate descent then solved the chain, joint by joint, from the
        grabbed bone up to the root. It reaches the finger, but it has no idea how heavy
        anything is: the hip is a joint like any other, and rotating the hip swings the
        WHOLE body. So grabbing an ankle and lifting made the solver spin the entire
        figure around rather than let it hang -- it chose the most expensive joint in the
        rig for the job, and then fought gravity for it every frame.

        What fixes it is distributing the correction by inertia, which is the least
        squares solution rather than CCD's greedy one:

            the rotation set that moves the fewest pixels of body

        for every joint, theta_i = lambda * (e . perp(r_i)) / I_i, with lambda chosen so
        the sum lands exactly on the target. The hip's I is the whole figure, so it barely
        moves; the ankle's is a foot, so it does the work. Rotating a single bone reduces
        to exactly the old CCD answer, so nothing that worked stopped working -- and the
        heavy joints are now left to gravity, which is what actually swings a body you
        pick up by the leg.
        """
        if CARRY_GAIN > 0.0:
            dx = dy = 0.0
            for name, target, offset in pins:
                bone = self.by_name.get(name)
                if bone is None:
                    continue
                p = self._grip(bone, offset)
                dx += target[0] - p[0]
                dy += target[1] - p[1]
            dx /= len(pins)
            dy /= len(pins)
            self.root_pos[0] += dx * CARRY_GAIN
            self.root_pos[1] += dy * CARRY_GAIN
            self.fk()

        # One rate budget per joint per FRAME, shared by every iteration and every outer round.
        #
        # This reset is the whole point: the cap inside _solve_pin_aim used to sit in the
        # iteration loop, which quietly multiplied it by PIN_OUTER * PIN_IK_ITERATIONS = 18.
        # A joint could therefore turn 18x the intended rate in a single frame -- measured
        # 46-77 deg/frame against a nominal 8.6. A proximal joint (hip, chest) reaches the
        # finger and breaks out of the loop early, so it never spent the whole allowance; the
        # extremities are exactly the ones that keep iterating, which is why the buzz showed
        # up on the hands and feet and nowhere else.
        self.ik_spent = {}
        for _ in range(PIN_OUTER):
            for name, target, offset in pins:
                bone = self.by_name.get(name)
                if bone is not None:
                    self._solve_pin(bone, target, offset, dt)

        # Whatever the joints could not reach, the root carries -- split between the pins,
        # so two hands pulling opposite ways do not fight over one translation.
        dx = dy = 0.0
        for name, target, offset in pins:
            p = self._grip(self.by_name[name], offset)
            dx += target[0] - p[0]
            dy += target[1] - p[1]
        dx /= len(pins)
        dy /= len(pins)
        self.root_pos[0] += dx
        self.root_pos[1] += dy
        self.pin_point = self._grip(self.by_name[pins[0][0]], pins[0][2])

        # The finger's own speed, not the leftover: the leftover is nearly zero once the
        # chain reaches, so measuring the throw by it meant a flick threw nothing.
        target = pins[0][1]
        if self.pin_last_target is not None and dt > 1e-6:
            a = 0.35
            self.pin_vel[0] += ((target[0] - self.pin_last_target[0]) / dt - self.pin_vel[0]) * a
            self.pin_vel[1] += ((target[1] - self.pin_last_target[1]) / dt - self.pin_vel[1]) * a
        self.pin_last_target = target
        self.pin_last = self.pin_point

    def _pin_chain(self, bone):
        """
        The grabbed bone and every ancestor, the root included.

        The root is in the chain because two fingers pulling a pair of legs apart need it:
        the hip's own limits will not splay a leg far enough on their own. It is held on a
        short leash by ROOT_PIN_GAIN, because the root's rotation is the figure's whole
        orientation and gravity has the stronger claim on that.
        """
        return self.chain_to_root(bone)

    def _solve_pin_aim(self, bone, target, offset, dt):
        """
        The greedy solve: every joint in the chain turns to line its tip up with the finger.

        This is cyclic coordinate descent, and for a chain held by its end it is the one
        that STRAIGHTENS -- each link lines up with the pull, which is what a limp limb
        does when you dangle it. The least-squares solve minimises movement instead, and
        minimising movement means folding whatever folds cheapest, which is a knee.
        """
        chain = self._pin_chain(bone)
        for _ in range(PIN_IK_ITERATIONS):
            end = self._grip(bone, offset)
            if math.hypot(target[0] - end[0], target[1] - end[1]) < 0.5:
                break
            for b in chain:
                end = self._grip(bone, offset)
                px, py = b.wpos
                # Not "a zero-length r": a lever this short cannot move the grabbed point by
                # even a pixel within this frame's allowance, so the turn it is about to be
                # given is an orientation change wearing a position correction's clothes.
                # See LEVER_MIN for the measurements.
                if math.hypot(end[0] - px, end[1] - py) < LEVER_MIN:
                    continue
                current = math.atan2(end[1] - py, end[0] - px)
                wanted = math.atan2(target[1] - py, target[0] - px)
                turn = norm_angle(wanted - current) * PIN_JOINT_GAIN
                # A limit on how far one FRAME may swing a joint toward the finger.
                #
                # The aim is exact -- the joint is pointed straight at the target -- and an
                # exact aim applied every frame to a DYNAMIC body is a fight: measured, the
                # held hand jumped 55 degrees in one frame, then 39 the next, and then rode
                # its joint limits back and forth, which is the buzz the user reports as
                # 振幅不大、频率大. A limb pulls toward what it is holding at a finite rate;
                # bounding the step lets the dynamics keep their say and turns the fight back
                # into a pull.
                #
                # The budget is whatever is LEFT of this frame's allowance, so the iterations
                # and the outer rounds share one cap instead of each getting their own. What
                # is spent is the rotation actually APPLIED, not the one asked for: a joint
                # pinned against its limit has not moved and must not be charged for it.
                cap = MAX_IK_RATE * dt - self.ik_spent.get(b.name, 0.0)
                if cap <= 0.0:
                    continue
                turn = max(-cap, min(cap, turn))
                if b.parent is None:
                    # The root owns the figure's whole orientation, and gravity has the
                    # stronger claim on it. A little give lets two fingers splay a pair of
                    # legs that the hip's own limits would not allow on their own; a lot of
                    # give is the pin and gravity undoing each other every frame, which is
                    # what stopped a figure held by the ankle from hanging.
                    turn *= ROOT_PIN_GAIN
                if abs(turn) < 1e-9:
                    continue
                turned = b.rotation + turn
                lo, hi = self.limits_of(b)
                if turned < lo:
                    turned = lo
                elif turned > hi:
                    turned = hi
                if turned != b.rotation:
                    # The turn just made IS this joint's velocity for the next frame.
                    #
                    # Verlet reads ang - ang_prev as the joint's velocity, so what these two
                    # writes leave behind is what the integrator does next. Writing ang and
                    # leaving ang_prev alone hands the correction over as EXTRA velocity, on
                    # top of whatever the joint already had: it overshoots the finger, the pin
                    # turns it back, the velocity flips, and the two alternate for ever. That
                    # is the buzz, and it is why the integrator and the IK are the only two
                    # phases that run hot -- each one is the other's input.
                    #
                    # Absorbing the turn instead (ang_prev += got, which is what _turn_out
                    # does for the floor) also fixes the integrator, but it stops the body
                    # coming with the hand: tools/drag_check.py's floor-hold and two-finger
                    # cases and tools/carry_check.py all go red with it. This is the velocity
                    # pass of PBD -- the joint goes on moving the way the finger asked it to --
                    # and it leaves every check green.
                    #
                    # Measured on the dragged bone, sign changes per frame / mean step, hip
                    # dragged along the floor at 60 Hz: integrator 52/179 and 7.7 deg with
                    # ang_prev left alone, 0/179 and 1.5 deg absorbed, 53/179 and 0.07 deg
                    # like this. What the user sees moves with the integrator: holding a
                    # lifted pet still, the furthest bone in the body went from 44 px per
                    # frame to 18.
                    got = turned - b.rotation
                    self.ik_spent[b.name] = self.ik_spent.get(b.name, 0.0) + abs(got)
                    b.rotation = turned
                    self.ang[b.name] = turned
                    self.ang_prev[b.name] = turned - got
                    self.fk()

    def _solve_pin(self, bone, target, offset=0.0, dt=1.0 / 60.0):  # noqa: D401
        """Pull one point of the figure to the finger, in the cheapest way available."""
        if PIN_MODE == "aim":
            self._solve_pin_aim(bone, target, offset, dt)
            return
        chain = self._pin_chain(bone)
        for _ in range(PIN_IK_ITERATIONS):
            end = self._grip(bone, offset)
            ex = target[0] - end[0]
            ey = target[1] - end[1]
            if math.hypot(ex, ey) < 0.5:
                break

            terms = []
            total = 0.0
            for b in chain:
                rx = end[0] - b.wpos[0]
                ry = end[1] - b.wpos[1]
                # Rotating b by theta moves the pinned point by theta * perp(r).
                px, py = -ry, rx
                dot = ex * px + ey * py
                inv = 1.0 / self.inertia[b.name]
                terms.append((b, dot * inv))
                total += dot * dot * inv
            if total < 1e-9:
                break

            lam = (ex * ex + ey * ey) / total * PIN_JOINT_GAIN
            moved = False
            for b, term in terms:
                # The same per-FRAME budget _solve_pin_aim uses, and for the same reason: a cap
                # written inside the iteration loop is a cap multiplied by the iteration count.
                # Measured, this branch never came close to it -- the least-squares solve hands
                # the error to the root, so a joint here turns at most 0.17 deg per frame over a
                # 600 px/s drag, two orders of magnitude under the 8.59 this allows. So sharing
                # the budget is not a behaviour change today; it is here so the mistake is not
                # left sitting in the file for whoever next decides this mode feels better.
                cap = MAX_IK_RATE * dt - self.ik_spent.get(b.name, 0.0)
                if cap <= 0.0:
                    continue
                turn = lam * term
                turn = max(-cap, min(cap, turn))
                if abs(turn) < 1e-9:
                    continue
                turned = b.rotation + turn
                lo, hi = self.limits_of(b)
                if turned < lo:
                    turned = lo
                elif turned > hi:
                    turned = hi
                if turned != b.rotation:
                    # The same velocity pass as _solve_pin_aim, for the same reason: what the
                    # pin turns this joint by is the velocity it carries into the next frame.
                    got = turned - b.rotation
                    self.ik_spent[b.name] = self.ik_spent.get(b.name, 0.0) + abs(got)
                    b.rotation = turned
                    self.ang[b.name] = turned
                    self.ang_prev[b.name] = turned - got
                    moved = True
            if not moved:
                break
            self.fk()

    def _ground(self, dt):
        """
        Resolve the floor per bone, not just for the whole figure.

        Lifting only the root makes the body perch on whichever part happened to touch
        first and keeps its shape from there on, which is what "limp still feels stiff"
        actually was: the limbs never get to rest ON the floor, they never sprawl, and
        every other part stays exactly as rigid as when it landed.

        So the deepest part is turned out of the floor first, about its own joint, and
        only a part that cannot be helped by turning -- a bone hanging straight down, or
        one already at its joint limit -- hands its share to the root.
        """
        if self.hanging():
            # Turned over and held by one finger: the finger has the weight, and the floor
            # has nothing to say about where the head is. See carry_floor, which runs after
            # the pins -- the pins are what moves the figure, and a floor resolved before
            # them is a floor the hand can push straight through.
            self._walls()
            return

        settled = False
        # One chance per bone per frame. Turning the SAME bone four times is what made the
        # feet buzz: with the contact only ~68 px from the joint, the step that would clear
        # the penetration is larger than MAX_TURN_STEP, so the pass rotates its full 20 deg
        # and carries the contact past the joint -- dx changes sign, the next pass computes
        # the same 20 deg the other way, and the two undo each other four times inside one
        # frame. The net motion is nothing; the flicker is at four times the frame rate,
        # which is the 振幅不大、频率大 the user reports, and it lands on whichever part is
        # touching the floor -- the feet.
        turned = set()
        for _ in range(GROUND_PASSES):
            deepest, target = 0.0, None
            for b in self.order:
                # A part somebody switched out of the world hangs straight through the line,
                # and the floor must not lift the whole figure because of it.
                if not self.solid.get(b.name, True):
                    continue
                if b.name in turned:
                    continue
                pen = self.collider_low(b) - self.floor
                if pen > deepest:
                    deepest, target = pen, b
            if target is None or deepest < 0.05:
                settled = True
                break
            if self._turn_out(target, deepest):
                turned.add(target.name)
            else:
                self._lift(deepest, dt)

        if not settled:
            # Ran out of passes with something still under the floor: the root carries it.
            worst = max((self.collider_low(b) - self.floor)
                        for b in self.order if self.solid.get(b.name, True))
            if worst > 0.05:
                self._lift(worst, dt)

        # A last resort, for a figure that is not under the floor but PAST it: if every bone
        # is below the ground line, something upstream put it there, and a pet that is simply
        # not on the screen any more is the worst possible way to find out. Putting it back
        # costs one comparison per bone and turns a lost pet into a pet that lands.
        if min(b.wpos[1] for b in self.order) > self.floor:
            self.root_pos[1] = self.floor - self.standing_span
            self.root_vel = [0.0, 0.0]
            self.fk()

        self._walls()

    def _turn_out(self, bone, pen):
        """Rotate a bone about its own joint until the part of it in the floor comes out.

        Returns False when turning cannot help, and the root has to carry the figure instead.
        That is now also the answer for a rotation that makes things WORSE: the linear estimate
        pen/dx stops meaning anything once the step is big enough to swing the contact past the
        joint, and applying it anyway is how the pass ended up undoing itself every frame.
        """
        kind, _ = self.collider[bone.name]
        h = bone.wpos
        if kind == "circle":
            contact = self.com(bone)
        else:
            t = tip(bone)
            contact = t if t[1] >= h[1] else h
        dx = contact[0] - h[0]
        # Rotating about the head raises the contact at dx per radian, so a contact directly
        # under the joint cannot be helped by turning at all and the root has to do it.
        if abs(dx) < 1.0:
            return False
        step = max(-MAX_TURN_STEP, min(MAX_TURN_STEP, pen / dx))
        before = bone.rotation
        after = before + step
        if after < bone.min_a:
            after = bone.min_a
        elif after > bone.max_a:
            after = bone.max_a
        if after == before:
            return False
        bone.rotation = after
        self.ang[bone.name] = after
        self.fk()
        # Did that actually lift the part out, or did it swing the contact over the top and
        # push it deeper? A correction that does not help is a correction that will be undone
        # by the next pass, so it is not applied at all.
        if self.collider_low(bone) - self.floor > pen - 0.05:
            bone.rotation = before
            self.ang[bone.name] = before
            self.fk()
            return False
        # Absorb it into the integrator history: a contact that is already resting must not
        # feed the correction back in as velocity and bounce.
        self.ang_prev[bone.name] += after - before
        self.fk()
        return True

    def _lift(self, amount, dt):
        self.root_pos[1] -= amount
        if self.root_vel[1] > 0.0:
            self.root_vel[1] = -self.root_vel[1] * self.restitution
        self.root_vel[0] *= (1.0 - self.ground_friction * dt)
        self.fk()

    def hanging(self):
        """
        Is this figure hanging off the finger rather than resting on the ground?

        It matters because the floor resolves the two completely differently, and getting it
        wrong is visible in both directions: a hanging figure whose head the floor insists on
        lifting turns over into a bow, and a figure that is merely being dragged along the
        ground -- limp enough that it flops over while you pull it -- must still land on the
        floor rather than sink through it.

        Orientation is the whole test, and it is the honest one. A figure turned past this
        far over is not standing on anything: everything under the finger is below the finger.
        """
        if not self.pinned:
            self._hanging = False
            return False

        # Hysteresis, and it is worth 70x rather than being a nicety.
        #
        # The floor treats a carried figure in one of two ways. Hanging, the figure may sink
        # CARRY_SINK below the line and the per-bone resolution stands down. Not hanging,
        # every part of it is turned out of the ground and the whole figure is lifted. Those
        # two answers differ by HUNDREDS of pixels -- and they MOVE the figure, and moving
        # the figure is what changes the root angle. So a figure that sits on the threshold
        # flips between the two models every single frame, and every flip throws it: measured,
        # with a finger holding the hip just under the floor line, the root alternated 310 px
        # every frame, and 252 px holding it on the line. With this, 4.5 px and 5.4 px.
        #
        # So it takes more to START hanging than to STOP: past UPSIDE_DOWN going over, and
        # back under HANGING_OFF coming back. In between, it keeps whichever it was.
        root = self.order[0].name
        turned = abs(norm_angle(self.ang[root]))
        if self._hanging:
            if turned < HANGING_OFF:
                self._hanging = False
        elif turned > UPSIDE_DOWN:
            self._hanging = True
        return self._hanging

    def carry_floor(self, dt):
        """
        The floor, for a figure somebody is holding up.

        Not a wall and not a cushion: the figure may hang CARRY_SINK below the line, and past
        that the floor is exactly as hard as it ever was. Deliberately NOT the per-bone
        resolution a standing figure gets -- that one turns the deepest bone out of the floor
        about its own joint, and on a figure hanging head-down the deepest bone is the neck.
        Lifting the neck is the bow. The shape of a carried body is gravity's business.
        """
        if not self.hanging():
            return
        deepest = 0.0
        for b in self.order:
            if not self.solid.get(b.name, True):
                continue
            deepest = max(deepest, self.collider_low(b) - self.floor)
        if deepest > CARRY_SINK:
            self._lift(deepest - CARRY_SINK, dt)

    def _walls(self):
        r = self.collider[self.order[0].name][1]
        if self.root_pos[0] - r < self.wall_left:
            self.root_pos[0] = self.wall_left + r
            self.root_vel[0] = 0.0
        elif self.root_pos[0] + r > self.wall_right:
            self.root_pos[0] = self.wall_right - r
            self.root_vel[0] = 0.0
        if self.root_pos[1] - r < self.ceiling:
            self.root_pos[1] = self.ceiling + r
            if self.root_vel[1] < 0.0:
                self.root_vel[1] = 0.0
        self.grounded = max(self.collider_low(b) for b in self.order) > self.floor - 6.0


# ------------------------------- tests -------------------------------

def _report(label, value, ok):
    print("  %-50s %-14s %s" % (label, value, "ok" if ok else "FAIL"))
    return ok


def run(spec_path):
    spec = room(json.load(open(spec_path, encoding="utf-8")))
    by_name, order = bake(spec["bones"])
    ok = True

    print("=== 0. it starts standing on the floor, not above it ===")
    # The bug this exists for: the room is deeper than the artwork, so the figure has to be
    # offset down onto the real floor -- and the offset was computed in a way that cancelled
    # itself out. The pet started a body's height in the air, fell, and was gone.
    rag = Ragdoll(spec, by_name, order, stiffness=0.0)
    low = max(rag.collider_low(b) for b in rag.order)
    # A hand's width, not a hair: the collider is a capsule a little fatter than the drawing,
    # and where exactly it settles is the collider's business. What must never happen again
    # is being a BODY's height out, which is what this catches.
    ok &= _report("the feet are at the floor line at t=0",
                  "%.0f px off, floor %.0f" % (rag.floor - low, rag.floor),
                  abs(low - rag.floor) < 0.06 * rag.standing_span)
    ok &= _report("and the head is a body higher up",
                  "head %.0f, floor %.0f" % (by_name["head"].wpos[1], rag.floor),
                  by_name["head"].wpos[1] < rag.floor - 0.7 * rag.standing_span)

    for _ in range(240):
        rag.step(1.0 / 60.0)
    low = max(rag.collider_low(b) for b in rag.order)
    ok &= _report("four seconds later it is still on the floor",
                  "%.0f px off" % (rag.floor - low), abs(low - rag.floor) < 40.0)

    print("")
    print("=== 0c. the global gravity dial does what it says ===")
    # 全局设置 has one dial that reaches the physics, and this is it. Twice the gravity has to
    # fall visibly faster and not merely differently.
    fallen = {}
    for scale in (0.5, 1.0, 2.0):
        rag = Ragdoll(spec, by_name, order, stiffness=0.0)
        rag.gravity_scale = scale
        # Dropped from height, because a figure standing on the floor has nowhere to fall from
        # and every gravity lands it in the same place.
        rag.root_pos[1] = rag.floor - rag.standing_span - 600.0
        start = rag.root_pos[1]
        for _ in range(30):
            rag.step(1.0 / 60.0)
        fallen[scale] = rag.root_pos[1] - start
    ok &= _report("half gravity falls about half as far",
                  "%.0f px" % fallen[0.5], 0.3 * fallen[1.0] < fallen[0.5] < 0.7 * fallen[1.0])
    ok &= _report("double gravity falls about twice as far",
                  "%.0f px" % fallen[2.0], 1.5 * fallen[1.0] < fallen[2.0] < 2.6 * fallen[1.0])

    print("")
    print("=== 0b. a figure that ends up under the world comes back ===")
    # Not physics: a last resort. Whatever the reason, a pet that is not on the screen is the
    # worst possible way to find out, so a figure whose every bone is under the floor is put
    # back on it.
    rag.root_pos[1] = rag.floor + 4000.0
    rag.root_vel = [0.0, 900.0]
    for _ in range(3):
        rag.step(1.0 / 60.0)
    ok &= _report("it is back above the floor",
                  "highest bone %.0f, floor %.0f" % (min(b.wpos[1] for b in rag.order), rag.floor),
                  min(b.wpos[1] for b in rag.order) <= rag.floor)

    print("")
    print("=== 1. a limp body let go in the air just falls ===")
    rag = Ragdoll(spec, by_name, order, stiffness=0.0)
    start = rag.root_pos[1]
    for _ in range(120):
        rag.step(1.0 / 120.0)
    ok &= _report("root fell", "%.0f px" % (rag.root_pos[1] - start), rag.root_pos[1] > start + 20)
    ok &= _report("nothing turned into NaN", "ok", math.isfinite(rag.root_pos[1]))

    print("")
    print("=== 2. picked up by the hand, the rest hangs BELOW it ===")
    rag = Ragdoll(spec, by_name, order, stiffness=0.0)
    hand = by_name["hand_L"]
    # Drag it there, do not teleport it there. A finger that jumps 1000px in one step
    # yanks the whole figure into the ceiling, and the pose it ends up in says more
    # about the impulse than about the physics.
    start = hand.wpos
    # Fully extended, hand to sole is about 1861px including the foot collider, so the
    # finger has to be above y = 119 or the feet simply reach the floor and the ground
    # ends up doing the holding.
    goal = (400.0, 60.0)
    for i in range(900):
        t = min(1.0, i / 600.0)
        pin = ("hand_L", (start[0] + (goal[0] - start[0]) * t,
                          start[1] + (goal[1] - start[1]) * t))
        rag.step(1.0 / 120.0, pin=pin)
    for _ in range(900):
        rag.step(1.0 / 120.0, pin=("hand_L", goal))
    off = math.hypot(hand.wpos[0] - goal[0], hand.wpos[1] - goal[1])
    ok &= _report("the hand reaches the finger", "%.0f px off" % off, off < 40.0)
    # Hung hand-to-sole the figure is about 1919px, and the floor sits 1920px below the
    # finger, so it is grazing the ground by design of the test, not by a bug. What
    # matters is that it is hanging: a figure that collapsed onto the floor instead
    # would span a fraction of its standing height.
    span = max(rag.collider_low(b) for b in order) - min(b.wpos[1] for b in order)
    standing = 1690.0
    ok &= _report("it is hanging upright, not heaped on the floor",
                  "%.0f px of %.0f" % (span, standing), span > standing * 0.7)

    # Lifted by one hand the arm swings UP and the whole figure hangs off it, so every
    # joint ends below the finger. Before the chain was solved first, the arm simply
    # stayed where it was and the body balanced above the finger instead -- which a limp
    # arm cannot do, and which is what this assertion exists to catch.
    h = by_name["hand_L"].wpos
    below = {}
    for name in ("hip", "chest", "head", "thigh_L", "thigh_R", "foot_L", "foot_R", "hand_R"):
        below[name] = by_name[name].wpos[1] - h[1]
    lowest = min(below.values())
    ok &= _report("every other joint hangs BELOW the hand",
                  "%.0f px below" % lowest, lowest > 0)
    ok &= _report("the feet are the lowest thing, as they should be",
                  "head %.0f / feet %.0f" % (below["head"], below["foot_L"]),
                  below["head"] < below["foot_L"])

    print("")
    print("=== 3. it comes to rest on the floor ===")
    rag = Ragdoll(spec, by_name, order, stiffness=0.0)
    for _ in range(1800):
        rag.step(1.0 / 120.0)
    a = rag.root_pos[1]
    for _ in range(240):
        rag.step(1.0 / 120.0)
    # A couple of pixels a second is the floor, not a bug: a limp figure lying on the floor is
    # still creeping at the millimetre level, and the give-way never fully switches off. What
    # this catches is a figure that is still FALLING.
    ok &= _report("height stops changing", "%.2f px drift" % abs(rag.root_pos[1] - a),
                  abs(rag.root_pos[1] - a) < 8.0)
    deepest = max(rag.collider_low(b) - rag.floor for b in order)
    ok &= _report("nothing is left inside the floor", "%.2f px" % deepest, deepest < 1.0)

    print("")
    print("=== 4. stiffness is the dial between limp and posed ===")
    drift = {}
    for stiffness in (0.0, 1.0):
        rag = Ragdoll(spec, by_name, order, stiffness=stiffness)
        for _ in range(240):
            rag.step(1.0 / 120.0)
        drift[stiffness] = max(abs(rag.ang[b.name]) for b in order)
    ok &= _report("limp figure collapses", "%.3f rad" % drift[0.0], drift[0.0] > 0.2)
    ok &= _report("stiff figure holds its pose", "%.3f rad" % drift[1.0], drift[1.0] < 0.35)
    ok &= _report("stiff is at least 3x steadier than limp",
                  "%.1fx" % (drift[0.0] / max(drift[1.0], 1e-6)),
                  drift[0.0] > drift[1.0] * 3.0)

    print("")
    print("=== 6. it lands sprawled, not perched ===")
    rag = Ragdoll(spec, by_name, order, stiffness=0.0)
    # Dropped from a height ABOVE THE FLOOR, not from an offset from where it stands: the room
    # is deeper than it used to be, so "700px above home" is now a short hop rather than a
    # real fall, and a limp figure that barely falls does not sprawl -- it props itself on one
    # knee and stays there, which is correct physics and the wrong experiment.
    rag.root_pos[1] = rag.floor - rag.standing_span - 900.0
    rag.root_vel[1] = 900.0
    for _ in range(2400):
        rag.step(1.0 / 120.0)
    touching = sum(1 for b in order if rag.collider_low(b) - rag.floor > -25.0)
    span = max(rag.collider_low(b) for b in order) - min(b.wpos[1] for b in order)
    ok &= _report("several parts rest on the floor",
                  "%d bones touching" % touching, touching >= 3)
    ok &= _report("it is lying down, not standing",
                  "%.0f px of %.0f" % (span, 1690.0), span < 1690.0 * 0.62)

    print("")
    print("=== 7. a stiff figure still lands on its feet ===")
    rag = Ragdoll(spec, by_name, order, stiffness=1.0)
    for _ in range(1200):
        rag.step(1.0 / 120.0)
    drift = max(abs(rag.ang[b.name]) for b in order)
    span = max(rag.collider_low(b) for b in order) - min(b.wpos[1] for b in order)
    ok &= _report("it stays upright", "%.0f px of %.0f" % (span, 1690.0), span > 1690.0 * 0.7)
    ok &= _report("and does not drift into a pose", "%.3f rad" % drift, drift < 0.45)

    print("")
    print("=== 4b. a part switched out of the world goes through the floor ===")
    # The per-part switch (BoneSpec.collides). Off means the FLOOR does not see it -- the bone
    # is still part of the figure, still has its mass, still swings -- and the floor has to
    # keep resolving the parts that are still solid rather than lifting the whole figure to
    # get a switched-off ribbon out of the ground.
    switched = json.loads(json.dumps(spec))
    for b in switched["bones"]:
        if b["name"] == "foot_R":
            b["collides"] = False
    by2, order2 = bake(switched["bones"])
    rag = Ragdoll(switched, by2, order2, stiffness=0.0)
    for _ in range(240):
        rag.step(1.0 / 60.0)
    foot_deep = rag.collider_low(by2["foot_R"]) - rag.floor
    solid_deep = max(rag.collider_low(b) - rag.floor for b in order2
                     if rag.solid.get(b.name, True))
    ok &= _report("the switched-off foot is under the floor line",
                  "%+.0f px" % foot_deep, foot_deep > 20.0)
    ok &= _report("and the parts that are still solid are on it",
                  "%+.0f px" % solid_deep, abs(solid_deep) < 0.06 * rag.standing_span)
    through = [b.name for b in order2
               if rag.solid.get(b.name, True) and rag.collider_low(b) - rag.floor > 20.0]
    ok &= _report("nothing but the switched-off part is through the floor",
                  "through: " + str(through), through == [])

    print("")
    print("=== 5. numbers stay finite over a long run ===")
    rag = Ragdoll(spec, by_name, order, stiffness=0.5)
    rng = random.Random(7)
    bad = 0
    for i in range(4000):
        pin = None
        if 500 <= i < 1500:
            pin = ("hand_R", (300.0 + rng.uniform(-200, 200), 400.0 + rng.uniform(-200, 200)))
        rag.step(1.0 / 60.0, pin=pin)
        for v in (rag.root_pos[0], rag.root_pos[1], rag.root_vel[0], rag.root_vel[1]):
            if not math.isfinite(v) or abs(v) > 1e7:
                bad += 1
        for name in rag.ang:
            if not math.isfinite(rag.ang[name]):
                bad += 1
    ok &= _report("no NaN, no runaway", "4000 steps", bad == 0)

    print("")
    print("=== 6. the other pin solver keeps the same per-frame budget ===")
    # _solve_pin 的 lss 分支默认不跑（PIN_MODE 是 "aim"），所以它自己的限速一直没有测试看着。
    # 那个 cap 曾经也写在迭代循环里，会被 PIN_IK_ITERATIONS 放大 —— 和 aim 分支修掉的是
    # 同一个写法。这里绕开积分器和地面直接调求解器，量到的就只会是 pin 自己的转动。
    _saved_mode = globals()["PIN_MODE"]
    globals()["PIN_MODE"] = "lss"
    try:
        by3, order3 = bake(spec["bones"])
        lss = Ragdoll(spec, by3, order3, stiffness=0.0)
        for _ in range(120):
            lss.step(1.0 / 60.0)
        bone3 = by3["hand_R"]
        gx, gy = bone3.wpos
        before3 = {b.name: b.rotation for b in order3}
        # 目标放在 9000 px 外：求解器这辈子收到的最猛的一次误差。
        lss._solve_pin(bone3, (gx + 9000.0, gy - 9000.0), 0.5, 1.0 / 60.0)
        worst = max(abs(b.rotation - before3[b.name]) for b in order3)
        budget = MAX_IK_RATE / 60.0
        ok &= _report("the least-squares pin does turn the joints",
                      "%.3f deg" % math.degrees(worst), worst > 1e-4)
        ok &= _report("but one frame cannot turn one past its budget",
                      "%.3f deg of %.3f" % (math.degrees(worst), math.degrees(budget)),
                      worst <= budget + 1e-9)
    finally:
        globals()["PIN_MODE"] = _saved_mode

    print("")
    print("=== 7. the pin and the floor keep their per-frame promises ===")
    # 抖动（振幅不大、频率大）真正的成因是两条「每帧的承诺」被破了，而它们都是**确定的**，
    # 不是统计的：
    #   · pin 一帧之内给一个关节的转动，不能超过 MAX_IK_RATE * dt
    #   · 地面一遍对同一根骨头只能转一次
    # 所以这里不设抖动阈值 —— 差分比那种东西会飘，而且实测它在出 bug 时也只有 1.16~1.27，
    # 一个不飘的阈值根本拦不住它。断言不变量，不断言手感。
    _drag_spec = room(json.load(open(spec_path)))
    by4, order4 = bake(_drag_spec["bones"])
    drag = Ragdoll(_drag_spec, by4, order4, stiffness=0.0)
    for _ in range(180):
        drag.step(1.0 / 60.0)

    pin_worst = [0.0]
    _real_pins = Ragdoll._pins

    def _spy_pins(self, dt, pins):
        before = {b.name: b.rotation for b in self.order}
        _real_pins(self, dt, pins)
        for b in self.order:
            d = abs(b.rotation - before[b.name])
            if d > pin_worst[0]:
                pin_worst[0] = d

    turn_counts = {}
    turn_worst = [0]
    turn_total = [0]
    _real_ground = Ragdoll._ground
    _real_turn = Ragdoll._turn_out

    def _spy_ground(self, dt):
        turn_counts.clear()
        r = _real_ground(self, dt)
        if turn_counts:
            turn_worst[0] = max(turn_worst[0], max(turn_counts.values()))
        return r

    def _spy_turn(self, bone, pen):
        turn_counts[bone.name] = turn_counts.get(bone.name, 0) + 1
        turn_total[0] += 1
        return _real_turn(self, bone, pen)

    Ragdoll._pins = _spy_pins
    Ragdoll._ground = _spy_ground
    Ragdoll._turn_out = _spy_turn
    try:
        # 场景要用**当初真的复现出问题的那一个**：快速横向拖手（600 px/s）。
        # 轻轻拖脚的话上限根本不吃劲，那样这条测试是绿的、也是瞎的。
        bone4 = by4["hand_R"]
        for i in range(180):
            t = i / 60.0
            drag.step(1.0 / 60.0, [("hand_R", (bone4.wpos[0] + 600.0 * t, bone4.wpos[1]), 0.5)])
    finally:
        Ragdoll._pins = _real_pins
        Ragdoll._ground = _real_ground
        Ragdoll._turn_out = _real_turn

    budget = MAX_IK_RATE / 60.0
    ok &= _report("the pin never spends more than one frame's budget on a joint",
                  "%.3f deg of %.3f" % (math.degrees(pin_worst[0]), math.degrees(budget)),
                  pin_worst[0] <= budget + 1e-9)
    ok &= _report("and it did actually pull", "%.3f deg" % math.degrees(pin_worst[0]),
                  pin_worst[0] > 1e-6)

    # 地面那一半要**另一个场景**：快速拖手时全身都在地面之上，地面一次都不出手，
    # 拿它去测地面就是一条永远绿的瞎测试。
    #
    # 而"砸到地上"也测不出来（实测最多还是 1 次）：那一下渗透很快就过去了。真正会触发的
    # 是**把手按到地面以下并按住** —— 渗透不会自己消失，地面那一遍就会盯着同一根骨头反复
    # 出手，正是当初"一帧内来回四次"的成因。这个场景是拿回退版一个个试出来的，不是猜的：
    # 按手/按脚/按头、60px 和 200px 都能到 2 次，砸下来只有 1 次。
    turn_counts.clear()
    turn_worst[0] = 0
    turn_total[0] = 0
    Ragdoll._ground = _spy_ground
    Ragdoll._turn_out = _spy_turn
    try:
        by5, order5 = bake(_drag_spec["bones"])
        pressed = Ragdoll(_drag_spec, by5, order5, stiffness=0.0)
        for _ in range(120):
            pressed.step(1.0 / 60.0)
        hand5 = by5["hand_R"]
        hx, _ = hand5.wpos
        for _ in range(60):
            pressed.step(1.0 / 60.0, [("hand_R", (hx, pressed.floor + 60.0), 0.5)])
    finally:
        Ragdoll._ground = _real_ground
        Ragdoll._turn_out = _real_turn

    ok &= _report("the floor did turn something", "%d turns" % turn_total[0], turn_total[0] > 0)
    ok &= _report("the floor turns a bone at most once per frame",
                  "most %d" % turn_worst[0], turn_worst[0] <= 1)

    print("")
    print("ALL OK" if ok else "SOME CHECKS FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else \
        "app/src/main/assets/characters/female_base/character.json"
    sys.exit(run(path))