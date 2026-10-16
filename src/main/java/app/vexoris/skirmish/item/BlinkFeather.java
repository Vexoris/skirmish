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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The item itself: minting one, recognising one, and performing the move.
 *
 * <h2>Authentication is the marker and nothing else</h2>
 * A Blink Feather is a FEATHER carrying a {@link org.bukkit.persistence.PersistentDataContainer}
 * key. It is never the name, never the lore and never the material alone: an anvil can
 * reproduce a display name and a player can drop a chicken feather, and neither of those may
 * become a working escape tool. Nothing but the marker is consulted.
 */
public final class BlinkFeather {

    /** The material. A feather is light, it is common enough to read as a tool rather than a
     *  trophy, and it already means "falling slowly" to anyone who has played Minecraft. */
    public static final Material MATERIAL = Material.FEATHER;

    private final SkirmishPlugin plugin;
    private final NamespacedKey key;

    /**
     * Who is inside their own blink, right now.
     *
     * <p>THE WINDOW IS EXACTLY THE SYNCHRONOUS TELEPORT CALL in {@link #blink} — added before,
     * removed in a finally — so it cannot leak and cannot cover any other teleport. That
     * matters more than it looks: this set is what stands the combat lock down for the
     * feather, and a leaked entry would be a permanent teleport bypass for that player.
     */
    private final Set<UUID> blinking = ConcurrentHashMap.newKeySet();

    public BlinkFeather(SkirmishPlugin plugin, NamespacedKey key) {
        this.plugin = plugin;
        this.key = key;
    }

    public NamespacedKey key() { return key; }

    /**
     * A freshly minted feather.
     *
     * <p>The lore carries the LIVE range and cooldown rather than two typed numbers, so
     * retuning the config cannot leave a shelf of feathers describing a tool that no longer
     * exists. Only feathers minted after a change say the new numbers, which is the honest
     * half of that: the config is the authority and the item says what it was told on the day
     * it was made.
     */
    public ItemStack create(int amount) {
        ItemStack stack = new ItemStack(MATERIAL, Math.max(1, amount));
        int range = plugin.blinkRange();
        int seconds = Math.max(1, plugin.blinkCooldownTicks() / 20);
        stack.editMeta(meta -> {
            meta.displayName(Component.text("Blink Feather")
                    .color(NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Right-click to step " + range + " blocks.")
                            .color(NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("It will not cross a wall.")
                            .color(NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text(seconds + " second cooldown.")
                            .color(NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
        });
        // THE MARKER GOES ON THROUGH editMeta, NOT ItemStack#editPersistentDataContainer.
        // The latter is a recent Paper addition; this form has existed since 1.14 and behaves
        // identically, so one jar serves 1.21 servers and current ones alike.
        stack.editMeta(meta -> meta.getPersistentDataContainer()
                .set(key, PersistentDataType.BYTE, (byte) 1));
        return stack;
    }

    /** Whether this stack is a real Blink Feather. Marker only — see the class header. */
    public boolean isFeather(ItemStack stack) {
        if (stack == null || stack.getType() != MATERIAL) return false;
        var meta = stack.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** Whether this player's teleport, at this instant, is the feather's own. */
    public boolean blinking(UUID uuid) {
        return uuid != null && blinking.contains(uuid);
    }

    /**
     * Performs the teleport with the combat lock stood down for its duration.
     *
     * @return whether the teleport happened. False means something else cancelled it, and the
     *         caller must NOT spend the cooldown on a move that did not occur.
     */
    public boolean blink(Player player, Location to) {
        if (player == null || to == null) return false;
        UUID uuid = player.getUniqueId();
        blinking.add(uuid);
        try {
            return player.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            blinking.remove(uuid);
        }
    }

    /** A full inventory drops the item rather than destroying it. */
    public static void give(Player player, ItemStack feather) {
        for (ItemStack leftover : player.getInventory().addItem(feather).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }
}
