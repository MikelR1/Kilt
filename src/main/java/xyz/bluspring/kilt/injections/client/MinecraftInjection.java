package xyz.bluspring.kilt.injections.client;

import net.minecraft.client.color.item.ItemColors;
import net.minecraft.client.searchtree.SearchRegistry;
import net.minecraftforge.client.gui.overlay.ForgeGui;

public interface MinecraftInjection {
    default ItemColors getItemColors() {
        throw new IllegalStateException();
    }

    default float getPartialTick() {
        throw new IllegalStateException();
    }

    default SearchRegistry getSearchTreeManager() {
        throw new IllegalStateException();
    }

    default void kilt$setForgeGui(ForgeGui gui) {
        throw new IllegalStateException();
    }
}
