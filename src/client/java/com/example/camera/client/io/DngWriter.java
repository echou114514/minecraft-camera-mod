package com.example.camera.client.io;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 DNG 1.5 写入器：LinearRaw（PhotometricInterpretation = 34892）。
 *
 * <p>16-bit 场景线性 RGB（曝光/白平衡后、tonemap 前），可直接被
 * darktable/Lightroom/CaptureOne 当 raw 打开调色。附带 DNG 1.5 的
 * DepthMap（16-bit 归一化深度 + Near/Far/Units 元数据）。</p>
 *
 * <p>布局策略：先把全部 out-of-line 数据块写进一个 extras 流并记录实际偏移
 * 与长度，再用它们生成 IFD 条目——偏移与内容天然一致，不会错位。</p>
 */
public final class DngWriter {

    private DngWriter() {
    }

    /** sRGB（D65）→ XYZ 的标准 ColorMatrix1，SRATIONAL ×10000。 */
    private static final int[][] COLOR_MATRIX1 = {
            {4124564, -3575761, -1804375},
            {2126729, 7151522, 721750},
            {1933390, -1191930, 9503041},
    };

    /** extras 流里的一个命名数据块（相对偏移 + 字节数）。 */
    private record Block(int rel, int len) {}

    private static final class Extras {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final Map<String, Block> blocks = new LinkedHashMap<>();

        void put(String name, java.util.function.Consumer<Extras> writer) {
            int start = buf.size();
            writer.accept(this);
            blocks.put(name, new Block(start, buf.size() - start));
        }

        void raw(byte[] bytes) {
            buf.writeBytes(bytes);
        }

        void rational(int num, int den) {
            for (int v : new int[]{num, den}) {
                buf.write(v & 0xFF);
                buf.write((v >> 8) & 0xFF);
                buf.write((v >> 16) & 0xFF);
                buf.write((v >> 24) & 0xFF);
            }
        }

        void pad(String s) {
            buf.writeBytes((s + "\u0000").getBytes(StandardCharsets.US_ASCII));
        }
    }

    public static void writeLinearRawDng(File file, FloatBuffer rgb, FloatBuffer depth,
                                         int w, int h, float near, float far, boolean hasDepth,
                                         int iso, int shutter, float aperture, float focal, float ev,
                                         int meteringMode, int exposureProgram, int exposureMode,
                                         boolean wbAuto, String lensModel, String cameraModel, String dateStr) throws IOException {
        int pixelBytes = w * h * 6;
        int depthBytes = hasDepth ? w * h * 2 : 0;
        int ifdEntries = hasDepth ? 30 : 24;
        int exifEntries = 12;
        int ifd0Size = 2 + ifdEntries * 12 + 4;
        int exifSize = 2 + exifEntries * 12 + 4;
        int exifOffset = 8 + ifd0Size;
        int dataStart = exifOffset + exifSize;

        // ---- 像素：16-bit 线性 RGB（自上而下）----
        ByteBuffer pixels = ByteBuffer.allocate(pixelBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < h; y++) {
            int srcRow = (h - 1 - y) * w * 4;
            for (int x = 0; x < w; x++) {
                int s = srcRow + x * 4;
                for (int c = 0; c < 3; c++) {
                    float v = rgb.get(s + c);
                    if (Float.isNaN(v) || v < 0f) v = 0f;
                    if (v > 1f) v = 1f;
                    pixels.putShort((short) Math.round(v * 65535f));
                }
            }
        }

        // ---- 深度：16-bit 归一化（near..far -> 0..65535）----
        ByteBuffer dmap = null;
        if (hasDepth) {
            dmap = ByteBuffer.allocate(depthBytes).order(ByteOrder.LITTLE_ENDIAN);
            float span = Math.max(1e-3f, far - near);
            for (int y = 0; y < h; y++) {
                int srcRow = (h - 1 - y) * w * 4;
                for (int x = 0; x < w; x++) {
                    float z = depth.get(srcRow / 4 + x);
                    z = Math.max(near, Math.min(far, Float.isNaN(z) ? far : z));
                    dmap.putShort((short) Math.round((z - near) / span * 65535f));
                }
            }
        }

        // ---- extras 流：写数据 + 记录偏移/长度 ----
        Extras ex = new Extras();
        ex.put("bitsPerSample", e -> e.raw(new byte[]{16, 0, 16, 0, 16, 0}));
        ex.put("sampleFormat", e -> e.raw(new byte[]{1, 0, 1, 0, 1, 0}));
        ex.put("colorMatrix1", e -> {
            ByteBuffer cm = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN);
            for (int[] row : COLOR_MATRIX1) {
                for (int v : row) {
                    cm.putInt(v).putInt(10000);
                }
            }
            e.raw(cm.array());
        });
        ex.put("asShotNeutral", e -> {
            e.rational(1, 1);
            e.rational(1, 1);
            e.rational(1, 1);
        });

