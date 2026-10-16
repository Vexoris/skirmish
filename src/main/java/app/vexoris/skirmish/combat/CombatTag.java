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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is in a fight, and for how long.
 *
 * <p>A tag is a wall-clock expiry rather than a countdown task: one map, no scheduler per
 * player, and a lapsed tag costs nothing to notice. The action bar is driven by a single
 * repeating task for everybody who is tagged, which is why {@link #shutdown()} exists.
 */
public final class CombatTag {

    private final SkirmishPlugin plugin;
    private final Map<UUID, Long> expiry = new ConcurrentHashMap<>();
    private int taskId = -1;

    public CombatTag(SkirmishPlugin plugin) {
        this.plugin = plugin;
        this.taskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                plugin, this::tick, 20L, 20L);
    }

    /** Puts this player in combat, or extends the window they are already in. */
    public void tag(Player player) {
        if (player == null) return;
        expiry.put(player.getUniqueId(),
                System.currentTimeMillis() + plugin.combatSeconds() * 1000L);
    }

    /** Seconds remaining, or 0. */
    public int remainingSeconds(UUID uuid) {
        Long until = expiry.get(uuid);
        if (until == null) return 0;
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            expiry.remove(uuid);
            return 0;
        }
        return (int) Math.ceil(left / 1000.0);
    }

    public boolean tagged(UUID uuid) { return remainingSeconds(uuid) > 0; }

    /** Clears a tag outright — death, or an admin. */
    public void clear(UUID uuid) { expiry.remove(uuid); }

    private void tick() {
        if (expiry.isEmpty()) return;
        for (Map.Entry<UUID, Long> e : expiry.entrySet()) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            if (p == null) continue;
            int left = remainingSeconds(e.getKey());
            if (left <= 0) continue;
            // The action bar IS the warning. A player who cannot see the timer experiences a
            // refused teleport as a broken command rather than as a rule.
            p.sendActionBar(Component.text("In combat — " + left + "s")
                    .color(NamedTextColor.RED));
        }
    }

    public void shutdown() {
        if (taskId != -1) plugin.getServer().getScheduler().cancelTask(taskId);
        taskId = -1;
        expiry.clear();
    }
}
