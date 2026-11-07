package com.example.camera.client;

import com.example.camera.ModItems;
import com.example.camera.client.gui.CameraSettingsScreen;
import com.example.camera.client.hud.CameraHud;
import com.example.camera.client.hud.HudSnapshot;
import com.example.camera.client.metering.Metering;
import com.example.camera.client.render.CameraItemRenderer;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import data.LensSpec;
import item.Jishen;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Locale;

public class CameraModClient implements ClientModInitializer {
	public static KeyBinding shutterKey;
	public static KeyBinding zoomInKey;
	public static KeyBinding zoomOutKey;
	public static KeyBinding meteringKey;

	private static boolean pendingShot = false;
	private static boolean cameraMode = false;
	private static double lastDiff = 0.0;
	private static float evComp = 0f;
	private static boolean evAuto = true;
	private static float totalEvOffset = 0f;
	private static int shutterFlashTicks = 0;
	private static final int SHUTTER_FLASH_DURATION = 6;

	// ---- HUD (取景器) ----
	/** 0=自动 1=照片 2=人像 3=夜景 4=电影，持久化在相机 NBT 的 hudMode。 */
	private static int hudMode = 0;
	private static int hudParam = 0;
	/** 安装动画期间保持手持模型可见（1.5s 动画约 30 tick）。 */
	private static int installAnimTicks = 0;
	private static int hudAdjustCooldown = 0;
	private static int hudParamCooldown = 0;
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
	private static boolean lastLeftDown = false;

	public static boolean isCameraMode() {
		return cameraMode;
	}

	@Override
	public void onInitializeClient() {
		shutterKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.camera-mod.shutter", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_P, "category.camera-mod"));
		zoomInKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.camera-mod.zoom_in", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UP, "category.camera-mod"));
		zoomOutKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.camera-mod.zoom_out", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_DOWN, "category.camera-mod"));
		meteringKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.camera-mod.metering", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "category.camera-mod"));

		CameraItemRenderer cameraRenderer = new CameraItemRenderer();
		BuiltinItemRendererRegistry.DynamicItemRenderer itemRenderer = cameraRenderer::render;
		BuiltinItemRendererRegistry.INSTANCE.register(ModItems.LUMIN_F, itemRenderer);
		BuiltinItemRendererRegistry.INSTANCE.register(ModItems.LUMIN_H, itemRenderer);

		UseItemCallback.EVENT.register((player, world, hand) -> {
			ItemStack stack = player.getStackInHand(hand);
			if (Jishen.isCamera(stack)) {
				// Installing takes priority: let the server-side use() write the
				// assembly NBT and play the animation.
				if (Jishen.installAction(stack, player.getOffHandStack()) != null) {
					if (world.isClient()) {
						// Predict the assembly so the lens bone is already visible
						// while the install animation plays; the server confirms it
						// and consumes the offhand lens.
						Jishen.installLens(stack, player.getOffHandStack());
						installAnimTicks = 36;
					}
					return TypedActionResult.pass(stack);
				}
				if (world.isClient() && Jishen.isReady(stack)) {
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

			if (cameraMode) {
				CameraHud.tick(0.05f);
			} else {
				CameraHud.reset();
			}
			tickHudInput(client);

			if (client.currentScreen == null) {
				while (meteringKey.wasPressed()) cycleMeteringMode(client);
				while (shutterKey.wasPressed()) onShutterPressed(client);
			}

			if (isFocusing) {
				focusTicks++;
				if (focusTicks >= 5) {
					isFocusing = false;
					executeCapture(client);
				}
			}

			if (client.currentScreen == null && cameraMode && client.getWindow() != null) {
				boolean leftDown = GLFW.glfwGetMouseButton(client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
				if (leftDown && !lastLeftDown) onShutterPressed(client);
				lastLeftDown = leftDown;
			} else {
				lastLeftDown = false;
			}
		});

		WorldRenderEvents.END.register(context -> {
			MinecraftClient client = MinecraftClient.getInstance();
			if (HighResCapture.consumeCompletion()) {
				pendingShot = false;
				return;
			}
			if (HighResCapture.isActive() || HighResCapture.isWaiting()) return;
			if (!pendingShot) return;
			pendingShot = false;

			ScreenshotRecorder.saveScreenshot(client.runDirectory, client.getFramebuffer(), text -> {
				if (client.player != null) client.player.sendMessage(Text.literal("[相机] " + text.getString()), false);
			});

			try {
				Framebuffer fb = client.getFramebuffer();
				int w = fb.textureWidth;
				int h = fb.textureHeight;

				ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
				int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
				try {
					GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fb.fbo);
					GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
				} finally {
					GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
				}

				short[] rgb16 = new short[w * h * 3];
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						int srcY = h - 1 - y;
						int src = (srcY * w + x) * 4;
						int dst = (y * w + x) * 3;
						int r = buf.get(src) & 0xFF;
						int g = buf.get(src + 1) & 0xFF;
						int b = buf.get(src + 2) & 0xFF;
						rgb16[dst] = (short) ((r << 8) | r);
						rgb16[dst + 1] = (short) ((g << 8) | g);
						rgb16[dst + 2] = (short) ((b << 8) | b);
					}
				}

				File tiffDir = new File(client.runDirectory, "screenshots");
				tiffDir.mkdirs();
				String name = "camera_" + System.currentTimeMillis() + ".tiff";
				File tiffFile = new File(tiffDir, name);

				TiffWriter.writeRgb16(tiffFile, rgb16, w, h);

				if (client.player != null) {
					client.player.sendMessage(Text.literal("[相机] TIFF: " + name), false);
				}
			} catch (Exception e) {
				System.err.println("[CameraMod] TIFF 保存失败: " + e.getMessage());
			}
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

			// 无边框取景器 HUD：顶部状态、左侧模式、底部参数轨。
			fillHudSnapshot(client);
			CameraHud.render(ctx, client, HUD);

			if (shutterFlashTicks > 0) {
				float t = (float) shutterFlashTicks / SHUTTER_FLASH_DURATION;
				float intensity = t > 0.67f ? (1.0f - t) / 0.33f : t / 0.67f;
				int alpha = (int) (intensity * 220);
				if (alpha > 0) ctx.fill(0, 0, w, h, (alpha << 24));
			}
		});
	}

