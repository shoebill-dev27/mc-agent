package dev.shinobu.mcagent.gui;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/**
 * Buttons, as items.
 *
 * <p>A chest screen is the only rich UI a vanilla client can be shown from the
 * server, so every control in these dialogs is an item stack with a name and a
 * few lines of lore. Names are forced upright because a custom item name is
 * italic by default, which reads as an off-hand remark rather than a button.
 */
public final class Icons {

    private Icons() {
    }

    public static ItemStack button(ItemLike item, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, upright(name));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>(lore.size());
            for (Component line : lore) {
                lines.add(upright(line));
            }
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }
        return stack;
    }

    public static ItemStack button(ItemLike item, Component name) {
        return button(item, name, List.of());
    }

    /** Fills the slots that are not buttons, so nothing looks droppable. */
    public static ItemStack filler() {
        ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
        stack.set(DataComponents.CUSTOM_NAME, Component.empty());
        return stack;
    }

    private static Component upright(Component text) {
        MutableComponent copy = text.copy();
        return copy.withStyle(style -> style.withItalic(Boolean.FALSE));
    }
}
