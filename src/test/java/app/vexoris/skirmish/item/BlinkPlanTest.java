/*
 * Skirmish — an escape tool and a combat lock for Minecraft servers.
 * Copyright (C) 2026 Alex Fernandez
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 */
package app.vexoris.skirmish.item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a Blink Feather puts you, and when it refuses. The decision, in full.
 *
 * <h2>What this proves, and what it structurally cannot</h2>
 * {@code build.gradle.kts} makes paper-api {@code compileOnly} and means it: no test in this
 * source set can hold a {@code Player}, raise a {@code PlayerInteractEvent} or read a real
 * {@code Block}. So this file proves the DECISION - which block a right-click puts you on, how
 * far it may reach, and every case in which it declines - against a world built out of a
 * boolean array. {@link BlinkFeatherWiringTest} holds the other half: that the listener still
 * asks, and that a refusal still costs nothing.
 *
 * <p>It cannot show a particle, a sound, a cooldown sweep on the item's face, or that twenty
 * blocks is the right number. Those need a connected client and are listed as open.
 *
 * <h2>Why the assertions are shaped the way they are</h2>
 * This project has shipped six defects that "looked correct and did nothing", so an assertion
 * a stub could satisfy is worse than none. Three habits here:
 *
 * <ul>
 *   <li><b>Every refusal is paired with the near-identical world in which it must SUCCEED.</b>
 *       "returns null when there is a wall" is passed by {@code return null;}. "returns null
 *       with the wall and lands twenty blocks out without it" is not.</li>
 *   <li><b>The clamp is checked for reach as well as for ceiling.</b> {@code range() <= max}
 *       alone is satisfied by a plan that never moves anybody. The reach assertions pin the
 *       exact block it must arrive at, so short is as red as far.</li>
 *   <li><b>{@link #everyLandingIsReachableAndSafe} re-derives its invariants from the fixture
 *       rather than from {@link BlinkPlan}</b> - it marches its own ray with its own floor
 *       function over 1,836 casts and requires every landing to be standable and every block
 *       between the eye and it to be clear. A plan that teleported blindly forward passes the
 *       open-ground tests and dies here on the first wall.</li>
 * </ul>
 */
class BlinkPlanTest {

    /** Vanilla standing eye height. The ray starts at the eye; the player lands on their feet. */
    private static final double EYE = 1.62;
    /** Paper's overworld limits, so no scenario is accidentally testing the world edge. */
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 320;

    // ---- a world, as two questions over a boolean array --------------------------------------

    /**
     * A block world. Outside the array is AIR with nothing under it, which is the void - and
     * modelling it that way rather than as "solid everywhere outside" is deliberate: the void
     * is the case the settle bound exists for.
     */
    private static final class TestWorld implements BlinkPlan.Probe {
        private final int ox, oy, oz, sx, sy, sz;
        private final boolean[] solid;

        TestWorld(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
            this.ox = minX; this.oy = minY; this.oz = minZ;
            this.sx = sizeX; this.sy = sizeY; this.sz = sizeZ;
            this.solid = new boolean[sizeX * sizeY * sizeZ];
        }

        private int index(int x, int y, int z) {
            int i = x - ox, j = y - oy, k = z - oz;
            if (i < 0 || j < 0 || k < 0 || i >= sx || j >= sy || k >= sz) return -1;
            return (i * sy + j) * sz + k;
        }

        boolean isSolid(int x, int y, int z) {
            int i = index(x, y, z);
            return i >= 0 && solid[i];
        }

        /** Inclusive on both corners; anything outside the array is silently skipped. */
        TestWorld fill(int x0, int y0, int z0, int x1, int y1, int z1) {
            return paint(x0, y0, z0, x1, y1, z1, true);
        }

        /** The inverse: cuts air out of whatever is there. */
        TestWorld carve(int x0, int y0, int z0, int x1, int y1, int z1) {
            return paint(x0, y0, z0, x1, y1, z1, false);
        }

        private TestWorld paint(int x0, int y0, int z0, int x1, int y1, int z1, boolean v) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        int i = index(x, y, z);
                        if (i >= 0) solid[i] = v;
                    }
                }
            }
            return this;
        }

        @Override public boolean clear(int x, int y, int z) { return !isSolid(x, y, z); }
        @Override public boolean footing(int x, int y, int z) { return isSolid(x, y, z); }
    }

    /** A street: ground with its surface at y=64, from x=-8 out to x=220. */
    private static TestWorld street() {
        return new TestWorld(-8, 32, -8, 232, 288, 17).fill(-8, 32, -8, 220, 64, 8);
    }

    /** The caster: feet at (0.5, 65, 0.5) on that street, looking due east and level. */
    private static BlinkPlan.Landing castEast(TestWorld w, double range) {
        return BlinkPlan.of(0.5, 65 + EYE, 0.5, 1, 0, 0, range, MIN_Y, MAX_Y, w);
    }

    // ---- it moves you, and it moves you somewhere you can stand ------------------------------

    @Test
    @DisplayName("A blink down an open street moves the player and lands them on the ground")
    void aSuccessfulBlinkMovesYouAndLandsSafely() {
        TestWorld w = street();
        BlinkPlan.Landing at = castEast(w, 20);

        assertNotNull(at, "a level look down twenty blocks of open, flat street produced no "
                + "landing at all. This is the commonest situation the item will ever be used "
                + "in; if it refuses here the feather is inert everywhere that matters.");

        // MOVED. The ray starts at the eye, more than a block above the feet, so a plan with
        // no downward settle would land inside the air at head height or refuse outright -
        // both of which are "the player did not go anywhere".
        assertEquals(20, at.x(), "the blink did not travel the length of the street");
        assertEquals(0, at.z(), "a level look due east drifted in z");
        assertTrue(at.range() >= BlinkPlan.MIN_DISTANCE,
                "the landing was inside the distance the plan itself refuses to move anybody");

        // SAFE. Feet clear, head clear, something underfoot - all three, checked against the
        // fixture rather than read back out of the plan.
        assertEquals(65, at.y(), "the landing was not on the street surface");
        assertTrue(w.clear(at.x(), at.y(), at.z()), "the player was placed inside a block");
        assertTrue(w.clear(at.x(), at.y() + 1, at.z()), "the player's head was inside a block");
        assertTrue(w.footing(at.x(), at.y() - 1, at.z()), "the player was placed in mid air");
    }

    @Test
    @DisplayName("THE ROOFTOP: an eighteen-block roof is reached, not the street beneath it")
    void itLandsOnTheRoofAndNotTheStreetBelow() {
        // The owner's own ten-second stretch: four hearts, somebody on you, a rooftop
        // eighteen blocks off, right-click, you are on it. A plan that took the NEAREST safe
        // spot rather than the FURTHEST would put the player back on the street a metre away,
        // which is a tool that has done nothing at the exact moment it was needed.
        TestWorld w = street();
        w.fill(18, 65, -8, 24, 69, 8);          // a six-wide building; roof surface at y=70

        // Looking up over the parapet from the street.
        BlinkPlan.Landing at = BlinkPlan.of(0.5, 65 + EYE, 0.5, 18.0, 3.88, 0.0,
                20, MIN_Y, MAX_Y, w);

        assertNotNull(at, "the rooftop cast produced no landing");
        assertEquals(70, at.y(), "the blink did not land on the roof. y=65 would mean it took "
                + "the nearest safe spot and left the player on the street they were being "
                + "chased down; anything else means it landed inside the building.");
        assertTrue(at.x() >= 18 && at.x() <= 24,
                "the landing was not on the building's footprint: x=" + at.x());
        assertTrue(w.footing(at.x(), 69, at.z()), "there is no roof under the landing");
    }

    // ---- the clamp ---------------------------------------------------------------------------

    @Test
    @DisplayName("The reach is exactly the configured range - never further, and never short")
    void theRangeClampHoldsInBothDirections() {
        // Both halves matter. `range() <= max` on its own is satisfied by a plan that never
        // moves anybody, so the arrival block is pinned too: a short blink is as red as a
        // long one.
        TestWorld w = street();
        for (int range = 3; range <= 60; range++) {
            BlinkPlan.Landing at = castEast(w, range);
            assertNotNull(at, "range " + range + ": no landing on open, flat street");
            assertTrue(at.range() <= range + 1.0e-9,
                    "range " + range + ": the plan reported a reach of " + at.range()
                    + ", which is further than it was allowed to travel");
            assertEquals(range, at.x(), "range " + range + ": the blink arrived at x="
                    + at.x() + ". The caster's eye is at x=0.5 and the landing is the centre "
                    + "of its block, so the only correct arrival is exactly x=range.");
        }
    }

    @Test
    @DisplayName("A range under the minimum distance refuses outright")
    void underTheMinimumThereIsNoBlinkAtAll() {
        TestWorld w = street();
        assertNull(castEast(w, BlinkPlan.MIN_DISTANCE - 0.01),
                "a range below MIN_DISTANCE produced a landing. Such a landing is inside the "
                + "space the player's own body already occupies, so it reads as the item "
                + "having failed while still taking the five seconds.");
        assertNotNull(castEast(w, BlinkPlan.MIN_DISTANCE + 1.0),
                "the control failed: nothing lands even ABOVE the minimum, so the assertion "
                + "above proves nothing about the minimum.");
    }

    // ---- you cannot blink through anything ----------------------------------------------------

    @Test
    @DisplayName("A wall stops the blink dead, and the same cast clears without it")
    void theRayCannotPassThroughAWall() {
        // This is the whole anti-phasing guarantee, and it is geometric rather than a
        // permission check: no wall, no vault, no bunker and no locked room is enterable by a
        // feather, whoever owns it, because the march ends at the first block a body cannot
        // occupy.
        TestWorld walled = street().fill(10, 65, -8, 10, 72, 8);
        BlinkPlan.Landing blocked = castEast(walled, 20);

        assertNotNull(blocked, "the walled cast refused entirely; it should stop SHORT of the "
                + "wall, not decline - there are nine blocks of good street in front of it");
        assertTrue(blocked.x() < 10, "the blink ended at x=" + blocked.x() + ", at or beyond "
                + "the wall at x=10. A player just walked through a wall.");
        assertEquals(9, blocked.x(), "the blink did not stop on the last block before the wall");

        // THE CONTROL, and it is what makes the assertion above worth anything: remove the
        // wall and nothing else, and the identical cast must run the full twenty blocks.
        // Without this, an implementation that always returned x=9 would pass.
        BlinkPlan.Landing open = castEast(street(), 20);
        assertNotNull(open, "the unwalled control produced no landing");
        assertEquals(20, open.x(), "with the wall removed the identical cast still stopped "
                + "short, so the wall is not what stopped it and this test certifies nothing");
    }

    @Test
    @DisplayName("No cast from outside a sealed room ever lands inside it")
    void aSealedRoomStaysSealed() {
        // The property stated the way the design states it, rather than as one lucky cast:
        // a strongroom with a one-block shell, a hollow interior, and 792 casts at it from
        // outside. The claim and vault checks in the listener sit ON TOP of this; they are
        // not what makes it safe.
        TestWorld w = street();
        w.fill(12, 65, -6, 22, 73, 6);          // a solid block of stone
        w.carve(14, 66, -4, 20, 71, 4);         // hollowed out: a sealed room, no door

        int casts = 0, landings = 0;
        for (int yaw = 0; yaw < 360; yaw += 5) {
            for (int pitch = -40; pitch <= 40; pitch += 8) {
                casts++;
                double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
                BlinkPlan.Landing at = BlinkPlan.of(0.5, 65 + EYE, 0.5,
                        -Math.sin(ry) * Math.cos(rp), -Math.sin(rp), Math.cos(ry) * Math.cos(rp),
                        20, MIN_Y, MAX_Y, w);
                if (at == null) continue;
                landings++;
                boolean inside = at.x() >= 14 && at.x() <= 20
                        && at.y() >= 66 && at.y() <= 71
                        && at.z() >= -4 && at.z() <= 4;
                assertTrue(!inside, "yaw=" + yaw + " pitch=" + pitch + ": the blink landed at ("
                        + at.x() + "," + at.y() + "," + at.z() + "), INSIDE a sealed room with "
                        + "no door. The feather is a key.");
            }
        }
        // 792 = 72 yaws x 11 pitches, and the 11 is the half that gets "corrected" back to
        // a wrong number. yaw 0..355 step 5 is 72 values. pitch -40..40 step 8 is
        // -40,-32,-24,-16,-8,0,8,16,24,32,40 - ELEVEN values, not ten, because the loop is
        // `pitch <= 40` and so casts +40 as well as -40. 72 x 11 = 792. This literal read 720
        // when the test was written: the sweep was right and the number was wrong. Counted,
        // not eyeballed - if you are about to change it back, run the loop and count.
        assertEquals(792, casts, "the sweep did not run the number of casts it claims to");
        assertTrue(landings > casts / 2, "only " + landings + " of " + casts + " casts landed "
                + "anywhere at all, so 'never inside' is mostly a statement about nothing "
                + "happening. Fix the fixture before trusting this test.");
    }

    @Test
    @DisplayName("A low overhang is not blinked into: the head has to be clear too")
    void theHeadClearanceIsEnforced() {
        // Feet clear and footing underneath but a ceiling one block up is a player
        // suffocating inside a slab. All three conditions, every time.
        TestWorld w = street().fill(15, 67, -8, 25, 67, 8);
        BlinkPlan.Landing at = castEast(w, 20);

        assertNotNull(at, "the overhang cast refused entirely rather than stopping short");
        assertTrue(w.clear(at.x(), at.y() + 1, at.z()), "the landing at (" + at.x() + ","
                + at.y() + "," + at.z() + ") has a solid block where the player's head goes, "
                + "so head clearance is not being checked at all");

        BlinkPlan.Landing open = castEast(street(), 20);
        assertEquals(20, open.x(), "the control failed: with the overhang removed the same "
                + "cast must still reach twenty blocks");
    }

    // ---- the void rule -------------------------------------------------------------------------

    @Test
    @DisplayName("A ravine with no far side REFUSES rather than dropping the player into it")
    void thereIsNoLandingOverTheVoid() {
        // A movement tool that can kill you is one nobody presses at four hearts, which is
        // the only moment it is for. SETTLE bounds the fall at three blocks; past that there
        // is no landing at all, and the caller's contract is that a null costs nothing.
        TestWorld lip = new TestWorld(-8, 32, -8, 232, 288, 17).fill(-8, 32, -8, 0, 64, 8);
        assertNull(castEast(lip, 20),
                "the plan returned a landing out over a chasm with no floor beneath it. That "
                + "is a feather that kills the person who presses it.");

        // THE CONTROL: put a far bank in and the identical cast must cross to it. Otherwise
        // "returns null" above is indistinguishable from a plan that never works at all.
        TestWorld bridged = new TestWorld(-8, 32, -8, 232, 288, 17)
                .fill(-8, 32, -8, 0, 64, 8)
                .fill(15, 32, -8, 40, 64, 8);
        BlinkPlan.Landing across = castEast(bridged, 20);
        assertNotNull(across, "the identical cast with a far bank still refused, so the "
                + "refusal above is not about the void and this test certifies nothing");
        assertEquals(20, across.x(), "the blink did not reach the far bank");
        assertEquals(65, across.y(), "the blink did not land on the far bank's surface");
    }

    @Test
    @DisplayName("The settle stops at the edge of a roof instead of falling to the street")
    void theSettleIsBoundedAndWalksBack() {
        // Standing on a roof that runs out. The street is six blocks below the roof's far
        // edge - further than SETTLE - so the plan must walk BACK along the ray and land on
        // the last block of roof rather than step off it.
        TestWorld w = new TestWorld(-8, 32, -8, 232, 288, 17)
                .fill(-8, 32, -8, 40, 64, 8)      // the street, surface y=64
                .fill(-8, 65, -8, 10, 69, 8);     // a block of building, roof surface y=70
        BlinkPlan.Landing at = BlinkPlan.of(0.5, 70 + EYE, 0.5, 1, 0, 0, 20, MIN_Y, MAX_Y, w);

        assertNotNull(at, "a level look along a rooftop produced no landing");
        assertEquals(70, at.y(), "the blink fell off the roof to y=" + at.y() + ". The street "
                + "is six blocks down, which is more than SETTLE, so this is either the settle "
                + "running past its bound or the walk-back not happening.");
        assertEquals(10, at.x(), "the blink did not stop on the last block of roof");
    }

    // ---- degenerate input ----------------------------------------------------------------------

    @Test
    @DisplayName("A zero look vector is not a direction and produces no landing")
    void aZeroLookVectorRefuses() {
        // Normalising it divides by zero, and floor(NaN) is block 0 - the world origin, which
        // is a real place somebody would arrive at.
        assertNull(BlinkPlan.of(0.5, 66.62, 0.5, 0, 0, 0, 20, MIN_Y, MAX_Y, street()),
                "a zero-length look vector produced a landing");
    }

    @Test
    @DisplayName("A null probe refuses instead of throwing")
    void aNullProbeRefuses() {
        assertNull(BlinkPlan.of(0.5, 66.62, 0.5, 1, 0, 0, 20, MIN_Y, MAX_Y, null));
    }

    @Test
    @DisplayName("The ray stops at the world floor and the world ceiling")
    void theWorldLimitsAreHonoured() {
        // Straight down through empty space towards the world floor: nothing to stand on.
        TestWorld hollow = new TestWorld(-8, -70, -8, 40, 400, 17);
        assertNull(BlinkPlan.of(0.5, 0, 0.5, 0, -1, 0, 20, MIN_Y, MAX_Y, hollow),
                "a cast straight down through empty space to the world floor produced a "
                + "landing, so a player would be teleported into the void");

        // A world with no room to stand in at all is refused before anything is probed.
        assertNull(BlinkPlan.of(0.5, 66.62, 0.5, 1, 0, 0, 20, 64, 66, street()),
                "a world two blocks tall produced a landing");
    }

    // ---- the invariants, over a lot of casts ----------------------------------------------------

    @Test
    @DisplayName("Across 1,836 casts every landing is standable, in range, and reachable")
    void everyLandingIsReachableAndSafe() {
        // The blunt instrument, and the one a naive implementation dies on. A blocky world of
        // pillars, ledges, pits and slabs; a full sphere of look directions; and three
        // invariants re-derived from the FIXTURE using this file's own floor function:
        //
        //   1. the landing is standable      - feet clear, head clear, footing beneath
        //   2. the landing is within range   - and no further
        //   3. the landing is REACHABLE      - every distinct block on the ray from the eye up
        //                                      to the landing is clear, the landing sits on
        //                                      that ray in x and z, and the settle column down
        //                                      to the feet is clear and no deeper than SETTLE
        //
        // (3) is the one that cannot be faked. A plan that teleported blindly along the look
        // vector passes (1) and (2) on open ground and fails here on the first wall.
        TestWorld w = new TestWorld(-40, 0, -40, 120, 160, 120);
        w.fill(-40, 0, -40, 79, 64, 79);                              // ground, surface y=64
        for (int x = -36; x <= 76; x += 7) {                          // a forest of pillars
            for (int z = -36; z <= 76; z += 9) {
                w.fill(x, 65, z, x + 1, 65 + ((x * 31 + z * 17) & 7), z + 1);
            }
        }
        w.fill(-10, 65, -10, 6, 70, 6);                               // a solid building
        w.fill(20, 72, -20, 46, 72, 20);                              // a slab to duck under
        for (int x = 30; x <= 40; x++) w.fill(x, 40, -6, x, 64, 6);   // a pit through the ground
        w.carve(30, 41, -6, 40, 64, 6);                               // ...actually hollow

        // Three casters, each given air to stand in and something to stand on. The fourth
        // number is 1 when the caster ends up standing on OPEN GROUND and 0 when they do not,
        // and it is not decoration - see the guard at the bottom of this method.
        //
        // (0,65,0) is inside the footprint of the solid building filled above, so carving a
        // pocket for it does not put it outdoors: it puts it in a 3x3x4 CLOSET with stone
        // exactly two blocks away on all four sides and a ceiling four above. Measured, on
        // this fixture: it lands 24 of its 612 casts, all of them 2.00-block diagonal hops
        // that stay inside the closet, and every refusal is MIN_DISTANCE doing its documented
        // job - "a player facing a wall a metre away" is the case that rule exists for.
        //
        // It is kept rather than moved, because it is coverage nothing else in this file has:
        // aSealedRoomStaysSealed only ever casts INTO a sealed room, and this is the converse
        // - you cannot blink your way OUT of one either. It is held to that property
        // explicitly below instead of being averaged into a landing rate, where all it did
        // was drag the total down and make the old floor unreachable.
        int[][] origins = {{0, 65, 0, 0}, {0, 71, 0, 1}, {24, 66, 14, 1}};
        for (int[] o : origins) {
            w.carve(o[0] - 1, o[1], o[2] - 1, o[0] + 1, o[1] + 3, o[2] + 1);
            w.fill(o[0] - 1, o[1] - 1, o[2] - 1, o[0] + 1, o[1] - 1, o[2] + 1);
        }

        int landings = 0, casts = 0;
        // The band a player would actually cast in, counted separately from the total. See
        // the guard at the bottom for why the total cannot carry that weight.
        int nearCasts = 0, nearLandings = 0, sealedLandings = 0;
        for (int[] o : origins) {
            boolean openGround = o[3] == 1;
            double ex = o[0] + 0.5, ey = o[1] + EYE, ez = o[2] + 0.5;
            for (int yaw = 0; yaw < 360; yaw += 10) {
                for (int pitch = -80; pitch <= 80; pitch += 10) {
                    casts++;
                    double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
                    double ux = -Math.sin(ry) * Math.cos(rp);
                    double uy = -Math.sin(rp);
                    double uz = Math.cos(ry) * Math.cos(rp);
                    BlinkPlan.Landing at =
                            BlinkPlan.of(ex, ey, ez, ux, uy, uz, 20, MIN_Y, MAX_Y, w);
                    // Counted BEFORE the null check, and that ordering is the whole point: a
                    // REFUSAL is what this band is watching for, so counting after the
                    // `continue` would tally only the casts that already worked.
                    if (openGround && Math.abs(pitch) <= 20) {
                        nearCasts++;
                        if (at != null) nearLandings++;
                    }
                    if (at == null) continue;
                    landings++;
                    String where = "from (" + o[0] + "," + o[1] + "," + o[2] + ") yaw=" + yaw
                            + " pitch=" + pitch + " -> (" + at.x() + "," + at.y() + ","
                            + at.z() + ")";

                    // (0) THE SEALED CASTER. Its casts are excluded from the band guard
                    // below, so they are held to the opposite property rather than quietly
                    // dropped: a blink from inside a closed box has to stay in the box. The
                    // bounds are re-derived from the same expressions the carve above uses,
                    // so the pocket and the assertion cannot drift apart.
                    if (!openGround) {
                        sealedLandings++;
                        assertTrue(Math.abs(at.x() - o[0]) <= 1 && Math.abs(at.z() - o[2]) <= 1
                                        && at.y() >= o[1] && at.y() <= o[1] + 3,
                                where + ": a cast from inside a sealed closet landed OUTSIDE "
                                + "it. The march is supposed to end at the first block a body "
                                + "cannot occupy, so this is a player phasing through stone.");
                    }

                    // (1) standable
                    assertTrue(w.clear(at.x(), at.y(), at.z()), where + ": feet inside a block");
                    assertTrue(w.clear(at.x(), at.y() + 1, at.z()), where + ": head in a block");
                    assertTrue(w.footing(at.x(), at.y() - 1, at.z()), where + ": no footing");

                    // (2) in range
                    assertTrue(at.range() <= 20 + 1.0e-9, where + ": reach " + at.range());
                    assertTrue(at.range() >= BlinkPlan.MIN_DISTANCE,
                            where + ": inside the minimum distance");

                    // (3) reachable - the ray, marched independently of BlinkPlan
                    int lx = Integer.MIN_VALUE, ly = Integer.MIN_VALUE, lz = Integer.MIN_VALUE;
                    int steps = (int) Math.ceil(at.range() / BlinkPlan.STEP);
                    for (int i = 1; i <= steps; i++) {
                        double t = Math.min(i * BlinkPlan.STEP, at.range());
                        int bx = (int) Math.floor(ex + ux * t);
                        int by = (int) Math.floor(ey + uy * t);
                        int bz = (int) Math.floor(ez + uz * t);
                        if (bx == lx && by == ly && bz == lz) continue;
                        lx = bx; ly = by; lz = bz;
                        assertTrue(w.clear(bx, by, bz), where + ": the ray passed THROUGH a "
                                + "solid block at (" + bx + "," + by + "," + bz + "). The "
                                + "player just phased into somewhere they could not walk to.");
                    }
                    int rayY = (int) Math.floor(ey + uy * at.range());
                    assertEquals(lx, at.x(), where + ": the landing left the ray in x");
                    assertEquals(lz, at.z(), where + ": the landing left the ray in z");
                    assertTrue(at.y() <= rayY, where + ": the landing rose above the ray");
                    assertTrue(rayY - at.y() <= BlinkPlan.SETTLE, where + ": the settle fell "
                            + (rayY - at.y()) + " blocks, past its bound of " + BlinkPlan.SETTLE);
                    for (int y = at.y(); y <= rayY; y++) {
                        assertTrue(w.clear(at.x(), y, at.z()), where + ": the settle fell "
                                + "through a solid block at y=" + y);
                    }
                }
            }
        }

        // NOT VACUOUS. `return null;` satisfies every assertion above, so the run has to
        // show that the casts a player would really make DID move somebody.
        //
        // WHY THIS IS NOT "landings > casts / 2" ANY MORE, AND WHY THAT IS NOT A CLIMBDOWN.
        // The old floor asked for half of ALL 1,836 casts and measured 757 (41%). Every one
        // of the three shortfalls behind that number is CORRECT behaviour, counted on this
        // exact fixture:
        //
        //   pitch +50..+80 (steeply down)   0 of 432 land.  Aiming at your own feet puts
        //                                   every candidate inside MIN_DISTANCE. Refusing is
        //                                   the documented rule, not a miss.
        //   pitch -60..-80 (steeply up)    32 of 324 land.  There is no footing in open air
        //                                   within SETTLE. Refusing is the void rule, which
        //                                   is the thing stopping this item killing people.
        //   the sealed closet caster       24 of 612 land, none of them leaving the closet.
        //                                   Stone two blocks away on every side is, again,
        //                                   MIN_DISTANCE working.
        //
        // So the old floor asserted something FALSE: a correct plan cannot reach it on this
        // fixture. And the change that was nearly made instead - dropping the bar to two
        // fifths so that 41% clears it - is worse than leaving it red. It passes by 23 casts
        // out of 1,836, a margin of 1.3 points: a threshold shaved to the number already in
        // front of it, which certifies nothing and which any innocent edit to the pillars or
        // the slab flips red for reasons that have nothing to do with the feather.
        //
        // What replaces it is scoped rather than shaved. The near-horizontal band - a player
        // running roughly level and looking roughly where they are going, which is the only
        // situation this item is ever pressed in - is held to EVERY one of its 360 casts, and
        // no steep-angle landing can pay into it. Sabotage-checked, reading 0 of 360 both
        // times: once against a plan hard-wired to `return null`, and once against a plan
        // that refuses only casts within ~24 degrees of level while every steep cast still
        // lands. What is NOT claimed, because it was measured and was not true: that the old
        // floor would have stayed green through that second sabotage. It would have gone red
        // too, at 21%. The case against it is that it was unreachable, not that it was blind.
        assertEquals(1836, casts, "the sweep did not run the number of casts it claims to");

        // The band has to BE something before "all of it lands" means anything: an empty band
        // makes the next assertion 0 == 0. 360 = 2 open-ground origins x 36 yaws x 5 pitches
        // (-20,-10,0,10,20).
        assertEquals(360, nearCasts, "the near-horizontal band over open ground is not the "
                + "size it claims to be, so the assertion below is about the wrong set of "
                + "casts - or, if it has gone to zero, about no casts at all");
        assertEquals(nearCasts, nearLandings, "only " + nearLandings + " of " + nearCasts
                + " near-horizontal casts from open ground produced a landing, and every one "
                + "of them must. Flat ground, head clearance, twenty blocks of room and a "
                + "level look is the exact situation the feather exists for; a refusal there "
                + "is the item being inert at the one moment somebody presses it.");

        // And the closet caster is not simply excluded - it landed, and everything it landed
        // on was inside the closet, which is what makes the containment check above a
        // statement about something rather than about a caster that never moved.
        assertTrue(sealedLandings > 0, "the sealed caster produced no landing at all, so the "
                + "'a blink from inside a closed box stays in the box' assertion never ran. "
                + "Fix the fixture before trusting it.");
    }
}
