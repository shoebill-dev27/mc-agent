package dev.shinobu.mcagent.gui;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * A chest screen used as a dialog: the items are buttons, and nothing in it can
 * be picked up, moved, dropped or shift-clicked away.
 *
 * <p>{@link #clicked} deliberately never calls {@code super}. Vanilla's click
 * handling exists to move items between the container and the player, and any
 * of it running here would either hand the player a decorative glass pane or
 * lose it out of their inventory. Handling the click ourselves and then
 * resending the whole screen means the client's optimistic prediction is
 * corrected immediately, so a button never looks like it was picked up.
 *
 * <p>The player's own inventory slots are inert for the same reason. Being
 * unable to tidy your bag while a dialog is open is a small price for a screen
 * that provably cannot duplicate or eat an item.
 */
public abstract class ReadOnlyChestMenu extends ChestMenu {

    private final SimpleContainer buttons;
    private final int rows;

    protected ReadOnlyChestMenu(int containerId, Inventory inventory, int rows) {
        super(menuTypeFor(rows), containerId, inventory, new SimpleContainer(rows * 9), rows);
        this.rows = rows;
        this.buttons = (SimpleContainer) getContainer();
    }

    private static MenuType<ChestMenu> menuTypeFor(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    /** Slots in the dialog itself, excluding the player's inventory. */
    protected final int size() {
        return rows * 9;
    }

    protected final void put(int slot, ItemStack stack) {
        if (slot >= 0 && slot < size()) {
            buttons.setItem(slot, stack);
        }
    }

    protected final void fillEmpty() {
        for (int slot = 0; slot < size(); slot++) {
            if (buttons.getItem(slot).isEmpty()) {
                buttons.setItem(slot, Icons.filler());
            }
        }
    }

    protected final void clearAll() {
        for (int slot = 0; slot < size(); slot++) {
            buttons.setItem(slot, ItemStack.EMPTY);
        }
    }

    /** Pushes a rebuilt layout out to the client. */
    protected final void redraw() {
        sendAllDataToRemote();
    }

    /**
     * A button was pressed.
     *
     * @param slot   index into the dialog, never the player's inventory
     * @param button 0 for left click, 1 for right
     */
    protected abstract void onButton(ServerPlayer player, int slot, int button);

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (slotId >= 0 && slotId < size()) {
            onButton(serverPlayer, slotId, button);
        }
        // Whatever was clicked, the client has already moved something on its
        // own screen. Put it back - unless the button closed the screen, in
        // which case there is nothing left to correct.
        if (serverPlayer.containerMenu == this) {
            sendAllDataToRemote();
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /**
     * True for good. The usual check is "are you still next to the block this
     * came from", and there is no block: these dialogs are opened by a command
     * or by poking an avatar, and they close when the player closes them.
     */
    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
