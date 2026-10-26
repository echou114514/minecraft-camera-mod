package com.example.camera.client;

import com.example.camera.ModItems;
import com.example.camera.client.gui.CameraSettingsScreen;
import com.example.camera.client.metering.Metering;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import data.LensSpec;
import item.Jingtou;
import item.Jishen;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
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
	private static String hudLine1 = "";
	private static String hudLine2 = "";
	private static int hudColor = 0xFFFFFF;
	private static double lastDiff = 0.0;
	private static float evComp = 0f;
	private static int savedGuiScale = -1;
	private static int shutterFlashTicks = 0;
	private static final int SHUTTER_FLASH_DURATION = 6;

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

		UseItemCallback.EVENT.register((player, world, hand) -> {
			ItemStack stack = player.getStackInHand(hand);
			if (world.isClient() && stack.isOf(ModItems.CAMERA_BODY)) {
				CameraSettingsScreen.open(stack);
				return TypedActionResult.success(stack);
			}
			return TypedActionResult.pass(stack);
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			applyLensFov(client);
			applyCameraGuiScale(client);
			tickMetering(client);
			if (shutterFlashTicks > 0) shutterFlashTicks--;

			if (client.currentScreen == null) {
				while (zoomInKey.wasPressed()) adjustZoom(1);
				while (zoomOutKey.wasPressed()) adjustZoom(-1);
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
			boolean wasCapturing = HighResCapture.isActive();
			HighResCapture.afterFrame(client);

			if (wasCapturing && !HighResCapture.isActive() && !HighResCapture.isWaiting()) {
				pendingShot = false;
				return;
			}
			if (wasCapturing || HighResCapture.isActive() || HighResCapture.isWaiting()) return;
			if (!pendingShot) return;
			pendingShot = false;

			ScreenshotRecorder.saveScreenshot(client.runDirectory, client.getFramebuffer(), text -> {
				if (client.player != null) client.player.sendMessage(Text.literal("[相机] " + text.getString()), false);
			});

			try {
				Framebuffer fb = client.getFramebuffer();
				int w = fb.textureWidth;
				int h = fb.textureHeight;

				fb.beginRead();
				ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
				GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
				fb.endRead();

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
			if (!cameraMode) return;
			MinecraftClient client = MinecraftClient.getInstance();
			int w = ctx.getScaledWindowWidth();
			int h = ctx.getScaledWindowHeight();

			if (Math.abs(lastDiff) >= 0.15) {
				float exposureEV = (float) -lastDiff;
				if (exposureEV > 5f) exposureEV = 5f;
				if (exposureEV < -5f) exposureEV = -5f;
				applyEvComp(w, h, exposureEV);
			}
			applyEvComp(w, h, evComp);
			PostProcess.render(ctx, client);
			PostProcess.renderNoise(ctx, client);

			int barX = (int) (w * 0.15);
			int barY = (int) (h * 0.15);
			int black = 0xFF000000;
			ctx.fill(0, 0, barX, h, black);
			ctx.fill(w - barX, 0, w, h, black);
			ctx.fill(barX, 0, w - barX, barY, black);
			ctx.fill(barX, h - barY, w - barX, h, black);

			int bottomY = h - barY;
			int paramY = bottomY + 60;
			drawSingleBar(ctx, client, barX + 20, paramY, w - (barX + 20) * 2, -(lastDiff + evComp));

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

		ItemStack offHand = client.player.getOffHandStack();
		LensSpec spec = Jingtou.getSpec(offHand);

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
		ItemStack offHand = client.player.getOffHandStack();
		LensSpec spec = Jingtou.getSpec(offHand);
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

		HighResCapture.snapshotExposureEV = nbt.getInt("evCompStep") / 3.0f;
		HighResCapture.snapshotWb = cachedWb;
		HighResCapture.snapshotSceneTemp = cachedSceneTemp;
		HighResCapture.snapshotTone = cachedTone;
		HighResCapture.snapshotIso = cachedIso;
		HighResCapture.snapshotShutter = cachedShutter;
		HighResCapture.snapshotAperture = cachedAperture;
		HighResCapture.snapshotFocusDistance = nbt.getFloat("focus");
		HighResCapture.snapshotFocalLength = spec.focalLength;

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

		HighResCapture.request();

		var soundEvent = net.minecraft.registry.Registries.SOUND_EVENT.get(net.minecraft.util.Identifier.of("camera-mod", "shutter"));
		if (soundEvent != null) client.player.playSound(soundEvent, 1.0f, 1.0f);
		shutterFlashTicks = SHUTTER_FLASH_DURATION;
		pendingShot = true;
	}

	private static void applyLensFov(MinecraftClient client) {
		if (client.player == null) return;
		cameraMode = client.player.getMainHandStack().isOf(ModItems.CAMERA_BODY) && client.player.getOffHandStack().isOf(ModItems.LENS);
		if (cameraMode) {
			LensSpec spec = Jingtou.getSpec(client.player.getOffHandStack());
			CameraFovState.setCustomFov(computeFov(spec.focalLength));
		} else {
			CameraFovState.clear();
		}
	}

	private static void applyCameraGuiScale(MinecraftClient client) {
		boolean inCamera = cameraMode && client.currentScreen == null;
		if (inCamera) {
			if (savedGuiScale == -1) savedGuiScale = client.options.getGuiScale().getValue();
			if (client.options.getGuiScale().getValue() != 1) {
				client.options.getGuiScale().setValue(1);
				client.onResolutionChanged();
			}
		} else {
			if (savedGuiScale != -1) {
				if (client.options.getGuiScale().getValue() != savedGuiScale) {
					client.options.getGuiScale().setValue(savedGuiScale);
					client.onResolutionChanged();
				}
				savedGuiScale = -1;
			}
		}
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

		ExposureMode mode = ExposureMode.M;
		String modeStr = settings.getString("mode");
		for (ExposureMode m : ExposureMode.values()) {
			if (m.name.equals(modeStr)) { mode = m; break; }
		}

		LensSpec lens = Jingtou.getSpec(client.player.getOffHandStack());
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

		if (mode == ExposureMode.A_PLUS) {
			AutoExposure.AutoResult r = AutoExposure.computeFullAuto(autoEV, focal, lens.maxAperture, 8, isoIn);
			iso = r.iso; sh = r.shutter; ap = Math.max(r.aperture, lens.maxAperture);
		} else if (mode == ExposureMode.P) {
			AutoExposure.AutoResult r = AutoExposure.computeFullAuto(autoEV, focal, lens.maxAperture, -3000, isoIn);
			iso = r.iso; sh = r.shutter; ap = Math.max(r.aperture, lens.maxAperture);
		} else if (mode == ExposureMode.AV) {
			AutoExposure.AutoResult r = AutoExposure.computeAv(autoEV, ap, focal, -3000, isoIn);
			iso = r.iso; sh = r.shutter;
		} else if (mode == ExposureMode.TV) {
			AutoExposure.AutoResult r = AutoExposure.computeTv(autoEV, sh, lens.maxAperture, isoIn);
			iso = r.iso; ap = Math.max(r.aperture, lens.maxAperture);
		} else {
			if (isoAuto) iso = AutoExposure.computeManualIso(autoEV, ap, sh);
		}

		cachedIso = iso;
		cachedShutter = sh;
		cachedAperture = ap;
		if (toneAuto) tone = 0;

		PostProcess.updateIso(iso);
		evComp = evStep / 3.0f;

		double diff = Metering.exposureDiff(ap, sh, iso);
		lastDiff = diff;

		String evStr = (evComp >= 0 ? "+" : "") + String.format(Locale.US, "%.1f", evComp);
		hudLine1 = String.format(Locale.US, "%s %s  %dmm  f/%.1f  %s  ISO%d  %dK  %sEV",
				mode.name, Metering.currentMode.displayName, focal, ap, Metering.formatShutter(sh), iso, wb, evStr);
		hudLine2 = String.format(Locale.US, "场景EV:%.1f  场景色温:%dK", sceneEV, sceneTemp);
	}

	private static void cycleMeteringMode(MinecraftClient client) {
		if (client.player == null) return;
		Metering.currentMode = Metering.currentMode.next();
		client.player.sendMessage(Text.literal("[测光] " + Metering.currentMode.displayName), true);
	}

	private static void adjustZoom(int dir) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!cameraMode || client.player == null) return;
		ItemStack lensStack = client.player.getOffHandStack();
		LensSpec spec = Jingtou.getSpec(lensStack);
		if (!spec.isZoom) return;

		// 变焦步长随广角端和长焦端自动调整
		int step = Math.max(1, (int) ((spec.maxFocalLength - spec.baseFocalLength) * 0.1f));
		int newFocal = spec.focalLength + dir * step;
		if (newFocal < spec.baseFocalLength) newFocal = spec.baseFocalLength;
		if (newFocal > spec.maxFocalLength) newFocal = spec.maxFocalLength;
		if (newFocal != spec.focalLength) {
			spec.focalLength = newFocal;
			Jingtou.setSpec(lensStack, spec);
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
		int color = 0xFFAACC77;
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