	private static void onShutterPressed(MinecraftClient client) {
		if (isFocusing) return;
		if (client.player == null || client.world == null || !cameraMode) return;

		ItemStack mainHand = client.player.getMainHandStack();
		LensSpec spec = Jishen.getLensSpec(mainHand);
		Jishen.playAnimation(mainHand, Jishen.ANIM_SHUTTER);

		if (spec.autoFocus) {
			isFocusing = true;
			focusTicks = 0;
			client.player.sendMessage(Text.literal("§a[相机] 对焦中..."), true);
		} else {
			executeCapture(client);
		}
	}

	private static void executeCapture(MinecraftClient client) {
		if (client.player == null || client.world == null || !cameraMode) return;

		ItemStack mainHand = client.player.getMainHandStack();
		LensSpec spec = Jishen.getLensSpec(mainHand);
		NbtCompound nbt = Jishen.getSettings(mainHand);

		if (spec.autoFocus) {
			var start = client.player.getEyePos();
			var dir = client.player.getRotationVec(1.0f);
			var end = start.add(dir.multiply(256.0));
			var hit = client.world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));

			float newFocus = (hit.getType() == HitResult.Type.BLOCK) ? (float) start.distanceTo(hit.getPos()) : Float.POSITIVE_INFINITY;
			float minFocus = Math.max(0.5f, spec.focalLength / 100.0f);
			if (!Float.isInfinite(newFocus) && newFocus < minFocus) newFocus = minFocus;

			nbt.putFloat("focus", newFocus);
			Jishen.setSettings(mainHand, nbt);

