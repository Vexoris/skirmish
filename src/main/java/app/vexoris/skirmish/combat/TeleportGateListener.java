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
package app.vexoris.skirmish.combat;

import app.vexoris.skirmish.SkirmishPlugin;
import app.vexoris.skirmish.item.BlinkFeather;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * You may not LEAVE a fight. You may still try to survive one.
 *
 * <h2>The one exemption, and why it is not a hole</h2>
 * The Blink Feather is stood down for the length of its own teleport call. It arrives as
 * cause PLUGIN, which is inside the blocked set, so without this the tool would be refused in
 * the exact situation it exists for.
 *
 * <p>It does not reopen the escape this gate closes. A blink is bounded by its configured
 * range, it cannot cross a wall, it draws a puff at both ends so the trail survives, and it
 * costs a cooldown. None of that is true of an ender pearl chain or /home.
 *
 * <p>The predicate is true ONLY inside {@link BlinkFeather#blink}'s synchronous teleport,
 * which adds the flag before and clears it in a finally — so no other teleport, from this
 * player or anyone else, can ever be inside the window.
 */
public final class TeleportGateListener implements Listener {

    private final SkirmishPlugin plugin;
    private final CombatTag combat;
    private final BlinkFeather feather;

    public TeleportGateListener(SkirmishPlugin plugin, CombatTag combat, BlinkFeather feather) {
        this.plugin = plugin;
        this.combat = combat;
        this.feather = feather;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!plugin.combatLockEnabled()) return;
        if (feather.blinking(event.getPlayer().getUniqueId())) return;

        int left = combat.remainingSeconds(event.getPlayer().getUniqueId());
        if (left <= 0) return;

        // THE CAUSE IS MATCHED BY NAME, NOT BY ENUM CONSTANT, AND THAT IS DELIBERATE.
        //
        // Chorus fruit teleports you under CHORUS_FRUIT on Paper 1.21.x and under
        // CONSUMABLE_EFFECT from 26.2, where CHORUS_FRUIT is @Deprecated. Naming either
        // constant in a switch pins this plugin to one era of the API: compile against the
        // new one and it will not build for 1.21 servers; compile against the old one and it
        // silently stops blocking chorus fruit the release it is removed.
        //
        // A string comparison costs nothing here (this runs on teleports, not on a tick) and
        // it means one jar covers both. Unknown future causes simply are not blocked, which
        // is the safe direction: a teleport we have never heard of is not a known escape.
        String cause = event.getCause().name();
        boolean escape = cause.equals("ENDER_PEARL")
                || cause.equals("CHORUS_FRUIT")        // 1.21.x
                || cause.equals("CONSUMABLE_EFFECT")   // 26.2+
                || cause.equals("COMMAND")
                || cause.equals("PLUGIN");
        if (escape) {
            if (event.getPlayer().hasPermission("skirmish.bypass")) return;
            event.setCancelled(true);
            event.getPlayer().sendActionBar(
                    Component.text("You cannot teleport in combat — " + left + "s")
                            .color(NamedTextColor.RED));
        }
    }
}
