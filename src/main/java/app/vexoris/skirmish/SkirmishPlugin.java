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
package app.vexoris.skirmish;

import app.vexoris.skirmish.combat.CombatTag;
import app.vexoris.skirmish.combat.CombatTagListener;
import app.vexoris.skirmish.combat.TeleportGateListener;
import app.vexoris.skirmish.item.BlinkFeather;
import app.vexoris.skirmish.item.BlinkFeatherListener;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Skirmish — an escape tool and a combat lock, for servers where loot can be taken.
 *
 * <h2>What this is</h2>
 * Two mechanics that only make sense together:
 * <ul>
 *   <li><b>The Blink Feather.</b> Right-click and step up to twenty blocks along your line of
 *       sight, on a cooldown. It cannot pass through a wall, it refuses rather than dropping
 *       you into a ravine, and a refusal costs you nothing.</li>
 *   <li><b>The combat lock.</b> While you are in a fight you cannot pearl, chorus or /home
 *       away. The feather is deliberately exempt — it is an evade, not an exit.</li>
 * </ul>
 * The pairing is the product. A combat lock on its own is a punishment; an escape tool on its
 * own is a way to never be caught. Together they say: you may not LEAVE a fight, but you may
 * always try to survive one.
 *
 * <h2>Design notes worth reading before you retune anything</h2>
 * <ul>
 *   <li><b>Safety is geometric, not permission-based.</b> The blink ray stops at the first
 *       block a body cannot occupy, so it can never cross a wall into a base, a vault or a
 *       locked room — whoever owns it, and whether or not any protection plugin is installed.
 *       That property is in {@link app.vexoris.skirmish.item.BlinkPlan} and it is proven by
 *       its own test suite, not asserted here.</li>
 *   <li><b>A failed activation is free.</b> If there is nowhere safe to land, the tool refuses
 *       and does NOT spend its cooldown. A tool that eats its cooldown on a failure is worse
 *       than no tool, because it fails at the exact moment somebody needed it.</li>
 *   <li><b>Both ends draw a puff.</b> This is a fairness decision, not decoration. A pursuer
 *       has to be able to follow. An escape nobody can track is a teleport, not a chase.</li>
 * </ul>
 */
public final class SkirmishPlugin extends JavaPlugin {

    private BlinkFeather feather;
    private CombatTag combatTag;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        NamespacedKey key = new NamespacedKey(this, "blink_feather");
        this.feather = new BlinkFeather(this, key);
        this.combatTag = new CombatTag(this);

        getServer().getPluginManager().registerEvents(
                new BlinkFeatherListener(this, feather, combatTag), this);
        getServer().getPluginManager().registerEvents(
                new CombatTagListener(this, combatTag), this);
        getServer().getPluginManager().registerEvents(
                new TeleportGateListener(this, combatTag, feather), this);

        getLogger().info("Skirmish enabled. Blink range " + blinkRange()
                + ", cooldown " + (blinkCooldownTicks() / 20) + "s, combat lock "
                + combatSeconds() + "s.");
    }

    @Override
    public void onDisable() {
        if (combatTag != null) combatTag.shutdown();
    }

    // ---- configuration, read live so /skirmish reload is honest ----------------------------
    // Every getter clamps. A config is user input, and a zero cooldown is not "responsive",
    // it is sustained flight at twenty blocks a tick.

    /** How far a blink may reach, in blocks. Clamped 3..64. */
    public int blinkRange() {
        return Math.max(3, Math.min(64, getConfig().getInt("blink.range", 20)));
    }

    /** Cooldown in ticks. Floored at 20 (one second) for the reason above. */
    public int blinkCooldownTicks() {
        return Math.max(20, Math.min(24_000, getConfig().getInt("blink.cooldown-ticks", 100)));
    }

    /** How long a hit keeps you in combat. Clamped 3..120 seconds. */
    public int combatSeconds() {
        return Math.max(3, Math.min(120, getConfig().getInt("combat.seconds", 15)));
    }

    /** Whether the combat lock refuses teleports at all. */
    public boolean combatLockEnabled() {
        return getConfig().getBoolean("combat.block-teleports", true);
    }

    /** Whether mobs tag you, or only players. Default false: PvP is the case this is for. */
    public boolean mobsTag() {
        return getConfig().getBoolean("combat.mobs-tag-you", false);
    }

    public BlinkFeather feather() { return feather; }

    public CombatTag combatTag() { return combatTag; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("skirmish.admin")) {
                sender.sendMessage("You do not have permission.");
                return true;
            }
            reloadConfig();
            sender.sendMessage("Skirmish config reloaded.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("skirmish.admin")) {
                sender.sendMessage("You do not have permission.");
                return true;
            }
            Player target = args.length > 1
                    ? getServer().getPlayerExact(args[1])
                    : (sender instanceof Player p ? p : null);
            if (target == null) {
                sender.sendMessage("Name a player, or run it as one.");
                return true;
            }
            BlinkFeather.give(target, feather.create(1));
            sender.sendMessage("Gave a Blink Feather to " + target.getName() + ".");
            return true;
        }
        sender.sendMessage("/skirmish give [player]  |  /skirmish reload");
        return true;
    }
}
