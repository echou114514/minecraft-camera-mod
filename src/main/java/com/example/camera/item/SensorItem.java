package com.example.camera.item;

import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class SensorItem extends Item {
    private final boolean fullFrame;

    public SensorItem(Settings settings, boolean fullFrame) {
        super(settings);
        this.fullFrame = fullFrame;
    }

    public boolean isFullFrame() { return fullFrame; }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext ctx, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable(fullFrame
                ? "item.camera-mod.sensor.fullframe" : "item.camera-mod.sensor.halfframe")
                .formatted(fullFrame ? Formatting.GOLD : Formatting.AQUA));
        tooltip.add(Text.translatable("item.camera-mod.sensor.noise_threshold",
                fullFrame ? 3200 : 1600, fullFrame ? 12800 : 6400).formatted(Formatting.GRAY));
    }
}
