package com.example.camera.client;

import com.example.camera.ModItems;
import com.example.camera.client.config.CameraModConfig;
import com.example.camera.client.gui.CameraSettingsScreen;
import com.example.camera.client.hud.CameraHud;
import com.example.camera.client.hud.ClassicHud;
import com.example.camera.client.hud.HudSnapshot;
import com.example.camera.client.metering.Metering;
import com.example.camera.client.render.CameraItemRenderer;
import com.example.camera.data.LensSpec;
import com.example.camera.item.CameraBodyItem;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.LightType;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

public class CameraModClient implements ClientModInitializer {
	public static final KeyBinding shutterKey = registerKey("key.camera-mod.shutter", GLFW.GLFW_KEY_P);
	public static final KeyBinding hudModePrevKey = registerKey("key.camera-mod.hud_mode_prev", GLFW.GLFW_KEY_UP);
	public static final KeyBinding hudModeNextKey = registerKey("key.camera-mod.hud_mode_next", GLFW.GLFW_KEY_DOWN);
	public static final KeyBinding meteringKey = registerKey("key.camera-mod.metering", GLFW.GLFW_KEY_M);
	public static final KeyBinding paramPrevKey = registerKey("key.camera-mod.param_prev", GLFW.GLFW_KEY_LEFT_BRACKET);
	public static final KeyBinding paramNextKey = registerKey("key.camera-mod.param_next", GLFW.GLFW_KEY_RIGHT_BRACKET);
	public static final KeyBinding valueDownKey = registerKey("key.camera-mod.value_down", GLFW.GLFW_KEY_LEFT);
	public static final KeyBinding valueUpKey = registerKey("key.camera-mod.value_up", GLFW.GLFW_KEY_RIGHT);

	private static final int SHUTTER_FLASH_DURATION = 6;
	/** Install animation length in ticks (matches the geo animation). */
	private static final int INSTALL_ANIM_TICKS = 36;

	private static boolean cameraMode = false;
	private static double lastDiff = 0.0;
	private static float evComp = 0f;
	private static boolean evAuto = true;
	private static float totalEvOffset = 0f;
	private static int shutterFlashTicks = 0;

	// ---- HUD (取景器) ----
	/** 0=自动 1=照片 2=人像 3=夜景 4=电影，持久化在相机数据的 hudMode。 */
	private static int hudMode = 0;
	private static int hudParam = 0;
	/** 安装动画期间保持手持模型可见（约 1.8s 动画）。 */
	private static int installAnimTicks = 0;
	private static final HudSnapshot HUD = new HudSnapshot();

	public static int cachedWb = 5500;
	public static int cachedSceneTemp = 5500;
	public static int cachedTone = 0;
	public static int cachedIso = 100;
	public static int cachedShutter = 125;
	public static float cachedAperture = 5.6f;

	private static double smoothSceneEV = 0.0;
	private static boolean smoothSceneEVInit = false;

	private static boolean isFocusing = false;
	private static int focusTicks = 0;
	/** 合焦确认指示（Classic HUD 绿点 + AF 框脉冲），约 1 秒。 */
	private static int afConfirmTicks = 0;
	private static boolean lastLeftDown = false;

	private static KeyBinding registerKey(String id, int defaultKey) {
		return KeyBindingHelper.registerKeyBinding(new KeyBinding(id, InputUtil.Type.KEYSYM, defaultKey, "category.camera-mod"));
	}

	public static boolean isCameraMode() {
		return cameraMode;
	}

