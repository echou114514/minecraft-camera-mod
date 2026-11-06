package item;

import java.util.List;

import com.example.camera.ModItems;

import data.LensSpec;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.SingletonGeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * A Lumin camera body. The sensor is built into the body (Lumin F is
 * full-frame, Lumin H is half-frame); only the lens is user-swappable and it
 * is stored in the stack's custom data so the whole camera stays one slot.
 */
public class Jishen extends Item implements GeoItem {
    public static final String ANIM_CONTROLLER = "main";
    public static final String ANIM_SHUTTER = "shutter";
    public static final String ANIM_INSTALL_LENS = "install_lens";
    public static final String ANIM_INSTALL_SENSOR = "install_sensor";

    /**
     * Items are singletons; GeckoLib normally keys animation data by a
     * per-stack id component. Fixed per-model ids keep the renderer and the
     * server-triggered animations in sync even for command-made stacks, while
     * still letting the two bodies animate independently.
     */
    public static final long INSTANCE_ID_F = 0x1CA3E4A11L;
    public static final long INSTANCE_ID_H = 0x1CA3E4A12L;

    private static final RawAnimation SHUTTER_ANIM = RawAnimation.begin().thenPlay(ANIM_SHUTTER);
    private static final RawAnimation INSTALL_LENS_ANIM = RawAnimation.begin().thenPlay(ANIM_INSTALL_LENS);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final boolean fullFrame;

    public Jishen(Settings settings, boolean fullFrame) {
        super(settings);
        this.fullFrame = fullFrame;
        SingletonGeoAnimatable.registerSyncedAnimatable(this);
    }

    /** Full-frame bodies are Lumin F, half-frame (APS-C) bodies are Lumin H. */
    public boolean isFullFrame() {
        return fullFrame;
    }

    public long instanceId() {
        return fullFrame ? INSTANCE_ID_F : INSTANCE_ID_H;
    }

