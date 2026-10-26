package item;

import java.util.List;

import data.LensSpec;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class Jingtou extends Item {
    public Jingtou(Settings settings) { super(settings); }

    public static LensSpec getSpec(ItemStack stack) {
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (comp == null) return LensSpec.base();
        NbtCompound nbt = comp.copyNbt();
        if (!nbt.contains("LensSpec")) return LensSpec.base();
        return LensSpec.fromNbt(nbt.getCompound("LensSpec"));
    }

    public static void setSpec(ItemStack stack, LensSpec spec) {
        NbtCompound nbt = new NbtCompound();
        nbt.put("LensSpec", spec.toNbt());
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext ctx, List<Text> tooltip, TooltipType type) {
        LensSpec s = getSpec(stack);

        // 【修复】：变焦头显示永远是 base-max，绝对不跟着滚轮变！
        String focal = s.isZoom
                ? s.baseFocalLength + "-" + s.maxFocalLength + "mm"
                : s.focalLength + "mm";

        tooltip.add(Text.literal("焦距: " + focal).formatted(Formatting.GRAY));
        tooltip.add(Text.literal("最大光圈: f/" + s.maxAperture).formatted(Formatting.GRAY));

        if (s.isZoom) {
            // 变焦比也永远基于 baseFocalLength 计算
            float ratio = (float) s.maxFocalLength / s.baseFocalLength;
            tooltip.add(Text.literal("变焦比: " + String.format("%.2f", ratio) + "x").formatted(Formatting.GRAY));
        }

        if (s.autoFocus) tooltip.add(Text.literal("自动对焦").formatted(Formatting.GREEN));
    }
}