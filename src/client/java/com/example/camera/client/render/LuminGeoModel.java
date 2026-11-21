package com.example.camera.client.render;

import com.example.camera.CameraMod;

import com.example.camera.item.CameraBodyItem;

import net.minecraft.util.Identifier;

import software.bernie.geckolib.model.DefaultedItemGeoModel;

/**
 * Shared geometry/animation for both Lumin bodies; only the texture differs
 * between the full-frame (Lumin F) and half-frame (Lumin H) bodies.
 */
public class LuminGeoModel extends DefaultedItemGeoModel<CameraBodyItem> {

    public LuminGeoModel() {
        super(CameraMod.id("lumin"));
    }

    @Override
    public Identifier getTextureResource(CameraBodyItem animatable) {
        return CameraMod.id("textures/item/" + (animatable.isFullFrame() ? "lumin_f" : "lumin_h") + ".png");
    }
}
