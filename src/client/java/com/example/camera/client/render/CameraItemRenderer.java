package com.example.camera.client.render;

import com.example.camera.item.CameraBodyItem;

import com.example.camera.client.CameraModClient;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;

import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/**
 * First-person/third-person renderer for the Lumin camera bodies.
 *
 * <p>The 3D model comes from {@code geo/item/lumin.geo.json}; the lens bone is
 * hidden until a lens is stored in the camera's {@code Assembly} NBT.</p>
 */
public class CameraItemRenderer extends GeoItemRenderer<CameraBodyItem> {

    /** 世界中（手持/展示框/掉落物）的模型放大系数：模型精致但偏小，GUI 保持原尺寸避免图标裁切。 */
    private static final float WORLD_MODEL_SCALE = 1.18f;

    public CameraItemRenderer() {
        super(new LuminGeoModel());
    }

    @Override
    public void render(ItemStack stack, ModelTransformationMode mode, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        // While the viewfinder HUD is up the body would block the frame, so the
        // first-person prop is hidden (but stays visible during the install
        // animation, which is the whole point of the model).
        boolean firstPerson = mode == ModelTransformationMode.FIRST_PERSON_LEFT_HAND
                || mode == ModelTransformationMode.FIRST_PERSON_RIGHT_HAND;
        if (firstPerson && CameraModClient.isHandHidden()) {
            return;
        }

        if (mode == ModelTransformationMode.GUI) {
            super.render(stack, mode, matrices, vertexConsumers, light, overlay);
            return;
        }
        matrices.push();
        matrices.scale(WORLD_MODEL_SCALE, WORLD_MODEL_SCALE, WORLD_MODEL_SCALE);
        super.render(stack, mode, matrices, vertexConsumers, light, overlay);
        matrices.pop();
    }

    @Override
    public long getInstanceId(CameraBodyItem animatable) {
        // Must match the item's instanceId so server-triggered animations reach
        // the same AnimatableManager the renderer reads.
        return animatable.instanceId();
    }

    @Override
    public void preRender(MatrixStack poseStack, CameraBodyItem animatable, BakedGeoModel model,
                          VertexConsumerProvider bufferSource, VertexConsumer buffer,
                          boolean isReRender, float partialTick, int packedLight,
                          int packedOverlay, int colour) {
        super.preRender(poseStack, animatable, model, bufferSource, buffer,
                isReRender, partialTick, packedLight, packedOverlay, colour);

        if (isReRender) return;

        ItemStack stack = getCurrentItemStack();
        boolean hasLens = stack != null && CameraBodyItem.hasLens(stack);
        model.getBone("lens").ifPresent(bone -> bone.setHidden(!hasLens));
    }
}
