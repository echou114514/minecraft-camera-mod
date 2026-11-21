package com.example.camera.item;

import java.util.List;
import java.util.Optional;

import com.example.camera.ModItems;
import com.example.camera.data.CameraData;
import com.example.camera.data.CameraSettings;
import com.example.camera.data.LensSpec;
import com.example.camera.data.ModComponents;

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
 * is stored in the body's typed data component so the whole camera stays one
 * slot. Sneak-use with an empty offhand removes the lens again.
 */
public class CameraBodyItem extends Item implements GeoItem {
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

    /** GeckoLib keys synced animation data per class; registering twice would silently overwrite. */
    private static boolean syncedAnimatableRegistered = false;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final boolean fullFrame;

    public CameraBodyItem(Settings settings, boolean fullFrame) {
        super(settings);
        this.fullFrame = fullFrame;
        if (!syncedAnimatableRegistered) {
            syncedAnimatableRegistered = true;
            SingletonGeoAnimatable.registerSyncedAnimatable(this);
        }
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
        if (!(stack.getItem() instanceof CameraBodyItem camera)) return;
        camera.getAnimatableInstanceCache()
                .getManagerForId(camera.instanceId())
                .tryTriggerAnimation(ANIM_CONTROLLER, animation);
    }

    // ------------------------------------------------------------------
    // Typed data component + legacy CUSTOM_DATA migration
    // ------------------------------------------------------------------

    public static NbtCompound defaultSettings() {
        return CameraData.DEFAULT.toFlatNbt();
    }

    /**
     * Cameras saved before the EV auto switch existed have no "evAuto" tag.
     * Treat a missing flag as on, so old cameras start with correct exposure.
     */
    public static boolean isEvAuto(NbtCompound settings) {
        return !settings.contains("evAuto") || settings.getBoolean("evAuto");
    }

    private static NbtCompound legacyRoot(ItemStack stack) {
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        return comp == null ? new NbtCompound() : comp.copyNbt();
    }

    /** Reads the typed component, transparently migrating legacy CUSTOM_DATA stacks. */
    public static CameraData getData(ItemStack stack) {
        CameraData data = stack.get(ModComponents.CAMERA_DATA);
        if (data != null) return data;

        NbtCompound root = legacyRoot(stack);
        if (root.isEmpty()) return CameraData.DEFAULT;

        NbtCompound settings = root.contains("Settings")
                ? root.getCompound("Settings").copy()
                : defaultSettings();
        CameraData migrated = CameraData.fromFlatNbt(settings, CameraData.DEFAULT);
        if (root.contains("Assembly")) {
            NbtCompound assembly = root.getCompound("Assembly");
            if (assembly.contains("Lens")) {
                migrated = migrated.withLens(LensSpec.fromNbt(assembly.getCompound("Lens")));
            }
        }
        return migrated;
    }

    private static void setData(ItemStack stack, CameraData data) {
        stack.set(ModComponents.CAMERA_DATA, data);
        // The typed component supersedes the opaque blob; drop it to keep items clean.
        stack.remove(DataComponentTypes.CUSTOM_DATA);
    }

    /** True for any Lumin body (full or half frame). */
    public static boolean isCamera(ItemStack stack) {
        return stack.isOf(ModItems.LUMIN_F) || stack.isOf(ModItems.LUMIN_H);
    }

    public static NbtCompound getSettings(ItemStack stack) {
        return getData(stack).toFlatNbt();
    }

    public static void setSettings(ItemStack stack, NbtCompound settings) {
        // Merge on top of the current data so untouched keys (e.g. hudMode,
        // assembly) survive partial writes.
        CameraData next = CameraData.fromFlatNbt(settings, getData(stack));
        setData(stack, next);
    }

    public static boolean hasLens(ItemStack stack) {
        return getData(stack).hasLens();
    }

    /** The sensor is part of the body, so any Lumin body always has one. */
    public static boolean hasSensor(ItemStack stack) {
        return isCamera(stack);
    }

    public static boolean isReady(ItemStack stack) {
        return isCamera(stack) && hasLens(stack);
    }

    public static LensSpec getLensSpec(ItemStack stack) {
        return getData(stack).lens().orElseGet(LensSpec::base);
    }

    public static void setLensSpec(ItemStack stack, LensSpec spec) {
        setData(stack, getData(stack).withLens(spec));
    }

    public static void removeLens(ItemStack stack) {
        setData(stack, getData(stack).withLens(null));
    }

    public static boolean isFullFrame(ItemStack stack) {
        return stack.getItem() instanceof CameraBodyItem camera ? camera.isFullFrame() : true;
    }

    public static float getSensorHeight(ItemStack stack) {
        return isFullFrame(stack) ? 24.0f : 16.0f;
    }

    public static void installLens(ItemStack camera, ItemStack lens) {
        setLensSpec(camera, LensItem.getSpec(lens).copy());
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
                // Broadcast the animation so other players see the install too.
                triggerAnim(user, instanceId(), ANIM_CONTROLLER, action);
                user.sendMessage(Text.translatable("message.camera-mod.lens_installed",
                        lensLabel(getLensSpec(camera))).formatted(Formatting.GREEN), true);
            }
            return TypedActionResult.success(camera);
        }

        if (user.isSneaking() && hasLens(camera)) {
            if (world.isClient()) {
                return TypedActionResult.success(camera);
            }
            // Remove the lens and hand it back with its full spec preserved.
            LensSpec spec = getLensSpec(camera);
            ItemStack lens = new ItemStack(ModItems.LENS);
            LensItem.setSpec(lens, spec);
            removeLens(camera);
            user.getInventory().offerOrDrop(lens);
            user.sendMessage(Text.translatable("message.camera-mod.lens_removed")
                    .formatted(Formatting.YELLOW), true);
            return TypedActionResult.success(camera);
        }

        if (world.isClient() && !isReady(camera)) {
            user.sendMessage(Text.translatable("message.camera-mod.install_hint")
                    .formatted(Formatting.YELLOW), true);
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
        tooltip.add(Text.translatable(full
                        ? "item.camera-mod.body.fullframe" : "item.camera-mod.body.halfframe")
                .formatted(full ? Formatting.GOLD : Formatting.AQUA));

        if (hasLens(stack)) {
            LensSpec spec = getLensSpec(stack);
            String focal = spec.isZoom
                    ? spec.baseFocalLength + "-" + spec.maxFocalLength + "mm"
                    : spec.focalLength + "mm";
            tooltip.add(Text.translatable("item.camera-mod.body.lens_installed", focal
                    + "  f/" + spec.maxAperture).formatted(Formatting.GRAY));
        } else {
            tooltip.add(Text.translatable("item.camera-mod.body.lens_none")
                    .formatted(Formatting.DARK_GRAY));
        }

        if (isReady(stack)) {
            tooltip.add(Text.translatable("item.camera-mod.body.open_panel_hint")
                    .formatted(Formatting.GREEN));
            tooltip.add(Text.translatable("item.camera-mod.body.remove_hint")
                    .formatted(Formatting.DARK_GRAY));
        } else {
            tooltip.add(Text.translatable("item.camera-mod.body.install_hint")
                    .formatted(Formatting.YELLOW));
        }
    }
}
