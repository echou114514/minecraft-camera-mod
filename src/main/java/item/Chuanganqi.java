package item;

import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class Chuanganqi extends Item {
    private final boolean fullFrame;

    public Chuanganqi(Settings settings, boolean fullFrame) {
        super(settings);
        this.fullFrame = fullFrame;
    }

    public boolean isFullFrame() { return fullFrame; }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext ctx, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.literal(fullFrame ? "全画幅 CMOS" : "半画幅 CMOS")
                .formatted(fullFrame ? Formatting.GOLD : Formatting.AQUA));
        tooltip.add(Text.literal(fullFrame
                ? "噪点阈值: ISO 3200 / 12800"
                : "噪点阈值: ISO 1600 / 6400").formatted(Formatting.GRAY));
    }
}