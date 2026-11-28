package com.example.camera.client.io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Map;

/**
 * 极简 OpenEXR 写入器：无压缩 scanline、half-float R/G/B + Z（深度，米）。
 *
 * <p>数据为场景线性（曝光/白平衡后、tonemap 前）——这就是 EXR 的价值：
 * 可直接进 Blender/Nuke 调色合成。深度通道为视线距离（世界方块单位），
 * 供深度合成与再聚焦。</p>
 *
 * <p>仅支持我们生成的这一种布局（NO_COMPRESSION + INCREASING_Y），
 * 足够 darktable/Blender/OIIO 正确读取。</p>
 */
public final class ExrWriter {

    private ExrWriter() {
    }

    /** 通道名顺序即 chunk 内数据顺序。 */
    private static final String[] CHANNELS = {"R", "G", "B", "Z"};

    /**
     * @param rgb        线性 RGB（未翻转，行 0 = 画面底部）
     * @param depth      视线距离（方块），与 rgb 同布局；null 时 Z 填 far
     * @param near/far   深度范围（方块），写进 Z 的边界与元数据
     * @param metadata   自定义 string 属性（camera/lens/software 等）
     */
    public static void writeRgbHalfZ(File file, FloatBuffer rgb, FloatBuffer depth,
                                     int w, int h, float near, float far,
                                     Map<String, String> metadata) throws IOException {
        int channels = CHANNELS.length;
        int pixelBytes = channels * 2;                       // 全 half
        int lineBytes = w * pixelBytes;
        long dataSize = (long) h * lineBytes;

        // ---- header ----
        ByteBuffer hdr = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);
        hdr.put((byte) 0x76).put((byte) 0x2f).put((byte) 0x31).put((byte) 0x01);
        hdr.putInt(2);                                       // version 2 = scanline

        putBytesAttr(hdr, "channels", "chlist", buildChlist());
        putIntAttr(hdr, "compression", "compression", 0);    // NO_COMPRESSION
        putBoxAttr(hdr, "dataWindow", 0, 0, w - 1, h - 1);
        putBoxAttr(hdr, "displayWindow", 0, 0, w - 1, h - 1);
        putIntAttr(hdr, "lineOrder", "lineOrder", 0);        // INCREASING_Y
        putFloatAttr(hdr, "pixelAspectRatio", 1.0f);
        putV2fAttr(hdr, "screenWindowCenter", 0f, 0f);
        putFloatAttr(hdr, "screenWindowWidth", 1.0f);
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            putStringAttr(hdr, e.getKey(), "string", e.getValue());
        }
        hdr.put((byte) 0);                                   // header 结束

        int headerSize = hdr.position();
        int offsetTableSize = h * 8;
        long pixelStart = headerSize + offsetTableSize;

        // ---- 文件骨架：header + offset table（缓冲必须容纳两者）----
        ByteBuffer out = ByteBuffer.allocate(headerSize + offsetTableSize).order(ByteOrder.LITTLE_ENDIAN);
        out.put((ByteBuffer) hdr.flip());
        for (int y = 0; y < h; y++) {
            out.putLong(pixelStart + (long) y * (8 + lineBytes));
        }
        try (FileOutputStream fos = new FileOutputStream(file);
             var ch = fos.getChannel()) {
            out.flip();
            while (out.hasRemaining()) ch.write(out);

            // ---- 扫描线 chunk（自上而下写：行 y 读线性缓冲的第 h-1-y 行）----
            ByteBuffer line = ByteBuffer.allocate(lineBytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int y = 0; y < h; y++) {
                int srcRow = (h - 1 - y) * w * 4;
                line.clear();
                for (int x = 0; x < w; x++) {
                    int s = srcRow + x * 4;
                    float r = sanitize(rgb.get(s));
                    float g = sanitize(rgb.get(s + 1));
                    float b = sanitize(rgb.get(s + 2));
                    float z = (depth != null)
                            ? sanitize(Math.max(near, Math.min(far, depth.get(srcRow / 4 + x))))
                            : far;
                    line.putShort(floatToHalf(r));
                    line.putShort(floatToHalf(g));
                    line.putShort(floatToHalf(b));
                    line.putShort(floatToHalf(z));
                }
                line.flip();
                ByteBuffer chunk = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                chunk.putInt(y).putInt(lineBytes).flip();
                while (chunk.hasRemaining()) ch.write(chunk);
                while (line.hasRemaining()) ch.write(line);
            }
        }
    }

    private static float sanitize(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 0f;
        return Math.max(0f, Math.min(65504f, v));
    }

    /** chlist：每通道 name\0 + int32 类型(1=half) + pLinear+3pad + int32 xSampling + int32 ySampling，整体 \\0 结尾。 */
    private static byte[] buildChlist() {
        ByteBuffer b = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        for (String ch : CHANNELS) {
            b.put(ch.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).put((byte) 0);
            b.putInt(1);        // HALF
            b.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0); // pLinear + reserved
            b.putInt(1);        // xSampling
            b.putInt(1);        // ySampling
        }
        b.put((byte) 0);
        byte[] out = new byte[b.position()];
        b.flip();
        b.get(out);
        return out;
    }

    private static void putBytesAttr(ByteBuffer b, String name, String type, byte[] payload) {
        putAttrNameAndType(b, name, type);
        b.putInt(payload.length);
        b.put(payload);
    }

    private static void putStringAttr(ByteBuffer b, String name, String type, String value) {
        putAttrNameAndType(b, name, type);
        byte[] v = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        b.putInt(v.length + 1);
        b.put(v).put((byte) 0);
    }

    private static void putIntAttr(ByteBuffer b, String name, String type, int value) {
        putAttrNameAndType(b, name, type);
        b.putInt(4).putInt(value);
    }

    private static void putFloatAttr(ByteBuffer b, String name, float value) {
        putAttrNameAndType(b, name, "float");
        b.putInt(4).putFloat(value);
    }

    private static void putV2fAttr(ByteBuffer b, String name, float x, float y) {
        putAttrNameAndType(b, name, "v2f");
        b.putInt(8).putFloat(x).putFloat(y);
    }

    private static void putBoxAttr(ByteBuffer b, String name, int xMin, int yMin, int xMax, int yMax) {
        putAttrNameAndType(b, name, "box2i");
        b.putInt(16).putInt(xMin).putInt(yMin).putInt(xMax).putInt(yMax);
    }

    private static void putAttrNameAndType(ByteBuffer b, String name, String type) {
        b.put(name.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).put((byte) 0);
        b.put(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).put((byte) 0);
    }

    // ------------------------------------------------------------------
    // float -> half（IEEE 754 二进制16，含 NaN/Inf 处理）
    // ------------------------------------------------------------------

    public static short floatToHalf(float f) {
        int bits = Float.floatToIntBits(f);
        int sign = (bits >>> 16) & 0x8000;
        int exp = (bits >>> 23) & 0xFF;
        int man = bits & 0x7FFFFF;

        if (exp == 0xFF) {                                   // Inf/NaN
            return (short) (sign | 0x7C00 | (man != 0 ? 0x0200 : 0));
        }
        int hExp = exp - 127 + 15;
        if (hExp >= 0x1F) return (short) (sign | 0x7C00);    // 溢出 -> Inf
        if (hExp <= 0) {                                     // 次正常/零
            if (hExp < -10) return (short) sign;
            man = (man | 0x800000) >> (1 - hExp);
            return (short) (sign | (man >> 13));
        }
        return (short) (sign | (hExp << 10) | (man >> 13));
    }
}
