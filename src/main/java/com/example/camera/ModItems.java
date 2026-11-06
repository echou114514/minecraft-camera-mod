package com.example.camera;

import item.Chuanganqi;
import item.Jingtou;
import item.Jishen;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public class ModItems {
    public static final Item HALF_FRAME_CMOS = new Chuanganqi(new Item.Settings().maxCount(16), false);
    public static final Item FULL_FRAME_CMOS = new Chuanganqi(new Item.Settings().maxCount(16), true);
    // Lumin F = full-frame body, Lumin H = half-frame (APS-C) body.
    public static final Item LUMIN_F = new Jishen(new Item.Settings().maxCount(1), true);
    public static final Item LUMIN_H = new Jishen(new Item.Settings().maxCount(1), false);
    public static final Item LENS    = new Jingtou(new Item.Settings().maxCount(1));

    public static void register() {
        reg("half_frame_cmos", HALF_FRAME_CMOS);
        reg("full_frame_cmos", FULL_FRAME_CMOS);
        reg("lumin_f", LUMIN_F);
        reg("lumin_h", LUMIN_H);
        reg("lens", LENS);
    }

    private static void reg(String name, Item item) {
        Registry.register(Registries.ITEM, CameraMod.id(name), item);
    }
}
