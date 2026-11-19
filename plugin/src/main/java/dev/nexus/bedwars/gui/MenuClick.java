package dev.nexus.bedwars.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/** Context handed to every button action. Always executes on the clicking player's entity thread. */
public record MenuClick(Player player, ClickType type, int slot, Menu menu) {

    public boolean left() { return type == ClickType.LEFT; }
    public boolean right() { return type == ClickType.RIGHT; }
    public boolean shiftLeft() { return type == ClickType.SHIFT_LEFT; }
    public boolean shiftRight() { return type == ClickType.SHIFT_RIGHT; }
    public boolean shift() { return type.isShiftClick(); }
}
