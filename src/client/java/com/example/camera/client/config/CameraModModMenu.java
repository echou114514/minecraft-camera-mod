package com.example.camera.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Optional Mod Menu entrypoint. Loaded only when Mod Menu is installed;
 * harmless otherwise.
 */
public class CameraModModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return CameraModConfigScreen::new;
    }
}