			String fStr = Float.isInfinite(newFocus) ? "∞" : String.format(Locale.US, "%.1fm", newFocus);
			client.player.sendMessage(Text.literal("§a[相机] 合焦于 " + fStr + "，拍摄！"), true);
		} else {
			client.player.sendMessage(Text.literal("§e[相机] 手动镜头拍摄！"), true);
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
		HighResCapture.snapshotSensorHeight = Jishen.getSensorHeight(mainHand);
		HighResCapture.snapshotFullFrame = Jishen.isFullFrame(mainHand);

		boolean isFullFrame = HighResCapture.snapshotSensorHeight == 24.0f;
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
			client.player.sendMessage(Text.literal("[相机] 无法创建高分辨率渲染目标；请检查显存/显卡最大纹理尺寸。"), false);
			return;
		}
		var soundEvent = net.minecraft.registry.Registries.SOUND_EVENT.get(net.minecraft.util.Identifier.of("camera-mod", "shutter"));
		if (soundEvent != null) client.player.playSound(soundEvent, 1.0f, 1.0f);
		shutterFlashTicks = SHUTTER_FLASH_DURATION;
		pendingShot = true;
	}

	private static void applyLensFov(MinecraftClient client) {
		if (client.player == null) return;
		ItemStack mainHand = client.player.getMainHandStack();
		boolean wasCamera = cameraMode;
		cameraMode = Jishen.isReady(mainHand);
		if (cameraMode) {
			LensSpec spec = Jishen.getLensSpec(mainHand);
			CameraFovState.setCustomFov(computeFov(spec.focalLength));
			if (!wasCamera) {
				NbtCompound settings = Jishen.getSettings(mainHand);
				if (settings.contains("hudMode")) {
					hudMode = settings.getInt("hudMode");
				} else {
					setHudMode(client, 0);
				}
				CameraHud.reset();
			}
		} else {
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

	private static final int[] ISO_STEPS = {
			25, 50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600
	};
	private static final int[] SHUTTER_STEPS = {
			4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2, 1, -200, -400, -800
	};

	private static void tickHudInput(MinecraftClient client) {
		if (!isViewfinderActive()) {
			hudAdjustCooldown = 0;
			hudParamCooldown = 0;
			return;
		}
		ItemStack stack = client.player.getMainHandStack();
		NbtCompound settings = Jishen.getSettings(stack);
		hudMode = Math.max(0, Math.min(HudSnapshot.MODE_NAMES.length - 1, settings.getInt("hudMode")));

		// 设计稿的键位：↑/↓ 切模式（沿用原变焦键）。
		while (zoomInKey.wasPressed()) setHudMode(client, hudMode - 1);
		while (zoomOutKey.wasPressed()) setHudMode(client, hudMode + 1);

		int paramCount = Jishen.getLensSpec(stack).isZoom ? 6 : 5;
		if (hudParam >= paramCount) hudParam = paramCount - 1;

		long window = client.getWindow().getHandle();

		// [ / ] 在参数之间移动（滚轮同样可以切换）。
		if (hudParamCooldown > 0) {
			hudParamCooldown--;
		} else {
			boolean prev = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_BRACKET);
			boolean next = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_BRACKET);
			if (prev != next) {
				moveHudParam(next ? 1 : -1, paramCount);
				hudParamCooldown = 5;
			}
		}

		// ← / → 调整当前参数。
		if (hudAdjustCooldown > 0) {
			hudAdjustCooldown--;
			return;
		}
		int dir = 0;
		if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT)) dir = -1;
		else if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT)) dir = 1;
		if (dir != 0) {
			adjustHudParam(client, dir);
			hudAdjustCooldown = 4;
		}
	}

	private static void moveHudParam(int dir, int paramCount) {
		hudParam = Math.floorMod(hudParam + dir, Math.max(1, paramCount));
	}

	private static void setHudMode(MinecraftClient client, int index) {
		if (client.player == null) return;
		index = Math.max(0, Math.min(HudSnapshot.MODE_NAMES.length - 1, index));
		hudMode = index;

		ItemStack stack = client.player.getMainHandStack();
		NbtCompound settings = Jishen.getSettings(stack);
		LensSpec lens = Jishen.getLensSpec(stack);
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
		Jishen.setSettings(stack, settings);
		client.player.sendMessage(Text.literal("[模式] " + HudSnapshot.MODE_NAMES[index]), true);
	}

	private static void adjustHudParam(MinecraftClient client, int dir) {
		if (client.player == null) return;
		ItemStack stack = client.player.getMainHandStack();
		NbtCompound settings = Jishen.getSettings(stack);
		LensSpec lens = Jishen.getLensSpec(stack);

		switch (hudParam) {
			case 0 -> { // 曝光补偿（1/3 EV）
				int step = settings.getInt("evCompStep") + dir;
				step = Math.max(-9, Math.min(9, step));
				settings.putInt("evCompStep", step);
				settings.putBoolean("evAuto", false);
			}
			case 1 -> { // ISO
				int idx = nearestIndex(ISO_STEPS, settings.getInt("iso"));
				idx = Math.max(0, Math.min(ISO_STEPS.length - 1, idx + dir));
				settings.putInt("iso", ISO_STEPS[idx]);
				settings.putBoolean("isoAuto", false);
			}
			case 2 -> { // 快门
				int idx = nearestIndex(SHUTTER_STEPS, settings.getInt("shutter"));
				idx = Math.max(0, Math.min(SHUTTER_STEPS.length - 1, idx + dir));
				settings.putInt("shutter", SHUTTER_STEPS[idx]);
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
		Jishen.setSettings(stack, settings);
	}

	private static int nearestIndex(int[] values, int value) {
		int best = 0;
		for (int i = 1; i < values.length; i++) {
			if (Math.abs(values[i] - value) < Math.abs(values[best] - value)) best = i;
		}
		return best;
	}

	private static void fillHudSnapshot(MinecraftClient client) {
		ItemStack stack = client.player.getMainHandStack();
		NbtCompound settings = Jishen.getSettings(stack);
		LensSpec lens = Jishen.getLensSpec(stack);

		boolean zoomLens = lens.isZoom;
		int paramCount = zoomLens ? 6 : 5;
		if (hudParam >= paramCount) hudParam = paramCount - 1;
		hudMode = Math.max(0, Math.min(HudSnapshot.MODE_NAMES.length - 1, settings.getInt("hudMode")));

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
		HUD.liveLabel = (Jishen.isFullFrame(stack) ? "全画幅" : "半画幅") + " · 8K";

		String evStr = evAuto ? "AUTO"
				: ((evComp >= 0 ? "+" : "") + String.format(Locale.US, "%.1f", evComp) + "EV");
		HUD.readout = String.format(Locale.US, "ƒ%.1f · %s · ISO%d · %s",
				cachedAperture, Metering.formatShutter(cachedShutter), cachedIso, evStr);

		for (int i = 0; i < HudSnapshot.PARAM_NAMES.length; i++) {
			HUD.paramValues[i] = null;
			HUD.paramFill[i] = 0f;
		}

		HUD.paramValues[0] = evAuto ? "AUTO" : ((evComp >= 0 ? "+" : "") + String.format(Locale.US, "%.1f", evComp) + "EV");
		HUD.paramFill[0] = clamp01((totalEvOffset + 3f) / 6f);

		HUD.paramValues[1] = isoAuto ? "AUTO" : String.valueOf(cachedIso);
		HUD.paramFill[1] = clamp01((float) (Math.log((double) cachedIso / ISO_STEPS[0]) / Math.log((double) ISO_STEPS[ISO_STEPS.length - 1] / ISO_STEPS[0])));

		HUD.paramValues[2] = Metering.formatShutter(cachedShutter);
		HUD.paramFill[2] = (float) nearestIndex(SHUTTER_STEPS, cachedShutter) / (SHUTTER_STEPS.length - 1);

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
	}

	private static float clamp01(float v) {
		return v < 0f ? 0f : (v > 1f ? 1f : v);
	}


	private static void tickMetering(MinecraftClient client) {
		if (!cameraMode || client.player == null) return;

		double fov = CameraFovState.getCustomFov() > 0 ? CameraFovState.getCustomFov() : client.options.getFov().getValue();
		Metering.tick(client, fov);

		ItemStack bodyStack = client.player.getMainHandStack();
		NbtCompound settings = Jishen.getSettings(bodyStack);
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
		evAuto = Jishen.isEvAuto(settings);

		ExposureMode mode = ExposureMode.M;
		String modeStr = settings.getString("mode");
		for (ExposureMode m : ExposureMode.values()) {
			if (m.name.equals(modeStr)) { mode = m; break; }
		}

		LensSpec lens = Jishen.getLensSpec(bodyStack);
		int focal = lens.focalLength;
		double rawSceneEV = Metering.getSceneEV();

		if (!smoothSceneEVInit) { smoothSceneEV = rawSceneEV; smoothSceneEVInit = true; }
		else smoothSceneEV += (rawSceneEV - smoothSceneEV) * 0.3;
		double sceneEV = smoothSceneEV;
		double autoEV = sceneEV - autoEvStep / 3.0;

		boolean isCave = client.player.getY() < 50;
		long timeOfDay = client.world != null ? client.world.getTimeOfDay() : 6000;
		int sceneTemp = SceneColorTemp.getSceneColorTemp(timeOfDay, isCave);

		if (wbAuto) wb = (sceneTemp + 5500) / 2;
		cachedWb = wb;
		cachedSceneTemp = sceneTemp;
		cachedTone = tone;
		PostProcess.updateWhiteBalance(wb, sceneTemp);

		int isoIn = isoAuto ? -1 : iso;
		boolean fullFrame = Jishen.isFullFrame(bodyStack);

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
			AutoExposure.AutoResult r = AutoExposure.computeTv(autoEV, sh, lens.maxAperture, isoIn, fullFrame);
			iso = r.iso; ap = Math.max(r.aperture, lens.maxAperture);
		} else {
			if (isoAuto) iso = AutoExposure.computeManualIso(autoEV, ap, sh);
		}

		cachedIso = iso;
		cachedShutter = sh;
		cachedAperture = ap;
		if (toneAuto) tone = 0;

		PostProcess.updateIso(iso);

		double diff = Metering.exposureDiff(ap, sh, iso);
		lastDiff = diff;

		// Auto EV cancels the metering error entirely: the photo is exposed for
		// the scene. Manual EV is the user's own offset and is left untouched.
		evComp = evAuto ? (float) diff : evStep / 3.0f;
		totalEvOffset = (float) (-diff + evComp);
	}

	private static void cycleMeteringMode(MinecraftClient client) {
		if (client.player == null) return;
		Metering.currentMode = Metering.currentMode.next();
		client.player.sendMessage(Text.literal("[测光] " + Metering.currentMode.displayName), true);
	}

	private static void adjustZoom(int dir) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!cameraMode || client.player == null) return;
		ItemStack camera = client.player.getMainHandStack();
		LensSpec spec = Jishen.getLensSpec(camera);
		if (!spec.isZoom) return;

		int step = Math.max(1, (int) ((spec.maxFocalLength - spec.baseFocalLength) * 0.1f));
		int newFocal = spec.focalLength + dir * step;
		if (newFocal < spec.baseFocalLength) newFocal = spec.baseFocalLength;
		if (newFocal > spec.maxFocalLength) newFocal = spec.maxFocalLength;
		if (newFocal != spec.focalLength) {
			spec.focalLength = newFocal;
			Jishen.setLensSpec(camera, spec);
		}
	}

	private static double computeFov(int focal) {
		double fov = 2.0 * Math.toDegrees(Math.atan(2.916 * 12.0 / focal));
		return Math.max(10.0, Math.min(110.0, fov));
	}

	private static void applyEvComp(int w, int h, float evComp) {
		if (Math.abs(evComp) < 0.01f) return;
		float factor = (float) Math.pow(2, evComp);
		float c;
		if (factor > 1.0f) {
			c = Math.min(1.0f, factor - 1.0f);
			RenderSystem.enableBlend();
			RenderSystem.blendFunc(GlStateManager.SrcFactor.DST_COLOR, GlStateManager.DstFactor.ONE);
		} else {
			c = factor;
			RenderSystem.enableBlend();
			RenderSystem.blendFunc(GlStateManager.SrcFactor.DST_COLOR, GlStateManager.DstFactor.ZERO);
		}

		RenderSystem.setShader(GameRenderer::getPositionColorProgram);
		Matrix4f mat = new Matrix4f();
		BufferBuilder bb = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
		bb.vertex(mat, 0, 0, 0).color(c, c, c, 1f);
		bb.vertex(mat, 0, h, 0).color(c, c, c, 1f);
		bb.vertex(mat, w, h, 0).color(c, c, c, 1f);
		bb.vertex(mat, w, 0, 0).color(c, c, c, 1f);
		BufferRenderer.drawWithGlobalProgram(bb.end());

		RenderSystem.defaultBlendFunc();
		RenderSystem.disableBlend();
		RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
	}

	private static void drawSingleBar(DrawContext ctx, MinecraftClient client, int x, int y, int width, double activeEV) {
		// A manual EV that pushes the exposure more than one stop away from the
		// metered value will visibly clip; warn with red numbers instead of
		// silently ruining the shot.
		boolean warn = !evAuto && Math.abs(totalEvOffset) > 1.0f;
		int color = warn ? 0xFFFF5555 : 0xFFAACC77;
		int textScale = 3;
		int pixelPerEv = 45;
		int tickWidth = 2;

		String sh = Metering.formatShutter(cachedShutter);
		String apStr = "f/" + (cachedAperture == (int) cachedAperture ? String.valueOf((int) cachedAperture) : String.format(Locale.US, "%.1f", cachedAperture));
		String isoStr = "ISO" + cachedIso;
		double quantized = Math.round(activeEV * 3) / 3.0;

		int shX = x + 10;
		int apX = x + 150;
		int meterCx = x + width / 2;
		int isoRightX = x + width - 10;
		int scaledPixelPerEv = pixelPerEv / textScale;

		MatrixStack m = ctx.getMatrices();
		m.push();
		m.scale(textScale, textScale, 1f);
		int sy = y / textScale;

		ctx.drawText(client.textRenderer, sh, shX / textScale, sy, color, false);
		ctx.drawText(client.textRenderer, apStr, apX / textScale, sy, color, false);

		int scaledMeterCx = meterCx / textScale;
		for (int i = -3; i <= 3; i++) {
			int px = scaledMeterCx + i * scaledPixelPerEv;
			String s = (i > 0 ? "+" : "") + i;
			int tw = client.textRenderer.getWidth(s);
			ctx.drawText(client.textRenderer, s, px - tw / 2, sy, color, false);
		}

		int isoW = client.textRenderer.getWidth(isoStr);
		ctx.drawText(client.textRenderer, isoStr, (isoRightX - isoW * textScale) / textScale, sy, color, false);
		m.pop();

		int tickY = y + 28;
		int tickH = 8;
		for (int i = -9; i <= 9; i++) {
			if (i % 3 == 0) continue;
			int tx = meterCx + i * pixelPerEv / 3;
			ctx.fill(tx, tickY, tx + tickWidth, tickY + tickH, color);
		}

		int pointerY = tickY + tickH + 2;
		if (quantized < -3.0) {
			if ((System.currentTimeMillis() / 300) % 2 == 0) {
				int px = meterCx - 3 * pixelPerEv;
				ctx.fill(px - 5, pointerY, px + 5, pointerY + 1, color);
				ctx.fill(px - 4, pointerY + 1, px + 4, pointerY + 2, color);
				ctx.fill(px - 3, pointerY + 2, px + 3, pointerY + 3, color);
				ctx.fill(px - 2, pointerY + 3, px + 2, pointerY + 4, color);
				ctx.fill(px - 1, pointerY + 4, px + 1, pointerY + 5, color);
			}
		} else if (quantized > 3.0) {
			if ((System.currentTimeMillis() / 300) % 2 == 0) {
				int px = meterCx + 3 * pixelPerEv;
				ctx.fill(px - 5, pointerY, px + 5, pointerY + 1, color);
				ctx.fill(px - 4, pointerY + 1, px + 4, pointerY + 2, color);
				ctx.fill(px - 3, pointerY + 2, px + 3, pointerY + 3, color);
				ctx.fill(px - 2, pointerY + 3, px + 2, pointerY + 4, color);
				ctx.fill(px - 1, pointerY + 4, px + 1, pointerY + 5, color);
			}
		} else {
			int pointerX = (int) Math.round(meterCx + quantized * pixelPerEv);
			ctx.fill(pointerX - 1, pointerY, pointerX + 1, pointerY + 1, color);
			ctx.fill(pointerX - 2, pointerY + 1, pointerX + 2, pointerY + 2, color);
			ctx.fill(pointerX - 3, pointerY + 2, pointerX + 3, pointerY + 3, color);
			ctx.fill(pointerX - 4, pointerY + 3, pointerX + 4, pointerY + 4, color);
			ctx.fill(pointerX - 5, pointerY + 4, pointerX + 5, pointerY + 5, color);
		}
	}
}