	@Override
	public void onInitializeClient() {
		CameraItemRenderer cameraRenderer = new CameraItemRenderer();
		BuiltinItemRendererRegistry.DynamicItemRenderer itemRenderer = cameraRenderer::render;
		BuiltinItemRendererRegistry.INSTANCE.register(ModItems.LUMIN_F, itemRenderer);
		BuiltinItemRendererRegistry.INSTANCE.register(ModItems.LUMIN_H, itemRenderer);

		UseItemCallback.EVENT.register((player, world, hand) -> {
			ItemStack stack = player.getStackInHand(hand);
			if (CameraBodyItem.isCamera(stack)) {
				// Installing takes priority: let the server-side use() write the
				// data component and play the animation.
				if (CameraBodyItem.installAction(stack, player.getOffHandStack()) != null) {
					if (world.isClient()) {
						// Predict the assembly so the lens bone is already visible
						// while the install animation plays; the server confirms it
						// and consumes the offhand lens.
						CameraBodyItem.installLens(stack, player.getOffHandStack());
						installAnimTicks = INSTALL_ANIM_TICKS;
					}
					return TypedActionResult.pass(stack);
				}
				if (world.isClient() && CameraBodyItem.isReady(stack)) {
					CameraSettingsScreen.open(stack);
					return TypedActionResult.success(stack);
				}
			}
			return TypedActionResult.pass(stack);
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			applyLensFov(client);
			tickMetering(client);
			if (shutterFlashTicks > 0) shutterFlashTicks--;
			if (installAnimTicks > 0) installAnimTicks--;
			if (afConfirmTicks > 0) afConfirmTicks--;

			tickHudInput(client);

			// Drain the key buffers every tick unconditionally, otherwise
			// presses made while a screen was open would pile up and fire the
			// moment the screen closes.
			boolean shutterPressed = consume(shutterKey);
			boolean meteringPressed = consume(meteringKey);

			if (meteringPressed && isViewfinderActive()) {
				cycleMeteringMode(client);
			}
			if (shutterPressed) {
				onShutterPressed(client);
			}

			if (isFocusing) {
				focusTicks++;
				if (focusTicks >= 5) {
					isFocusing = false;
					executeCapture(client);
				}
			}

			// Left click = shutter. The edge is detected here; the actual
			// attack is suppressed by the callbacks below while in viewfinder.
			if (cameraMode && client.currentScreen == null && client.player != null
					&& client.getWindow() != null) {
				boolean leftDown = GLFW.glfwGetMouseButton(client.getWindow().getHandle(),
						GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
				if (leftDown && !lastLeftDown) onShutterPressed(client);
				lastLeftDown = leftDown;
			} else {
				lastLeftDown = false;
			}
		});

		// Suppress punching/breaking while the viewfinder is up; left click
		// belongs to the shutter there.
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (world.isClient() && cameraMode && CameraBodyItem.isCamera(player.getMainHandStack())) {
				return ActionResult.FAIL;
			}
			return ActionResult.PASS;
		});
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClient() && cameraMode && CameraBodyItem.isCamera(player.getMainHandStack())) {
				return ActionResult.FAIL;
			}
			return ActionResult.PASS;
		});

		HudRenderCallback.EVENT.register((DrawContext ctx, RenderTickCounter tickCounter) -> {
			// The capture reads the frame after the world but before this callback
			// finishes, so never let camera overlays or noise leak into the photo.
			if (HighResCapture.isWaiting() || HighResCapture.isCapturing()) return;
			if (!cameraMode) return;
			MinecraftClient client = MinecraftClient.getInstance();
			int w = ctx.getScaledWindowWidth();
			int h = ctx.getScaledWindowHeight();

			float previewEv = totalEvOffset;
			if (previewEv > 5f) previewEv = 5f;
			if (previewEv < -5f) previewEv = -5f;
			applyEvComp(w, h, previewEv);
			PostProcess.render(ctx, client);
			PostProcess.renderNoise(ctx, client);

			// 取景器 HUD：快照由 tickMetering 在 tick 级填充，渲染回调只读缓存。
			// Neon（现代无边框）/ Classic（佳能式 OVF）由配置切换，动效两套都保留。
			if (CameraModConfig.get().isClassicUi()) {
				ClassicHud.render(ctx, client, HUD);
			} else {
				CameraHud.render(ctx, client, HUD);
			}

			if (shutterFlashTicks > 0) {
				float t = (float) shutterFlashTicks / SHUTTER_FLASH_DURATION;
				float intensity = t > 0.67f ? (1.0f - t) / 0.33f : t / 0.67f;
				int alpha = (int) (intensity * 220);
				if (alpha > 0) ctx.fill(0, 0, w, h, (alpha << 24));
			}
		});

		// Warm up the vignette texture outside the first viewfinder frame.
		CameraHud.prewarm(MinecraftClient.getInstance());

		// Unattended test run (-Dcameramod.autoTest): drives world creation,
		// HUD screenshots and 8K exports by itself.
		if (com.example.camera.client.autotest.AutoTest.enabled()) {
			com.example.camera.client.autotest.AutoTest.start(net.minecraft.client.MinecraftClient.getInstance());
		}
	}

	/** Aggregates a key's press buffer into a single boolean. */
	private static boolean consume(KeyBinding key) {
		boolean pressed = false;
		while (key.wasPressed()) pressed = true;
		return pressed;
	}

	private static void onShutterPressed(MinecraftClient client) {
		if (isFocusing) return;
		if (client.player == null || client.world == null || !cameraMode) return;
		if (client.currentScreen != null) return;

		ItemStack mainHand = client.player.getMainHandStack();
		LensSpec spec = CameraBodyItem.getLensSpec(mainHand);
		CameraBodyItem.playAnimation(mainHand, CameraBodyItem.ANIM_SHUTTER);

		if (spec.autoFocus) {
			isFocusing = true;
			focusTicks = 0;
			client.player.sendMessage(Text.translatable("message.camera-mod.focusing")
					.formatted(Formatting.GREEN), true);
		} else {
			executeCapture(client);
		}
	}

	public static void executeCapture(MinecraftClient client) {
		if (client.player == null || client.world == null || !cameraMode) return;

		ItemStack mainHand = client.player.getMainHandStack();
		LensSpec spec = CameraBodyItem.getLensSpec(mainHand);
		NbtCompound nbt = CameraBodyItem.getSettings(mainHand);

		if (spec.autoFocus) {
			var start = client.player.getEyePos();
			var dir = client.player.getRotationVec(1.0f);
			var end = start.add(dir.multiply(256.0));
			var hit = client.world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));

			float newFocus = (hit.getType() == HitResult.Type.BLOCK) ? (float) start.distanceTo(hit.getPos()) : Float.POSITIVE_INFINITY;
			float minFocus = Math.max(0.5f, spec.focalLength / 100.0f);
			if (!Float.isInfinite(newFocus) && newFocus < minFocus) newFocus = minFocus;

			nbt.putFloat("focus", newFocus);
			CameraBodyItem.setSettings(mainHand, nbt);

			String fStr = Float.isInfinite(newFocus) ? "∞" : String.format(Locale.US, "%.1fm", newFocus);
			client.player.sendMessage(Text.translatable("message.camera-mod.focused", fStr)
					.formatted(Formatting.GREEN), true);
			afConfirmTicks = 20;
		} else {
			client.player.sendMessage(Text.translatable("message.camera-mod.manual_shot")
					.formatted(Formatting.YELLOW), true);
		}

		HighResCapture.snapshotExposureEV = evAuto ? (float) lastDiff : nbt.getInt("evCompStep") / 3.0f;
		HighResCapture.snapshotMeterEV = (float) lastDiff;
		HighResCapture.snapshotWb = cachedWb;
		HighResCapture.snapshotSceneTemp = cachedSceneTemp;
		HighResCapture.snapshotTone = cachedTone;
		HighResCapture.snapshotIso = cachedIso;
		HighResCapture.snapshotShutter = cachedShutter;
		HighResCapture.snapshotAperture = cachedAperture;
		HighResCapture.snapshotFocusDistance = nbt.getFloat("focus");
		HighResCapture.snapshotFocalLength = spec.focalLength;
		HighResCapture.snapshotSensorHeight = CameraBodyItem.getSensorHeight(mainHand);
		HighResCapture.snapshotFullFrame = CameraBodyItem.isFullFrame(mainHand);

		boolean isFullFrame = CameraBodyItem.isFullFrame(mainHand);
		HighResCapture.snapshotCamName = isFullFrame ? "MC DSLR F" : "MC DSLR H";

		if (spec.isZoom) {
			HighResCapture.snapshotLensName = String.format(Locale.US, "MC Lens %dmm-%dmm f/%.1f", spec.baseFocalLength, spec.maxFocalLength, spec.maxAperture);
		} else {
			HighResCapture.snapshotLensName = String.format(Locale.US, "MC Lens %dmm f/%.1f", spec.focalLength, spec.maxAperture);
		}

		String modeStr = nbt.getString("mode");
		HighResCapture.snapshotExposureProgram = 1;
		HighResCapture.snapshotExposureMode = 1;
		if (modeStr.equals("A_PLUS") || modeStr.equals("P")) {
			HighResCapture.snapshotExposureProgram = 2;
			HighResCapture.snapshotExposureMode = 0;
		} else if (modeStr.equals("TV")) {
			HighResCapture.snapshotExposureProgram = 4;
			HighResCapture.snapshotExposureMode = 0;
		} else if (modeStr.equals("AV")) {
			HighResCapture.snapshotExposureProgram = 3;
			HighResCapture.snapshotExposureMode = 0;
		}

		HighResCapture.snapshotMeteringMode = Metering.currentMode.ordinal() + 1;
		HighResCapture.snapshotWbAuto = nbt.getBoolean("wbAuto");

		long tick = client.world.getTimeOfDay();
		int mcHour = (int)((tick / 1000 + 6) % 24);
		int mcMin = (int)((tick % 1000) * 60 / 1000);
		java.time.LocalDate today = java.time.LocalDate.now();
		HighResCapture.snapshotDateStr = String.format(Locale.US, "%04d:%02d:%02d %02d:%02d:00",
				today.getYear(), today.getMonthValue(), today.getDayOfMonth(), mcHour, mcMin);

		if (!HighResCapture.request(client)) {
			client.player.sendMessage(Text.translatable("message.camera-mod.capture_target_failed")
					.formatted(Formatting.RED), false);
			return;
		}
		// Mechanical shutter click (the mod has no custom sound assets).
		client.player.playSound(SoundEvents.BLOCK_LEVER_CLICK, 1.0f, 1.6f);
		shutterFlashTicks = SHUTTER_FLASH_DURATION;
	}

	private static void applyLensFov(MinecraftClient client) {
		if (client.player == null) {
			// Leaving the world must clear the takeover, otherwise the FOV
			// stays locked after reconnecting or switching dimensions.
			cameraMode = false;
			CameraFovState.clear();
			return;
		}
		ItemStack mainHand = client.player.getMainHandStack();
		boolean wasCamera = cameraMode;
		cameraMode = CameraBodyItem.isReady(mainHand);
			if (cameraMode) {
				LensSpec spec = CameraBodyItem.getLensSpec(mainHand);
				CameraFovState.setCustomFov(computeFov(spec.focalLength));
				if (!wasCamera) {
					NbtCompound settings = CameraBodyItem.getSettings(mainHand);
					hudMode = Math.max(0, Math.min(HudSnapshot.MODE_COUNT - 1, settings.getInt("hudMode")));
					hudParam = 0;
					CameraHud.reset();
					ClassicHud.reset();
				}
			} else {
				if (wasCamera) {
					CameraHud.reset();
					ClassicHud.reset();
				}
				CameraFovState.clear();
			}
	}

	public static boolean isViewfinderActive() {
		MinecraftClient client = MinecraftClient.getInstance();
		return cameraMode && client.currentScreen == null && client.player != null;
	}

	/** 第一人称是否隐藏手持相机模型（安装动画期间仍显示）。 */
	public static boolean isHandHidden() {
		return isViewfinderActive() && installAnimTicks <= 0;
	}

	// ==================== 取景器 HUD ====================

	private static void tickHudInput(MinecraftClient client) {
		// Consume every mode/param/value key press unconditionally so nothing
		// accumulates while a screen is open.
		boolean modePrev = consume(hudModePrevKey);
		boolean modeNext = consume(hudModeNextKey);
		boolean paramPrev = consume(paramPrevKey);
		boolean paramNext = consume(paramNextKey);
		boolean valueDown = consume(valueDownKey);
		boolean valueUp = consume(valueUpKey);

		if (!isViewfinderActive()) return;

		if (modePrev) setHudMode(client, hudMode - 1);
		if (modeNext) setHudMode(client, hudMode + 1);

		ItemStack stack = client.player.getMainHandStack();
		int paramCount = CameraBodyItem.getLensSpec(stack).isZoom ? 6 : 5;
		if (hudParam >= paramCount) hudParam = paramCount - 1;

		if (paramPrev != paramNext) {
			moveHudParam(paramNext ? 1 : -1, paramCount);
		}
		if (valueDown) adjustHudParam(client, -1);
		else if (valueUp) adjustHudParam(client, 1);
	}

	private static void moveHudParam(int dir, int paramCount) {
		hudParam = Math.floorMod(hudParam + dir, Math.max(1, paramCount));
	}

	private static void setHudMode(MinecraftClient client, int index) {
		if (client.player == null) return;
		index = Math.max(0, Math.min(HudSnapshot.MODE_COUNT - 1, index));
		hudMode = index;

		ItemStack stack = client.player.getMainHandStack();
		NbtCompound settings = CameraBodyItem.getSettings(stack);
		LensSpec lens = CameraBodyItem.getLensSpec(stack);
		settings.putInt("hudMode", index);
		switch (index) {
			case 0 -> { // 自动：所有参数交给 AUTO
				settings.putString("mode", "A_PLUS");
				settings.putBoolean("evAuto", true);
				settings.putBoolean("isoAuto", true);
				settings.putBoolean("wbAuto", true);
				settings.putBoolean("toneAuto", true);
			}
			case 1 -> { // 照片：标准程序曝光
				settings.putString("mode", "P");
				settings.putBoolean("evAuto", true);
				settings.putBoolean("isoAuto", true);
				settings.putBoolean("wbAuto", true);
				settings.putBoolean("toneAuto", true);
			}
			case 2 -> { // 人像：最大光圈 + 自动对焦
				settings.putString("mode", "AV");
				settings.putFloat("aperture", lens.maxAperture);
				settings.putBoolean("evAuto", true);
				settings.putBoolean("isoAuto", true);
				settings.putBoolean("focusAuto", true);
			}
			case 3 -> { // 夜景：低光长曝
				settings.putString("mode", "TV");
				settings.putInt("shutter", -200);
				settings.putBoolean("evAuto", true);
				settings.putBoolean("isoAuto", true);
			}
			case 4 -> { // 电影：1/48 快门
				settings.putString("mode", "M");
				settings.putInt("shutter", 48);
				settings.putBoolean("evAuto", true);
				settings.putBoolean("isoAuto", true);
			}
			default -> {
			}
		}
		CameraBodyItem.setSettings(stack, settings);
		client.player.sendMessage(Text.translatable("message.camera-mod.hud_mode",
				Text.translatable(HudSnapshot.MODE_KEYS[index])), true);
	}

	private static void adjustHudParam(MinecraftClient client, int dir) {
		if (client.player == null) return;
		ItemStack stack = client.player.getMainHandStack();
		NbtCompound settings = CameraBodyItem.getSettings(stack);
		LensSpec lens = CameraBodyItem.getLensSpec(stack);

		switch (hudParam) {
			case 0 -> { // 曝光补偿（1/3 EV）
				int step = settings.getInt("evCompStep") + dir;
				step = Math.max(-9, Math.min(9, step));
				settings.putInt("evCompStep", step);
				settings.putBoolean("evAuto", false);
			}
			case 1 -> { // ISO
				int idx = AutoExposure.nearestIndex(AutoExposure.ISO_STOPS, settings.getInt("iso"));
				idx = Math.max(0, Math.min(AutoExposure.ISO_STOPS.length - 1, idx + dir));
				settings.putInt("iso", AutoExposure.ISO_STOPS[idx]);
				settings.putBoolean("isoAuto", false);
			}
			case 2 -> { // 快门
				int idx = AutoExposure.nearestIndex(AutoExposure.SHUTTER_STOPS, settings.getInt("shutter"));
				idx = Math.max(0, Math.min(AutoExposure.SHUTTER_STOPS.length - 1, idx + dir));
				settings.putInt("shutter", AutoExposure.SHUTTER_STOPS[idx]);
			}
			case 3 -> { // 白平衡
				int wb = Math.max(2000, Math.min(12000, settings.getInt("wb") + dir * 100));
				settings.putInt("wb", wb);
				settings.putBoolean("wbAuto", false);
			}
			case 4 -> { // 对焦距离
				float focus = Math.max(0.5f, Math.min(64f, settings.getFloat("focus") + dir * 0.5f));
				settings.putFloat("focus", focus);
			}
			case 5 -> { // 焦距（仅变焦镜头）
				adjustZoom(dir);
			}
			default -> {
			}
		}
		CameraBodyItem.setSettings(stack, settings);
	}

	private static void tickMetering(MinecraftClient client) {
		if (!cameraMode || client.player == null) return;

		double fov = CameraFovState.getCustomFov() > 0 ? CameraFovState.getCustomFov() : client.options.getFov().getValue();
		Metering.tick(client, fov);

		ItemStack bodyStack = client.player.getMainHandStack();
		NbtCompound settings = CameraBodyItem.getSettings(bodyStack);
		float ap = settings.getFloat("aperture");
		int sh = settings.getInt("shutter");
		int iso = settings.getInt("iso");
		int wb = settings.getInt("wb");
		int tone = settings.getInt("tone");
		int evStep = settings.getInt("evCompStep");
		int autoEvStep = settings.getInt("autoEvStep");

		boolean isoAuto = settings.getBoolean("isoAuto");
		boolean wbAuto = settings.getBoolean("wbAuto");
		boolean toneAuto = settings.getBoolean("toneAuto");
		evAuto = CameraBodyItem.isEvAuto(settings);

		ExposureMode mode = ExposureMode.M;
		String modeStr = settings.getString("mode");
		for (ExposureMode m : ExposureMode.values()) {
			if (m.name.equals(modeStr)) { mode = m; break; }
		}

		LensSpec lens = CameraBodyItem.getLensSpec(bodyStack);
		int focal = lens.focalLength;
		double rawSceneEV = Metering.getSceneEV();

		if (!smoothSceneEVInit) { smoothSceneEV = rawSceneEV; smoothSceneEVInit = true; }
		else smoothSceneEV += (rawSceneEV - smoothSceneEV) * 0.3;
		double sceneEV = smoothSceneEV;
		double autoEV = sceneEV - autoEvStep / 3.0;

		// Cave detection via sky light, not altitude: a surface house at
		// y<50 should stay "outdoor" and a deep ravine should count as cave.
		boolean isCave = client.world != null
				&& client.world.getLightLevel(LightType.SKY, client.player.getBlockPos()) < 8;
		long timeOfDay = client.world != null ? client.world.getTimeOfDay() : 6000;
		int sceneTemp = SceneColorTemp.getSceneColorTemp(timeOfDay, isCave);

		if (wbAuto) wb = (sceneTemp + 5500) / 2;
		cachedWb = wb;
		cachedSceneTemp = sceneTemp;
		cachedTone = tone;
		PostProcess.updateWhiteBalance(wb, sceneTemp);
		PostProcess.updateTone(tone);

		int isoIn = isoAuto ? -1 : iso;
		boolean fullFrame = CameraBodyItem.isFullFrame(bodyStack);

		if (mode == ExposureMode.A_PLUS) {
			// AUTO：4 秒上限，优先压低 ISO（看得见的噪点比看不见的机震更伤画面）。
			AutoExposure.AutoResult r = AutoExposure.computeFullAuto(autoEV, focal, lens.maxAperture, -400, isoIn, fullFrame);
			iso = r.iso; sh = r.shutter; ap = Math.max(r.aperture, lens.maxAperture);
		} else if (mode == ExposureMode.P) {
			AutoExposure.AutoResult r = AutoExposure.computeFullAuto(autoEV, focal, lens.maxAperture, -200, isoIn, fullFrame);
			iso = r.iso; sh = r.shutter; ap = Math.max(r.aperture, lens.maxAperture);
		} else if (mode == ExposureMode.AV) {
			AutoExposure.AutoResult r = AutoExposure.computeAv(autoEV, ap, focal, -400, isoIn, fullFrame);
			iso = r.iso; sh = r.shutter;
		} else if (mode == ExposureMode.TV) {
			AutoExposure.AutoResult r = AutoExposure.computeTv(autoEV, sh, focal, lens.maxAperture, isoIn, fullFrame);
			iso = r.iso; ap = Math.max(r.aperture, lens.maxAperture);
		} else {
			if (isoAuto) iso = AutoExposure.computeManualIso(autoEV, ap, sh);
		}

		cachedIso = iso;
		cachedShutter = sh;
		cachedAperture = ap;
		if (toneAuto) tone = 0;
		cachedTone = tone;

		PostProcess.updateIso(iso);

		double diff = Metering.exposureDiff(ap, sh, iso);
		lastDiff = diff;

		// Auto EV cancels the metering error entirely: the photo is exposed for
		// the scene. Manual EV is the user's own offset and is left untouched.
		evComp = evAuto ? (float) diff : evStep / 3.0f;
		totalEvOffset = (float) (-diff + evComp);

		fillHudSnapshot(client, bodyStack, lens, settings, mode);
	}

	private static void fillHudSnapshot(MinecraftClient client, ItemStack stack, LensSpec lens,
	                                    NbtCompound settings, ExposureMode mode) {
		boolean zoomLens = lens.isZoom;
		int paramCount = zoomLens ? 6 : 5;
		if (hudParam >= paramCount) hudParam = paramCount - 1;
		hudMode = Math.max(0, Math.min(HudSnapshot.MODE_COUNT - 1, settings.getInt("hudMode")));

		// Resolve HUD strings at tick rate instead of every frame.
		for (int i = 0; i < HudSnapshot.MODE_COUNT; i++) {
			HUD.modeNames[i] = Text.translatable(HudSnapshot.MODE_KEYS[i]).getString();
		}
		for (int i = 0; i < HudSnapshot.PARAM_COUNT; i++) {
			HUD.paramNames[i] = Text.translatable(HudSnapshot.PARAM_KEYS[i]).getString();
		}
		HUD.hint = Text.translatable("hud.camera-mod.hint").getString();
		HUD.liveLabel = Text.translatable("hud.camera-mod.live",
				Text.translatable(CameraBodyItem.isFullFrame(stack)
						? "hud.camera-mod.fullframe" : "hud.camera-mod.halfframe").getString()).getString();

		boolean isoAuto = settings.getBoolean("isoAuto");
		boolean wbAuto = settings.getBoolean("wbAuto");
		boolean focusAuto = settings.getBoolean("focusAuto");

		HUD.modeIndex = hudMode;
		HUD.paramIndex = hudParam;
		HUD.paramCount = paramCount;
		HUD.autoMode = hudMode == 0;
		HUD.portrait = hudMode == 2;
		HUD.night = hudMode == 3;
		HUD.cinemaBars = hudMode == 4;

		String evStr = evAuto ? "AUTO"
				: ((evComp >= 0 ? "+" : "") + String.format(Locale.US, "%.1f", evComp) + "EV");
		HUD.readout = String.format(Locale.US, "ƒ%.1f · %s · ISO%d · %s",
				cachedAperture, Metering.formatShutter(cachedShutter), cachedIso, evStr);

		int[] isoStops = AutoExposure.ISO_STOPS;
		int[] shutterStops = AutoExposure.SHUTTER_STOPS;

		for (int i = 0; i < HudSnapshot.PARAM_COUNT; i++) {
			HUD.paramValues[i] = null;
			HUD.paramFill[i] = 0f;
		}

		HUD.paramValues[0] = evAuto ? "AUTO" : ((evComp >= 0 ? "+" : "") + String.format(Locale.US, "%.1f", evComp) + "EV");
		HUD.paramFill[0] = clamp01((totalEvOffset + 3f) / 6f);

		HUD.paramValues[1] = isoAuto ? "AUTO" : String.valueOf(cachedIso);
		HUD.paramFill[1] = clamp01((float) (Math.log((double) cachedIso / isoStops[0]) / Math.log((double) isoStops[isoStops.length - 1] / isoStops[0])));

		HUD.paramValues[2] = Metering.formatShutter(cachedShutter);
		HUD.paramFill[2] = (float) AutoExposure.nearestIndex(shutterStops, cachedShutter) / (shutterStops.length - 1);

		HUD.paramValues[3] = wbAuto ? "AUTO" : cachedWb + "K";
		HUD.paramFill[3] = clamp01((cachedWb - 2000f) / 10000f);

		float focus = settings.getFloat("focus");
		HUD.paramValues[4] = focusAuto ? "AUTO" : String.format(Locale.US, "%.1fm", focus);
		HUD.paramFill[4] = clamp01((float) (Math.log(Math.max(0.5f, focus)) / Math.log(64.0)));

		if (zoomLens) {
			HUD.paramValues[5] = lens.focalLength + "mm";
			float span = Math.max(1f, lens.maxFocalLength - lens.baseFocalLength);
			HUD.paramFill[5] = clamp01((lens.focalLength - lens.baseFocalLength) / span);
		}

		// ---- Classic（佳能式）读数 ----
		HUD.modeLetter = mode.name;                       // A+ / P / Tv / Av / M
		HUD.shutterText = Metering.formatShutterCanon(cachedShutter);
		HUD.apertureText = Metering.formatAperture(cachedAperture);
		HUD.isoText = String.valueOf(cachedIso);
		HUD.wbAuto = wbAuto;
		HUD.wbValue = cachedWb;
		HUD.evOffset = totalEvOffset;
		HUD.afState = isFocusing ? 1 : (afConfirmTicks > 0 ? 2 : 0);
	}

	private static float clamp01(float v) {
		return v < 0f ? 0f : (v > 1f ? 1f : v);
	}

	private static void cycleMeteringMode(MinecraftClient client) {
		if (client.player == null) return;
		Metering.currentMode = Metering.currentMode.next();
		client.player.sendMessage(Text.translatable("message.camera-mod.metering_mode",
				Text.translatable(Metering.currentMode.nameKey)), true);
	}

	private static void adjustZoom(int dir) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!cameraMode || client.player == null) return;
		ItemStack camera = client.player.getMainHandStack();
		LensSpec spec = CameraBodyItem.getLensSpec(camera);
		if (!spec.isZoom) return;

		int step = Math.max(1, (int) ((spec.maxFocalLength - spec.baseFocalLength) * 0.1f));
		int newFocal = spec.focalLength + dir * step;
		if (newFocal < spec.baseFocalLength) newFocal = spec.baseFocalLength;
		if (newFocal > spec.maxFocalLength) newFocal = spec.maxFocalLength;
		if (newFocal != spec.focalLength) {
			spec.focalLength = newFocal;
			CameraBodyItem.setLensSpec(camera, spec);
		}
	}

	private static double computeFov(int focal) {
		// 36mm full-frame image height (2.916 ≈ 36mm / 12mm units), matching
		// the thin-lens sensor scale used by the capture.
		double fov = 2.0 * Math.toDegrees(Math.atan(2.916 * 12.0 / focal));
		return Math.max(10.0, Math.min(110.0, fov));
	}

	/**
	 * Approximates the EV offset on the live preview. Brightening uses
	 * multiplicative doubling passes so the overlay stays symmetric with the
	 * capture pipeline across the whole ±5 stop range.
	 */
	private static void applyEvComp(int w, int h, float evComp) {
		if (Math.abs(evComp) < 0.01f) return;
		float factor = (float) Math.pow(2, evComp);
		RenderSystem.enableBlend();
		if (factor > 1.0f) {
			RenderSystem.blendFunc(GlStateManager.SrcFactor.DST_COLOR, GlStateManager.DstFactor.ONE);
			int fullPasses = (int) Math.min(5, Math.floor(evComp));
			float frac = factor / (float) Math.pow(2, fullPasses) - 1f;
			for (int i = 0; i < fullPasses; i++) drawEvFill(w, h, 1f);
			if (frac > 0.01f) drawEvFill(w, h, frac);
		} else {
			RenderSystem.blendFunc(GlStateManager.SrcFactor.DST_COLOR, GlStateManager.DstFactor.ZERO);
			drawEvFill(w, h, factor);
		}
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableBlend();
		RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
	}

	private static void drawEvFill(int w, int h, float c) {
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);
		Matrix4f mat = new Matrix4f();
		BufferBuilder bb = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
		bb.vertex(mat, 0, 0, 0).color(c, c, c, 1f);
		bb.vertex(mat, 0, h, 0).color(c, c, c, 1f);
		bb.vertex(mat, w, h, 0).color(c, c, c, 1f);
		bb.vertex(mat, w, 0, 0).color(c, c, c, 1f);
		BufferRenderer.drawWithGlobalProgram(bb.end());
	}

}
