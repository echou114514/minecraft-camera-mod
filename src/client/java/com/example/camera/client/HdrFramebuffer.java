package com.example.camera.client;

import net.minecraft.client.gl.Framebuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

public class HdrFramebuffer extends Framebuffer {

    public HdrFramebuffer(int w, int h) {
        super(true);

        this.textureWidth = w;
        this.textureHeight = h;
        this.viewportWidth = w;
        this.viewportHeight = h;
        this.setClearColor(0, 0, 0, 0);

        // RGBA16F 颜色纹理
        this.colorAttachment = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.colorAttachment);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F,
                w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        // 深度纹理
        this.depthAttachment = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.depthAttachment);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24,
                w, h, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

        // FBO
        this.fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, this.colorAttachment, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, this.depthAttachment, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            System.err.println("[CameraMod] HDR FBO 不完整: " + status);
        } else {
            System.out.println("[CameraMod] HDR framebuffer 创建成功: " + w + "x" + h);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }
}