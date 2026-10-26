package com.example.camera;

import net.minecraft.recipe.RecipeSerializer;
import net.minecraft.recipe.SpecialRecipeSerializer;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import recipe.LensUpgradeRecipe;

public class ModRecipes {
    public static final RecipeSerializer<LensUpgradeRecipe> LENS_UPGRADE_SERIALIZER =
            new SpecialRecipeSerializer<>(LensUpgradeRecipe::new);

    public static void register() {
        Registry.register(Registries.RECIPE_SERIALIZER,
                CameraMod.id("lens_upgrade"),
                LENS_UPGRADE_SERIALIZER);
    }
}