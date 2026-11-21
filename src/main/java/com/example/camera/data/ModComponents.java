package com.example.camera.data;

import com.example.camera.CameraMod;

import net.minecraft.component.ComponentType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

/**
 * Typed data components replacing the opaque {@code CUSTOM_DATA} NBT blobs.
 * Registered before {@code ModItems} so items can reference them.
 */
public final class ModComponents {
    private ModComponents() {
    }

    public static final ComponentType<LensSpec> LENS_SPEC =
            register("lens_spec", ComponentType.<LensSpec>builder().codec(LensSpec.CODEC).build());

    public static final ComponentType<CameraData> CAMERA_DATA =
            register("camera_data", ComponentType.<CameraData>builder().codec(CameraData.CODEC).build());

    private static <T> ComponentType<T> register(String name, ComponentType<T> type) {
        return Registry.register(Registries.DATA_COMPONENT_TYPE, CameraMod.id(name), type);
    }

    public static void register() {
        // Static initializers do the work; this method only pins the order.
    }
}
