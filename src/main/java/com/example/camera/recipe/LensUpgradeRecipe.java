package com.example.camera.recipe;

import com.example.camera.ModItems;
import com.example.camera.ModRecipes;
import com.example.camera.data.LensSpec;
import com.example.camera.item.LensItem;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.RecipeSerializer;
import net.minecraft.recipe.SpecialCraftingRecipe;
import net.minecraft.recipe.book.CraftingRecipeCategory;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.World;

public class LensUpgradeRecipe extends SpecialCraftingRecipe {
    /** Material caps so one crafting grid cannot skip a dozen stops at once. */
    private static final int MAX_GLASS = 4;
    private static final int MAX_REDSTONE = 3;
    private static final int MAX_IRON_INGOT = 4;
    private static final int MAX_IRON_BLOCK = 2;

    public LensUpgradeRecipe(CraftingRecipeCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingRecipeInput input, World world) {
        var stacks = input.getStacks();
        ItemStack lens = ItemStack.EMPTY;
        int glass = 0, redstone = 0, ironIngot = 0, ironBlock = 0;

        for (int i = 0; i < stacks.size(); i++) {
            ItemStack s = stacks.get(i);
            if (s.isEmpty()) continue;
            if (s.isOf(ModItems.LENS)) {
                if (!lens.isEmpty()) return false;
                lens = s;
            } else if (s.isOf(Items.GLASS)) glass += s.getCount();
            else if (s.isOf(Items.REDSTONE)) redstone += s.getCount();
            else if (s.isOf(Items.IRON_INGOT)) ironIngot += s.getCount();
            else if (s.isOf(Items.IRON_BLOCK)) ironBlock += s.getCount();
            else return false;
        }

        if (lens.isEmpty()) return false;
        if (glass == 0 && redstone == 0 && ironIngot == 0 && ironBlock == 0) return false;
        return glass <= MAX_GLASS && redstone <= MAX_REDSTONE
                && ironIngot <= MAX_IRON_INGOT && ironBlock <= MAX_IRON_BLOCK;
    }

    @Override
    public ItemStack craft(CraftingRecipeInput input, RegistryWrapper.WrapperLookup lookup) {
        var stacks = input.getStacks();
        ItemStack lens = ItemStack.EMPTY;
        int glass = 0, redstone = 0, ironIngot = 0, ironBlock = 0;

        for (int i = 0; i < stacks.size(); i++) {
            ItemStack s = stacks.get(i);
            if (s.isEmpty()) continue;
            if (s.isOf(ModItems.LENS)) lens = s;
            else if (s.isOf(Items.GLASS)) glass += s.getCount();
            else if (s.isOf(Items.REDSTONE)) redstone += s.getCount();
            else if (s.isOf(Items.IRON_INGOT)) ironIngot += s.getCount();
            else if (s.isOf(Items.IRON_BLOCK)) ironBlock += s.getCount();
        }

        if (lens.isEmpty()) return ItemStack.EMPTY;

        LensSpec spec = LensItem.getSpec(lens).copy();

        // 焦距处理
        if (glass > 0) {
            if (spec.isZoom) {
                // 变焦头：扩展长焦端
                spec.maxFocalLength = LensSpec.nextFocal(spec.maxFocalLength, glass);
                int cap = (int) (spec.baseFocalLength * 4.5f);
                if (spec.maxFocalLength > cap) {
                    spec.maxFocalLength = LensSpec.maxFocalWithin(spec.baseFocalLength, 4.5f);
                }
            } else {
                // 定焦头：焦距跳档，广角端同步
                spec.focalLength = LensSpec.nextFocal(spec.focalLength, glass);
                spec.maxFocalLength = spec.focalLength;
                spec.baseFocalLength = spec.focalLength;
            }
        }

        // 红石：1 个解锁自动对焦，2 个及以上同时解锁自动对焦 + 变焦。
        if (redstone >= 1) {
            spec.autoFocus = true;
        }
        if (redstone >= 2) {
            if (!spec.isZoom) {
                // 定焦变焦：广角端就是此刻的定焦焦距！
                spec.isZoom = true;
                spec.baseFocalLength = spec.focalLength;
            }

            // 更新上限：保留已升级出的长焦端，绝不能抹回广角端。
            spec.maxFocalLength = Math.max(spec.maxFocalLength, spec.focalLength);
            int cap = (int) (spec.baseFocalLength * 4.5f);
            if (spec.maxFocalLength > cap) {
                spec.maxFocalLength = LensSpec.maxFocalWithin(spec.baseFocalLength, 4.5f);
            }
        }

        // 光圈处理
        float apertureDelta = ironIngot * 0.1f + ironBlock * 1.0f;
        if (apertureDelta > 0) {
            spec.maxAperture = LensSpec.snapAperture(spec.maxAperture - apertureDelta);
        }

        float limit = LensSpec.apertureLimit(spec.baseFocalLength, spec.isZoom);
        if (spec.maxAperture < limit) {
            spec.maxAperture = limit;
        }

        // Copy the original stack so custom names and other components survive.
        ItemStack out = lens.copy();
        out.setCount(1);
        LensItem.setSpec(out, spec);
        return out;
    }

    @Override
    public boolean fits(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.LENS_UPGRADE_SERIALIZER;
    }
}