        ex.put("exposureTime", e -> e.rational(shutter > 0 ? 1 : -shutter / 100, shutter > 0 ? shutter : 1));
        ex.put("fNumber", e -> e.rational(Math.round(aperture * 100), 100));
        ex.put("focalLength", e -> e.rational(Math.round(focal * 100), 100));
        ex.put("exposureCompensation", e -> e.rational(Math.round(ev * 100), 100));
        if (hasDepth) {
            ex.put("depthNear", e -> e.rational(Math.round(near * 100), 100));
            ex.put("depthFar", e -> e.rational((int) far, 1));
        }
        ex.put("make", e -> e.pad("Minecraft"));
        ex.put("model", e -> e.pad(cameraModel));
        ex.put("software", e -> e.pad("MC Camera Mod"));
        ex.put("dateTime", e -> e.pad(dateStr));
        ex.put("uniqueCameraModel", e -> e.pad("MC Camera Mod " + cameraModel));
        ex.put("lensModel", e -> e.pad(lensModel));
        final ByteBuffer dmapFinal = dmap;
        if (hasDepth) {
            ex.put("depthMap", e -> e.raw(dmapFinal.array()));
        }
        byte[] extras = ex.buf.toByteArray();
        java.util.Map<String, Long> off = new LinkedHashMap<>();
        ex.blocks.forEach((k, blk) -> off.put(k, dataStart + (long) blk.rel()));
        long oPixels = dataStart + extras.length;

