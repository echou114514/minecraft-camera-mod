package com.example.camera.client;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Small wrapper around glReadPixels. Mods and shader packs commonly leave a
 * pixel-pack buffer or a non-zero pack row length behind, which makes a plain
 * glReadPixels write into the wrong place or fail. Every read here snapshots
 * and restores the relevant pixel-store state and unbinds any pack buffer.
 */
final class GlReadback {
    private GlReadback() {
    }

    static boolean readRgbaFloat(int fbo, int width, int height, FloatBuffer dst) {
        return readRgbaFloat(fbo, 0, 0, width, height, dst);
    }

    static boolean readRgbaFloat(int fbo, int x, int y, int width, int height, FloatBuffer dst) {
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int previousRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int previousSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int previousSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int previousBuffer = GL11.glGetInteger(GL30.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            if (previousBuffer != 0) {
                GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
            }
            preparePackState();
            GL11.glReadPixels(x, y, width, height, GL11.GL_RGBA, GL11.GL_FLOAT, dst);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            restorePackState(previousAlignment, previousRowLength, previousSkipPixels, previousSkipRows);
            if (previousBuffer != 0) {
                GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, previousBuffer);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousFramebuffer);
        }
    }

    static boolean readRgbaBytes(int fbo, int width, int height, ByteBuffer dst) {
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int previousRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int previousSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int previousSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int previousBuffer = GL11.glGetInteger(GL30.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            if (previousBuffer != 0) {
                GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
            }
            preparePackState();
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, dst);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            restorePackState(previousAlignment, previousRowLength, previousSkipPixels, previousSkipRows);
            if (previousBuffer != 0) {
                GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, previousBuffer);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousFramebuffer);
        }
    }

    /**
     * A depth read can "succeed" and still contain garbage: with shader packs
     * the vanilla depth attachment is often unused, and some mods clear it to a
     * constant. Depth of field on flat depth smears the whole frame, so treat a
     * buffer without any variation as unusable.
     */
    static boolean hasDepthVariation(FloatBuffer depth) {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < depth.capacity(); i += 1024) {
            float v = depth.get(i);
            if (!Float.isFinite(v)) continue;
            if (v < min) min = v;
            if (v > max) max = v;
            if (max - min > 1e-5f) return true;
        }
        return false;
    }

    static boolean readDepthFloat(int fbo, int width, int height, FloatBuffer dst) {
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int previousRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int previousSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int previousSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int previousBuffer = GL11.glGetInteger(GL30.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            if (previousBuffer != 0) {
                GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
            }
            GL11.glReadBuffer(GL11.GL_NONE);
            preparePackState();
            GL11.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, dst);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            restorePackState(previousAlignment, previousRowLength, previousSkipPixels, previousSkipRows);
            if (previousBuffer != 0) {
                GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, previousBuffer);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousFramebuffer);
        }
    }

    private static void preparePackState() {
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
    }

    private static void restorePackState(int alignment, int rowLength, int skipPixels, int skipRows) {
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, rowLength);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
    }
}
