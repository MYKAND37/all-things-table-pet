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
COLLAPSE_GAIN = 6.0

#: How far over a figure has to be turned before the finger, and not the floor, is what is
#: holding it up, in radians. Not a quarter turn: a figure that has been laid on its side is
#: on the floor, and the floor resolves it exactly as it always did. This is the line
#: between standing on the ground and hanging off a hand.
UPSIDE_DOWN = 2.0

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
        physics = spec.get("physics", {})
        self.gravity = float(physics.get("gravity", 2400.0))
        self.floor = float(physics.get("floorY", 2048.0))
        self.ceiling = 0.0
        self.wall_left = 0.0
        self.wall_right = float(spec["canvas"]["width"])
        self.restitution = 0.2
        self.ground_friction = 2.5

        # Colliders. A bone with no explicit one gets a capsule sized off the head
        # height, so a package that says nothing still behaves sensibly.
        default_r = float(spec["headHeight"]) * 0.18
        self.collider = {}
        for b in spec["bones"]:
            c = b.get("collider") or {}
            self.collider[b["name"]] = (
                c.get("type", "capsule"),
                float(c.get("radius", default_r)),
            )

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
        self.root_home = (order[0].wpos[0], order[0].wpos[1] + physics.get("roomAir", 0.0))
        self.root_pos = list(self.root_home)
        # The root carries an explicit velocity. A Verlet integrator reads any positional
        # correction as velocity, and one large correction then squares its way to
        # infinity in under a second.
        self.root_vel = [0.0, 0.0]

        # How tall the figure is standing. Used to tell "still up" from "already down",
        # which is what stops the limp give-way feedback once the figure has collapsed.
        self.standing_span = (max(self.collider_low(b) for b in self.order)
                              - min(b.wpos[1] for b in self.order))
        self.ang = dict((b.name, 0.0) for b in order)
        self.ang_prev = dict((b.name, 0.0) for b in order)
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
        return (self.root_pos[0] - self.root_home[0],
                self.root_pos[1] - self.root_home[1])

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
        g = self.gravity

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
        # Not while the figure is HANGING: the feedback here is strong enough that firing it
        # mid-hang throws the figure out of the pose entirely, and a body dangling from a
        # finger has already fallen as far as it is going to.
        #
        # It IS allowed while a finger holds a figure that is still on its feet, and that is
        # the whole reason lifting an ankle is reliable rather than a coin toss. A limp body
        # held up by one ankle and still standing on the other is balanced on a support
        # polygon: correct physics, and a genuine equilibrium, so whether it topples depends
        # on which way the noise happened to nudge it. A real ragdoll has no balance to lose.
        giving = (self.figure_stiffness < 0.25 and self.grounded and slow
                  and not self.hanging()
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

        for _ in range(PIN_OUTER):
            for name, target, offset in pins:
                bone = self.by_name.get(name)
                if bone is not None:
                    self._solve_pin(bone, target, offset)

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

    def _solve_pin_aim(self, bone, target, offset=0.0):
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
                if math.hypot(end[0] - px, end[1] - py) < 1e-6:
                    continue
                current = math.atan2(end[1] - py, end[0] - px)
                wanted = math.atan2(target[1] - py, target[0] - px)
                turn = norm_angle(wanted - current) * PIN_JOINT_GAIN
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
                    b.rotation = turned
                    self.ang[b.name] = turned
                    self.fk()

    def _solve_pin(self, bone, target, offset=0.0):  # noqa: D401
        """Pull one point of the figure to the finger, in the cheapest way available."""
        if PIN_MODE == "aim":
            self._solve_pin_aim(bone, target, offset)
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
                turn = lam * term
                if abs(turn) < 1e-9:
                    continue
                turned = b.rotation + turn
                lo, hi = self.limits_of(b)
                if turned < lo:
                    turned = lo
                elif turned > hi:
                    turned = hi
                if turned != b.rotation:
                    b.rotation = turned
                    self.ang[b.name] = turned
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
        for _ in range(GROUND_PASSES):
            deepest, target = 0.0, None
            for b in self.order:
                pen = self.collider_low(b) - self.floor
                if pen > deepest:
                    deepest, target = pen, b
            if target is None or deepest < 0.05:
                settled = True
                break
            if not self._turn_out(target, deepest):
                self._lift(deepest, dt)

        if not settled:
            # Ran out of passes with something still under the floor: the root carries it.
            worst = max(self.collider_low(b) - self.floor for b in self.order)
            if worst > 0.05:
                self._lift(worst, dt)

        self._walls()

    def _turn_out(self, bone, pen):
        """Rotate a bone about its own joint until the part of it in the floor comes out."""
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
            return False
        root = self.order[0].name
        return abs(norm_angle(self.ang[root])) > UPSIDE_DOWN

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
    ok &= _report("height stops changing", "%.2f px drift" % abs(rag.root_pos[1] - a),
                  abs(rag.root_pos[1] - a) < 2.0)
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
    # Dropped from height with speed, so it has to take a real landing.
    rag.root_pos[1] -= 700.0
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
    print("ALL OK" if ok else "SOME CHECKS FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else \
        "app/src/main/assets/characters/female_base/character.json"
    sys.exit(run(path))