    // ------------------------------------------------------------------
    // GeckoLib
    // ------------------------------------------------------------------

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, ANIM_CONTROLLER, 0, state -> PlayState.STOP)
                .triggerableAnim(ANIM_SHUTTER, SHUTTER_ANIM)
                .triggerableAnim(ANIM_INSTALL_LENS, INSTALL_LENS_ANIM));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    /** Plays a triggerable animation locally (used for the shutter key). */
    public static void playAnimation(ItemStack stack, String animation) {
        if (!(stack.getItem() instanceof Jishen camera)) return;
        camera.getAnimatableInstanceCache()
                .getManagerForId(camera.instanceId())
                .tryTriggerAnimation(ANIM_CONTROLLER, animation);
    }

    // ------------------------------------------------------------------
    // NBT helpers
    // ------------------------------------------------------------------

    public static NbtCompound defaultSettings() {
        NbtCompound nbt = new NbtCompound();
        nbt.putInt("iso", 100);
        nbt.putInt("shutter", 125);
        nbt.putFloat("aperture", 5.6f);
        nbt.putInt("wb", 5500);
        nbt.putInt("tone", 0);
        nbt.putFloat("focus", 10.0f);
        nbt.putBoolean("evAuto", true);
        return nbt;
    }

    /**
     * Cameras saved before the EV auto switch existed have no "evAuto" tag.
     * Treat a missing flag as on, so old cameras start with correct exposure.
     */
    public static boolean isEvAuto(NbtCompound settings) {
        return !settings.contains("evAuto") || settings.getBoolean("evAuto");
    }

    private static NbtCompound rootCopy(ItemStack stack) {
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        return comp == null ? new NbtCompound() : comp.copyNbt();
    }

    private static void writeRoot(ItemStack stack, NbtCompound root) {
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
    }

    /** True for any Lumin body (full or half frame). */
    public static boolean isCamera(ItemStack stack) {
        return stack.isOf(ModItems.LUMIN_F) || stack.isOf(ModItems.LUMIN_H);
    }

    public static NbtCompound getSettings(ItemStack stack) {
        NbtCompound root = rootCopy(stack);
        if (!root.contains("Settings")) return defaultSettings();
        return root.getCompound("Settings").copy();
    }

    public static void setSettings(ItemStack stack, NbtCompound settings) {
        // Preserve the assembly data; settings are changed far more often.
        NbtCompound root = rootCopy(stack);
        root.put("Settings", settings);
        writeRoot(stack, root);
    }

    public static boolean hasLens(ItemStack stack) {
        NbtCompound root = rootCopy(stack);
        return root.contains("Assembly") && root.getCompound("Assembly").contains("Lens");
    }

    /** The sensor is part of the body, so any Lumin body always has one. */
    public static boolean hasSensor(ItemStack stack) {
        return isCamera(stack);
    }

    public static boolean isReady(ItemStack stack) {
        return isCamera(stack) && hasLens(stack);
    }

    public static LensSpec getLensSpec(ItemStack stack) {
        NbtCompound root = rootCopy(stack);
        if (root.contains("Assembly")) {
            NbtCompound assembly = root.getCompound("Assembly");
            if (assembly.contains("Lens")) {
                return LensSpec.fromNbt(assembly.getCompound("Lens"));
            }
        }
        return LensSpec.base();
    }

    public static void setLensSpec(ItemStack stack, LensSpec spec) {
        NbtCompound root = rootCopy(stack);
        NbtCompound assembly = root.contains("Assembly")
                ? root.getCompound("Assembly").copy()
                : new NbtCompound();
        assembly.put("Lens", spec.toNbt());
        root.put("Assembly", assembly);
        writeRoot(stack, root);
    }

    public static boolean isFullFrame(ItemStack stack) {
        return stack.getItem() instanceof Jishen camera ? camera.isFullFrame() : true;
    }

    public static float getSensorHeight(ItemStack stack) {
        return isFullFrame(stack) ? 24.0f : 16.0f;
    }

    public static void installLens(ItemStack camera, ItemStack lens) {
        setLensSpec(camera, Jingtou.getSpec(lens).copy());
    }

    /** Returns the animation to play when {@code offhand} can be installed, or null. */
    public static String installAction(ItemStack camera, ItemStack offhand) {
        if (offhand.isEmpty()) return null;
        if (offhand.isOf(ModItems.LENS) && !hasLens(camera)) return ANIM_INSTALL_LENS;
        return null;
    }

    // ------------------------------------------------------------------
    // Use / assembly
    // ------------------------------------------------------------------

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack camera = user.getStackInHand(hand);
        if (hand != Hand.MAIN_HAND) return TypedActionResult.pass(camera);

        ItemStack offhand = user.getOffHandStack();
        String action = installAction(camera, offhand);

        if (action != null) {
            if (world.isClient()) {
                // Play the install animation locally right away. The old code
                // only triggered it through GeckoLib's server->client packet,
                // which made the animation show up one interaction late.
                playAnimation(camera, action);
            } else {
                installLens(camera, offhand);
                offhand.decrement(1);
                user.sendMessage(Text.literal("§a[相机] 已安装镜头："
                        + lensLabel(Jingtou.getSpec(camera))), true);
            }
            return TypedActionResult.success(camera);
        }

        if (world.isClient() && !isReady(camera)) {
            user.sendMessage(Text.literal("§e[相机] 副手拿镜头，右键安装"), true);
        }
        return TypedActionResult.pass(camera);
    }

    private static String lensLabel(LensSpec spec) {
        return spec.isZoom
                ? spec.baseFocalLength + "-" + spec.maxFocalLength + "mm f/" + spec.maxAperture
                : spec.focalLength + "mm f/" + spec.maxAperture;
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        boolean full = isFullFrame(stack);
        tooltip.add(Text.literal("Lumin " + (full ? "F · 全画幅" : "H · 半画幅"))
                .formatted(full ? Formatting.GOLD : Formatting.AQUA));

        if (hasLens(stack)) {
            LensSpec spec = getLensSpec(stack);
            String focal = spec.isZoom
                    ? spec.baseFocalLength + "-" + spec.maxFocalLength + "mm"
                    : spec.focalLength + "mm";
            tooltip.add(Text.literal("镜头: " + focal + "  f/" + spec.maxAperture)
                    .formatted(Formatting.GRAY));
        } else {
            tooltip.add(Text.literal("镜头: 未安装").formatted(Formatting.DARK_GRAY));
        }

        if (isReady(stack)) {
            tooltip.add(Text.literal("右键打开参数面板").formatted(Formatting.GREEN));
        } else {
            tooltip.add(Text.literal("副手拿镜头右键安装").formatted(Formatting.YELLOW));
        }
    }
}
