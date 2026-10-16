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

import java.util.ArrayList;
import java.util.List;

/**
 * Where a blink lands, as pure arithmetic. No world, no chunk, no block, no Bukkit type.
 *
 * <h2>Why this is a separate class and not four lines inside the listener</h2>
 * {@code build.gradle.kts} makes paper-api {@code compileOnly} and says why: nothing in the
 * test source set may touch {@code org.bukkit}, because a test that mocks a {@code Block} is
 * a test of the mock. So the DECISION - which block a right-click puts you on, and when it
 * refuses - lives here, behind a {@link Probe} the listener fills in from the real world and
 * a test fills in from a string map. {@code BlinkFeatherWiringTest} is what holds the other
 * half: that the listener still asks.
 *
 * <h2>The rule, in the order it is applied</h2>
 * <ol>
 *   <li><b>The ray stops at the first block a body cannot occupy.</b> Not "the first opaque
 *       block", not "the first block that is not air" - the first block that fails
 *       {@link Probe#clear}. This is the whole of the anti-phasing guarantee and it is
 *       geometric rather than a permission check: you cannot blink THROUGH a wall into a
 *       base, a vault, a bunker or a locked room, whoever owns it, because the march ends
 *       at the wall. The claim and strongroom checks in the listener sit on top of that;
 *       they are not what makes it safe.</li>
 *   <li><b>The landing is the FURTHEST standing position on the surviving ray</b>, walked
 *       back from the far end. Furthest, not nearest: the ten-second stretch this exists for
 *       is "you look at a rooftop eighteen blocks off and you are on it", and a tool that
 *       lands you three blocks short of the roof has done nothing.</li>
 *   <li><b>A standing position is feet clear, head clear, and something solid underfoot.</b>
 *       All three, every time. Two of them alone is a player inside a block or a player in
 *       the air over a ravine.</li>
 * </ol>
 *
 * <h2>The settle, and why it is bounded at three</h2>
 * The ray is cast from the EYE and the player lands on their FEET, so a level look down a
 * flat street runs the ray one block above the ground the player is standing on. Without a
 * downward settle the only place that ray could ever land is inside the air block at head
 * height, which has no footing, and the feather would refuse on open ground - the most
 * common case there is.
 *
 * <p>{@link #SETTLE} blocks is the whole allowance. It cannot pass through anything: the
 * scan stops at the first level whose floor is solid, so a settle can only ever fall through
 * air it was already free to fall through. What the bound buys is the void rule - aim across
 * a ravine and the far end has no floor within three blocks, so the blink is REFUSED rather
 * than dropping the player twenty blocks into it. That refusal is the feature. A movement
 * tool that can kill you is one nobody presses at four hearts, which is the only moment it
 * is for.
 *
 * <h2>Why there is a minimum distance</h2>
 * A player facing a wall a metre away has a "furthest safe standing position" of roughly
 * where they already are. Teleporting them nowhere and then taking their five seconds is
 * strictly worse than refusing: they spent the tool and got nothing, at the exact moment
 * they were being chased. Under {@link #MIN_DISTANCE} there is no landing at all, and the
 * caller's contract is that a null landing costs nothing.
 */
public final class BlinkPlan {

    private BlinkPlan() {}

    /**
     * How finely the ray is walked, in blocks. Small enough that a corner cannot be cut
     * through diagonally - the largest gap this can skip is under a quarter of a block, and
     * a block is one - and large enough that a 20-block cast is 100 probes rather than
     * thousands. Every probe is one already-loaded block read on the main thread.
     */
    public static final double STEP = 0.2;

    /** See the class header. Three blocks of fall, and never through anything solid. */
    public static final int SETTLE = 3;

    /**
     * Blocks. Under this the blink is refused and the cooldown is not spent.
     *
     * <p>Two rather than one: one block forward is inside the space the player's own body
     * already sweeps, so it reads as the item having failed while still burning the five
     * seconds. Two is the shortest step that is visibly a step.
     */
    public static final double MIN_DISTANCE = 2.0;