        try (FileOutputStream fos = new FileOutputStream(file);
             FileChannel ch = fos.getChannel()) {

            // ---- TIFF header ----
            ByteBuffer hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            hdr.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
            hdr.flip();
            ch.write(hdr);

            // ---- IFD0（tag 升序；value 引用 extras 实际偏移）----
            ByteBuffer ifd = ByteBuffer.allocate(ifd0Size).order(ByteOrder.LITTLE_ENDIAN);
            ifd.putShort((short) ifdEntries);
            entry(ifd, 254, 4, 1, 0);                                     // NewSubfileType
            entry(ifd, 256, 4, 1, w);                                     // ImageWidth
            entry(ifd, 257, 4, 1, h);                                     // ImageLength
            entry(ifd, 258, 3, 3, off.get("bitsPerSample"));              // BitsPerSample
            entry(ifd, 259, 3, 1, 1);                                     // Compression = none
            entry(ifd, 262, 3, 1, 34892);                                 // LinearRaw
            asciiEntry(ifd, 271, off, "make");                            // Make
            asciiEntry(ifd, 272, off, "model");                           // Model
            entry(ifd, 273, 4, 1, oPixels);                               // StripOffsets
            entry(ifd, 277, 3, 1, 3);                                     // SamplesPerPixel
            entry(ifd, 278, 4, 1, h);                                     // RowsPerStrip
            entry(ifd, 279, 4, 1, pixelBytes);                            // StripByteCounts
            asciiEntry(ifd, 305, off, "software");                        // Software
            asciiEntry(ifd, 306, off, "dateTime");                        // DateTime
            entry(ifd, 339, 3, 3, off.get("sampleFormat"));               // SampleFormat = uint
            entry(ifd, 34665, 4, 1, exifOffset);                          // ExifIFD
            // BYTE×4 ≤ 4 字节：按 TIFF 规范必须内联在 value 字段（LE 打包）。
            entry(ifd, 50706, 1, 4, 0x00000501);                          // DNGVersion 1.5.0.0
            entry(ifd, 50707, 1, 4, 0x00000101);                          // DNGBackwardVersion 1.1.0.0
            asciiEntry(ifd, 50708, off, "uniqueCameraModel");             // UniqueCameraModel
            entry(ifd, 50714, 3, 1, 0);                                   // BlackLevel
            entry(ifd, 50717, 4, 1, 65535);                               // WhiteLevel
            entry(ifd, 50721, 10, 9, off.get("colorMatrix1"));            // ColorMatrix1
            entry(ifd, 50728, 5, 3, off.get("asShotNeutral"));            // AsShotNeutral
            entry(ifd, 50778, 3, 1, 21);                                  // CalibrationIlluminant1 = D65
            if (hasDepth) {
                entry(ifd, 51177, 3, 1, 1);                               // DepthFormat = range map
                entry(ifd, 51178, 5, 1, off.get("depthNear"));            // DepthNear
                entry(ifd, 51179, 5, 1, off.get("depthFar"));             // DepthFar
                entry(ifd, 51180, 3, 1, 1);                               // DepthUnits = meters
                entry(ifd, 51181, 3, 1, 2);                               // DepthMeasureType = radial
                entry(ifd, 51182, 7, depthBytes, off.get("depthMap"));    // DepthMap
            }
            ifd.putInt(0);
            ifd.flip();
            ch.write(ifd);

            // ---- ExifIFD ----
            ByteBuffer exif = ByteBuffer.allocate(exifSize).order(ByteOrder.LITTLE_ENDIAN);
            exif.putShort((short) exifEntries);
            entry(exif, 33434, 5, 1, off.get("exposureTime"));            // ExposureTime
            entry(exif, 33437, 5, 1, off.get("fNumber"));                 // FNumber
            entry(exif, 34850, 3, 1, exposureProgram);
            entry(exif, 34855, 3, 1, iso);
            asciiEntry(exif, 36867, off, "dateTime");                     // DateTimeOriginal
            asciiEntry(exif, 36868, off, "dateTime");                     // DateTimeDigitized
            entry(exif, 37380, 10, 1, off.get("exposureCompensation"));   // ExposureCompensation
            entry(exif, 37383, 3, 1, meteringMode);
            entry(exif, 37386, 5, 1, off.get("focalLength"));             // FocalLength
            entry(exif, 41986, 3, 1, exposureMode);
            entry(exif, 41987, 3, 1, wbAuto ? 0 : 1);
            asciiEntry(exif, 42036, off, "lensModel");                    // LensModel
            exif.putInt(0);
            exif.flip();
            ch.write(exif);

            // ---- extras 数据块 ----
            ch.write(ByteBuffer.wrap(extras));

            // ---- 像素 ----
            pixels.flip();
            while (pixels.hasRemaining()) ch.write(pixels);
        }
    }

    /** ASCII（含结尾 0）的长度 = 下一块起点 - 本块起点。 */
    private static void asciiEntry(ByteBuffer ifd, int tag, java.util.Map<String, Long> off, String key) {
        java.util.List<String> keys = new java.util.ArrayList<>(off.keySet());
        int i = keys.indexOf(key);
        long len = (i + 1 < keys.size() ? off.get(keys.get(i + 1)) : Long.MAX_VALUE) - off.get(key) - 1;
        entry(ifd, tag, 2, (int) len, off.get(key));
    }

    private static void entry(ByteBuffer ifd, int tag, int type, int count, long value) {
        ifd.putShort((short) tag);
        ifd.putShort((short) type);
        ifd.putInt(count);
        if (type == 3 && count == 1) {
            ifd.putShort((short) value).putShort((short) 0);
        } else {
            ifd.putInt((int) value);
        }
    }
}
