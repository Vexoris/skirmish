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
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * What counts as being in a fight.
 *
 * <p>MONITOR and ignoreCancelled, so a hit some other plugin already refused never tags
 * anybody. Both sides of an exchange are tagged: tagging only the victim would let an
 * attacker burst somebody and leave, which is the escape this exists to close.
 */
public final class CombatTagListener implements Listener {

    private final SkirmishPlugin plugin;
    private final CombatTag combat;

    public CombatTagListener(SkirmishPlugin plugin, CombatTag combat) {
        this.plugin = plugin;
        this.combat = combat;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolve(event.getDamager());
        if (attacker == null) {
            // A MOB HIT THEM. Off by default and it should stay off on most servers: in a
            // world full of hostile mobs, tagging on every zombie is a permanent combat lock,
            // which is a worse bug than the one it fixes. Servers running a boss or a raid
            // event turn it on deliberately.
            if (plugin.mobsTag()) combat.tag(victim);
            return;
        }
        if (attacker.equals(victim)) return;
        combat.tag(attacker);
        combat.tag(victim);
    }

    /** The player behind a hit: the swing itself, or whoever loosed the projectile. */
    private Player resolve(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) return p;
        }
        return null;
    }

    /** Death ends the fight. Leaving a tag on a corpse would follow them past the respawn. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        combat.clear(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // NOTE: this only drops the action-bar bookkeeping. Punishing a combat-logger is a
        // separate decision and deliberately not made here -- see the README.
        combat.clear(event.getPlayer().getUniqueId());
    }
}
