package com.example.camera.item;

import java.util.List;

import com.example.camera.data.LensSpec;
import com.example.camera.data.ModComponents;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class LensItem extends Item {
    public LensItem(Settings settings) { super(settings); }

    public static LensSpec getSpec(ItemStack stack) {
        LensSpec spec = stack.get(ModComponents.LENS_SPEC);
        if (spec != null) return spec;

        // Legacy stacks carry the spec inside CUSTOM_DATA.
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (comp == null) return LensSpec.base();
        NbtCompound nbt = comp.copyNbt();
        if (!nbt.contains("LensSpec")) return LensSpec.base();
        return LensSpec.fromNbt(nbt.getCompound("LensSpec"));
    }

    public static void setSpec(ItemStack stack, LensSpec spec) {
        stack.set(ModComponents.LENS_SPEC, spec.sanitize());
        stack.remove(DataComponentTypes.CUSTOM_DATA);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext ctx, List<Text> tooltip, TooltipType type) {
        LensSpec s = getSpec(stack);

        // 变焦头显示永远是 base-max，绝对不跟着滚轮变！
        String focal = s.isZoom
                ? s.baseFocalLength + "-" + s.maxFocalLength + "mm"
                : s.focalLength + "mm";

        tooltip.add(Text.translatable("item.camera-mod.lens.focal", focal).formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("item.camera-mod.lens.max_aperture",
                String.format("%.1f", s.maxAperture)).formatted(Formatting.GRAY));

        if (s.isZoom) {
            // 变焦比也永远基于 baseFocalLength 计算
            float ratio = (float) s.maxFocalLength / s.baseFocalLength;
            tooltip.add(Text.translatable("item.camera-mod.lens.zoom_ratio",
                    String.format("%.2f", ratio)).formatted(Formatting.GRAY));
        }

        if (s.autoFocus) {
            tooltip.add(Text.translatable("item.camera-mod.lens.auto_focus")
                    .formatted(Formatting.GREEN));
        }
    }
}
