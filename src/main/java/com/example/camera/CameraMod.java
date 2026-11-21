package com.example.camera;

import com.example.camera.data.ModComponents;

import net.fabricmc.api.ModInitializer;

import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CameraMod implements ModInitializer {
	public static final String MOD_ID = "camera-mod";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Components must exist before items with default components load.
		ModComponents.register();
		ModItems.register();
		ModItemGroups.register();
		ModRecipes.register();
		LOGGER.info("[CameraMod] loaded");
	}

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}
}
