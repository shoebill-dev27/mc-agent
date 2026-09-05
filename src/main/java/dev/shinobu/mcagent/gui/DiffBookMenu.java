package dev.shinobu.mcagent.gui;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * Shows a written book without giving anyone the book.
 *
 * <p>A lectern is the one thing in the game that puts a book on screen from the
 * server alone: the client already knows how to draw {@link LecternMenu}, pages
 * and all, and it works on a vanilla client with no mod installed. The
 * alternative — briefly swapping a real book into the player's hand and sending
 * the open-book packet — risks leaving a stray item behind if anything goes
 * wrong in between, which is not a trade worth making for a dialog.
 *
 * <p>There is no lectern block and no book to take, so "Take Book" is refused
 * and the slot is inert.
 */
public final class DiffBookMenu extends LecternMenu {

    public DiffBookMenu(int containerId, ItemStack book) {
        super(containerId, new SimpleContainer(book), new SimpleContainerData(1));
    }

    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (button == LecternMenu.BUTTON_TAKE_BOOK) {
            // The book is a rendering of a permission request, not an item.
            return false;
        }
        return super.clickMenuButton(player, button);
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
