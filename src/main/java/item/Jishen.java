package item;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

public class Jishen extends Item {
    public Jishen(Settings settings) { super(settings); }

    public static NbtCompound defaultSettings() {
        NbtCompound nbt = new NbtCompound();
        nbt.putInt("iso", 100);
        nbt.putInt("shutter", 125);
        nbt.putFloat("aperture", 5.6f);
        nbt.putInt("wb", 5500);
        nbt.putInt("tone", 0);
        nbt.putFloat("focus", 10.0f);
        return nbt;
    }

    public static NbtCompound getSettings(ItemStack stack) {
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (comp == null) return defaultSettings();
        NbtCompound nbt = comp.copyNbt();
        if (!nbt.contains("Settings")) return defaultSettings();
        return nbt.getCompound("Settings").copy();
    }

    public static void setSettings(ItemStack stack, NbtCompound settings) {
        NbtCompound nbt = new NbtCompound();
        nbt.put("Settings", settings);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
    }
}