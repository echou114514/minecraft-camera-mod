package com.example.camera;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;

import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;

/**
 * Dedicated "Camera" creative tab so the Lumin bodies, lenses and sensors are
 * actually reachable without /give.
 */
public final class ModItemGroups {
    public static final RegistryKey<ItemGroup> CAMERA =
            RegistryKey.of(RegistryKeys.ITEM_GROUP, CameraMod.id("camera"));

    private ModItemGroups() {
    }

    public static void register() {
        Registry.register(Registries.ITEM_GROUP, CAMERA, FabricItemGroup.builder()
                .displayName(Text.translatable("itemGroup.camera-mod.camera"))
                .icon(() -> new ItemStack(ModItems.LUMIN_F))
                .entries((context, entries) -> {
                    entries.add(ModItems.LUMIN_F);
                    entries.add(ModItems.LUMIN_H);
                    entries.add(ModItems.LENS);
                    entries.add(ModItems.FULL_FRAME_CMOS);
                    entries.add(ModItems.HALF_FRAME_CMOS);
                })
                .build());
    }
}