    /** What the caller has to be able to answer about a block. Both are cheap reads. */
    public interface Probe {
        /**
         * Whether a player's body may occupy this block: air, grass, a torch, water. NOT
         * lava - a "safe destination" that kills the person using it is the one outcome
         * this tool must never have - and not any block with a collision box.
         */
        boolean clear(int x, int y, int z);

        /** Whether this block can be stood ON. Solid collision, top included. */
        boolean footing(int x, int y, int z);
    }

    /**
     * A block position to stand in, plus how far along the ray it was found.
     *
     * @param x     block x of the FEET
     * @param y     block y of the FEET; the head occupies y+1
     * @param z     block z of the FEET
     * @param range how far along the look vector the ray had travelled when this block was
     *              reached. Reported so the caller can show it and so the clamp is testable
     *              without re-deriving it from the coordinates.
     */
    public record Landing(int x, int y, int z, double range) {}

    /**
     * The furthest safe standing position along the look vector, or null to refuse.
     *
     * @param eyeX  the caster's eye position - the ray's origin, not their feet
     * @param dirX  the look vector; need not be normalised, and a zero vector refuses
     * @param range how far the blink may reach, in blocks
     * @param minY  the world's floor; a ray leaving it stops there
     * @param maxY  the world's ceiling, EXCLUSIVE, as {@code World#getMaxHeight} reports it
     */
    public static Landing of(double eyeX, double eyeY, double eyeZ,
                             double dirX, double dirY, double dirZ,
                             double range, int minY, int maxY, Probe probe) {
        if (probe == null || range < MIN_DISTANCE || maxY - minY < 3) return null;
        double len = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        // A zero look vector is not a direction. Normalising it would divide by zero and
        // produce NaN coordinates, which floor() turns into block 0 - the origin of the
        // world, which is a real place somebody would arrive at.
        if (!(len > 1.0e-6)) return null;
        double ux = dirX / len, uy = dirY / len, uz = dirZ / len;

        // Every distinct block the ray passes through before it meets something solid, in
        // order. Built forward because the march has to STOP at the obstruction; read
        // backward below because the landing is the furthest one that works.
        List<Landing> path = new ArrayList<>(Math.max(8, (int) (range / STEP) + 1));
        int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        int steps = (int) Math.ceil(range / STEP);
        for (int i = 1; i <= steps; i++) {
            double t = Math.min(i * STEP, range);
            int bx = floor(eyeX + ux * t);
            int by = floor(eyeY + uy * t);
            int bz = floor(eyeZ + uz * t);
            if (bx == lastX && by == lastY && bz == lastZ) continue;
            lastX = bx; lastY = by; lastZ = bz;
            if (by < minY || by >= maxY) break;
            if (!probe.clear(bx, by, bz)) break;
            path.add(new Landing(bx, by, bz, t));
        }

        for (int i = path.size() - 1; i >= 0; i--) {
            Landing at = path.get(i);
            if (at.range() < MIN_DISTANCE) return null;   // and so is everything before it
            Landing stood = settle(at, minY, maxY, probe);
            if (stood != null) return stood;
        }
        return null;
    }

    /**
     * Drops a point on the ray onto the first floor within {@link #SETTLE} blocks of it.
     *
     * <p>Returns null rather than the nearest miss, because "close to a landing" is not a
     * landing and the caller's next move is to try the block behind this one.
     */
    private static Landing settle(Landing at, int minY, int maxY, Probe probe) {
        for (int drop = 0; drop <= SETTLE; drop++) {
            int fy = at.y() - drop;
            // Nothing to stand on below the world, and nothing to put a head into above it.
            if (fy - 1 < minY || fy + 1 >= maxY) return null;
            // Re-checked at drop 0 as well: it costs one probe and it means this method is
            // correct on its own terms rather than only when called from the march.
            if (!probe.clear(at.x(), fy, at.z())) return null;
            if (!probe.clear(at.x(), fy + 1, at.z())) return null;
            if (probe.footing(at.x(), fy - 1, at.z())) {
                return new Landing(at.x(), fy, at.z(), at.range());
            }
        }
        return null;
    }

    /** Block coordinate of a world coordinate. Math.floor, NOT a cast: (int) -0.4 is 0. */
    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
