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

import app.vexoris.skirmish.SkirmishPlugin;
import app.vexoris.skirmish.combat.CombatTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

/**
 * The right-click: read the world, ask {@link BlinkPlan} where to land, move, draw the trail.
 *
 * <h2>Why the decision is not in here</h2>
 * Everything about WHERE a blink lands lives in {@link BlinkPlan}, which touches no Bukkit
 * type at all and is covered by its own tests. This class only fills in what the world says
 * and performs the consequences. That split is the reason the safety properties — cannot pass
 * a wall, refuses over a void, never lands you inside a block — can be proven offline instead
 * of being asserted in a comment.
 */
public final class BlinkFeatherListener implements Listener {

    private final SkirmishPlugin plugin;
    private final BlinkFeather feather;
    private final CombatTag combat;

    public BlinkFeatherListener(SkirmishPlugin plugin, BlinkFeather feather, CombatTag combat) {
        this.plugin = plugin;
        this.feather = feather;
        this.combat = combat;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;
        if (!feather.isFeather(event.getItem())) return;

        Player player = event.getPlayer();
        // The event fires once per hand. The off-hand pass carries the off-hand item, so it is
        // already filtered out above UNLESS both hands hold a feather — which this covers, so
        // one click is one blink and never two.
        if (hand == EquipmentSlot.OFF_HAND
                && feather.isFeather(player.getInventory().getItemInMainHand())) {
            return;
        }

        // An interactable block's own use wins unless the player is sneaking. Silent, because
        // this is not a refusal — it is somebody opening a chest while holding a feather.
        Block clicked = event.getClickedBlock();
        if (action == Action.RIGHT_CLICK_BLOCK && clicked != null
                && !player.isSneaking() && clicked.getType().isInteractable()) {
            return;
        }

        event.setCancelled(true);

        if (player.hasCooldown(BlinkFeather.MATERIAL)) return;   // vanilla sweep is the message

        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();

        BlinkPlan.Landing landing = BlinkPlan.of(
                eye.getX(), eye.getY(), eye.getZ(),
                dir.getX(), dir.getY(), dir.getZ(),
                plugin.blinkRange(), world.getMinHeight(), world.getMaxHeight(),
                new WorldProbe(world));

        if (landing == null) {
            // REFUSED, AND IT COSTS NOTHING. No cooldown is spent on a move that did not
            // happen — see the plugin header. The player is told why, because a tool that
            // does nothing silently is indistinguishable from a broken one.
            player.sendActionBar(Component.text("No room to blink.")
                    .color(NamedTextColor.GRAY));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
            return;
        }

        Location from = player.getLocation().clone();
        Location to = new Location(world,
                landing.x() + 0.5, landing.y(), landing.z() + 0.5,
                from.getYaw(), from.getPitch());

        if (!feather.blink(player, to)) return;   // cancelled elsewhere: spend nothing

        player.setCooldown(BlinkFeather.MATERIAL, plugin.blinkCooldownTicks());
        trail(world, from);
        trail(world, to);
    }

    /**
     * A puff and a note at BOTH ends.
     *
     * <p>Deliberate, and it is a fairness decision rather than decoration: a pursuer has to be
     * able to follow. An escape nobody can track is not an escape, it is a disappearance, and
     * a server where loot can be taken needs the chase to stay possible.
     */
    private void trail(World world, Location at) {
        world.spawnParticle(Particle.PORTAL, at.clone().add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.4);
        world.spawnParticle(Particle.CLOUD, at.clone().add(0, 0.2, 0), 8, 0.2, 0.1, 0.2, 0.01);
        world.playSound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.4f);
    }

    /** The world, answering the two questions {@link BlinkPlan.Probe} asks of it. */
    private record WorldProbe(World world) implements BlinkPlan.Probe {
        @Override
        public boolean clear(int x, int y, int z) {
            Block b = world.getBlockAt(x, y, z);
            // NOT "is it air". A body may occupy grass, a torch or water; it may not occupy
            // anything with a collision box, and it must never be sent into lava — a "safe
            // destination" that kills the person using it is the one outcome this tool must
            // never have.
            if (b.getType() == org.bukkit.Material.LAVA) return false;
            return b.isPassable();
        }

        @Override
        public boolean footing(int x, int y, int z) {
            Block b = world.getBlockAt(x, y, z);
            return b.getType().isSolid();
        }
    }
}
