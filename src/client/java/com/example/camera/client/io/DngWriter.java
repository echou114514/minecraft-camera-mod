package com.example.camera.client.io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

/**
 * 极简 DNG 1.5 写入器：LinearRaw（PhotometricInterpretation = 34892）。
 *
 * <p>16-bit 场景线性 RGB（曝光/白平衡后、tonemap 前），可直接被
 * darktable/Lightroom/CaptureOne 当 raw 打开调色。附带 DNG 1.5 的
 * DepthMap（16-bit 归一化深度 + Near/Far/Units 元数据）——若验证工具
 * 不认可则按「如果支持」降级为不带深度的 DNG。</p>
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

    public static void writeLinearRawDng(File file, FloatBuffer rgb, FloatBuffer depth,
                                         int w, int h, float near, float far, boolean hasDepth,
                                         int iso, int shutter, float aperture, float focal, float ev,
                                         int meteringMode, int exposureProgram, int exposureMode,
                                         boolean wbAuto, String lensModel, String cameraModel, String dateStr) throws IOException {
        byte[] make = pad("Minecraft");
        byte[] model = pad(cameraModel);
        byte[] software = pad("MC Camera Mod");
        byte[] date = pad(dateStr);
        byte[] uniqueModel = pad("MC Camera Mod " + cameraModel);
        byte[] lensBytes = pad(lensModel);

        long pixelCount = (long) w * h;
        int pixelBytes = (int) (pixelCount * 6);          // 16-bit RGB
        int depthBytes = hasDepth ? (int) (pixelCount * 2) : 0;
        int shutterNum = shutter > 0 ? 1 : -shutter / 100;
        int shutterDen = shutter > 0 ? shutter : 1;

        // ---- 布局：header + IFD0 + ExifIFD + 数据块 + 像素 + 深度 ----
        int ifd0Entries = hasDepth ? 30 : 24;
        int exifEntries = 12;
        int ifd0Size = 2 + ifd0Entries * 12 + 4;
        int exifSize = 2 + exifEntries * 12 + 4;

        long ifd0Offset = 8;
        long exifOffset = ifd0Offset + ifd0Size;
        long dataStart = exifOffset + exifSize;

        long oBitsPerSample = dataStart;                       // SHORT×3
        long oSampleFormat = oBitsPerSample + 6;               // SHORT×3
        long oColorMatrix1 = oSampleFormat + 6;                // SRATIONAL×9
        long oAsShotNeutral = oColorMatrix1 + 72;              // RATIONAL×3
        long oDngVersion = oAsShotNeutral + 24;                // BYTE×4
        long oDngBackward = oDngVersion + 4;                   // BYTE×4
        long oExifRationals = oDngBackward + 4;                // RATIONAL×4：快门/光圈/焦距/EV
        long oDepthNear = oExifRationals + 32;
        long oDepthFar = oDepthNear + 8;
        long oMake = hasDepth ? oDepthFar + 8 : oDepthFar;     // hasDepth=false 时不写深度块
        long oModel = oMake + make.length;
        long oSoftware = oModel + model.length;
        long oDate = oSoftware + software.length;
        long oUniqueModel = oDate + date.length;
        long oLens = oUniqueModel + uniqueModel.length;
        long oDepthMap = hasDepth ? oLens + lensBytes.length : 0;
        long oPixels = (hasDepth ? oDepthMap + depthBytes : oLens + lensBytes.length);

        // ---- 像素：16-bit 线性 RGB（自上而下）+ 归一化深度 ----
        ByteBuffer pixels = ByteBuffer.allocate(pixelBytes).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer dmap = hasDepth ? ByteBuffer.allocate(depthBytes).order(ByteOrder.LITTLE_ENDIAN) : null;
        float span = Math.max(1e-3f, far - near);
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
                if (hasDepth) {
                    float z = depth.get(srcRow / 4 + x);
                    z = Math.max(near, Math.min(far, Float.isNaN(z) ? far : z));
                    dmap.putShort((short) Math.round((z - near) / span * 65535f));
                }
            }
        }

        try (FileOutputStream fos = new FileOutputStream(file);
             FileChannel ch = fos.getChannel()) {

            // ---- TIFF header ----
            ByteBuffer hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            hdr.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt((int) ifd0Offset);
            hdr.flip();
            ch.write(hdr);

            // ---- IFD0（tag 升序）----
            ByteBuffer ifd = ByteBuffer.allocate(ifd0Size).order(ByteOrder.LITTLE_ENDIAN);
            ifd.putShort((short) ifd0Entries);
            entry(ifd, 254, 4, 1, 0);                                     // NewSubfileType
            entry(ifd, 256, 4, 1, w);                                     // ImageWidth
            entry(ifd, 257, 4, 1, h);                                     // ImageLength
            entry(ifd, 258, 3, 3, oBitsPerSample);                        // BitsPerSample
            entry(ifd, 259, 3, 1, 1);                                     // Compression = none
            entry(ifd, 262, 3, 1, 34892);                                 // LinearRaw
            entry(ifd, 271, 2, make.length, oMake);                       // Make
            entry(ifd, 272, 2, model.length, oModel);                     // Model
            entry(ifd, 273, 4, 1, oPixels);                               // StripOffsets
            entry(ifd, 277, 3, 1, 3);                                     // SamplesPerPixel
            entry(ifd, 278, 4, 1, h);                                     // RowsPerStrip
            entry(ifd, 279, 4, 1, pixelBytes);                            // StripByteCounts
            entry(ifd, 305, 2, software.length, oSoftware);               // Software
            entry(ifd, 306, 2, date.length, oDate);                       // DateTime
            entry(ifd, 339, 3, 3, oSampleFormat);                         // SampleFormat = uint
            entry(ifd, 34665, 4, 1, exifOffset);                          // ExifIFD
            entry(ifd, 50706, 1, 4, oDngVersion);                         // DNGVersion
            entry(ifd, 50707, 1, 4, oDngBackward);                        // DNGBackwardVersion
            entry(ifd, 50708, 2, uniqueModel.length, oUniqueModel);       // UniqueCameraModel
            entry(ifd, 50714, 3, 1, 0);                                   // BlackLevel
            entry(ifd, 50717, 4, 1, 65535);                               // WhiteLevel
            entry(ifd, 50721, 10, 9, oColorMatrix1);                      // ColorMatrix1
            entry(ifd, 50728, 5, 3, oAsShotNeutral);                      // AsShotNeutral
            entry(ifd, 50778, 3, 1, 21);                                  // CalibrationIlluminant1 = D65
            if (hasDepth) {
                entry(ifd, 52526, 3, 1, 1);                               // DepthFormat = Range map
                entry(ifd, 52527, 5, 1, oDepthNear);                      // DepthNear
                entry(ifd, 52528, 5, 1, oDepthFar);                       // DepthFar
                entry(ifd, 52529, 3, 1, 1);                               // DepthUnits = meters
                entry(ifd, 52530, 3, 1, 2);                               // DepthMeasureType = radial
                entry(ifd, 52531, 7, depthBytes, oDepthMap);              // DepthMap
            }
            ifd.putInt(0);
            ifd.flip();
            ch.write(ifd);

            // ---- ExifIFD ----
            ByteBuffer exif = ByteBuffer.allocate(exifSize).order(ByteOrder.LITTLE_ENDIAN);
            exif.putShort((short) exifEntries);
            entry(exif, 33434, 5, 1, oExifRationals);                     // ExposureTime
            entry(exif, 33437, 5, 1, oExifRationals + 8);                 // FNumber
            entry(exif, 34850, 3, 1, exposureProgram);
            entry(exif, 34855, 3, 1, iso);
            entry(exif, 36867, 2, date.length, oDate);                    // DateTimeOriginal
            entry(exif, 36868, 2, date.length, oDate);                    // DateTimeDigitized
            entry(exif, 37380, 10, 1, oExifRationals + 24);               // ExposureCompensation
            entry(exif, 37383, 3, 1, meteringMode);
            entry(exif, 37386, 5, 1, oExifRationals + 16);                // FocalLength
            entry(exif, 41986, 3, 1, exposureMode);
            entry(exif, 41987, 3, 1, wbAuto ? 0 : 1);
            entry(exif, 42036, 2, lensBytes.length, oLens);               // LensModel
            exif.putInt(0);
            exif.flip();
            ch.write(exif);

            // ---- 数据块 ----
            ByteBuffer extras = ByteBuffer.allocate((int) (oPixels - dataStart))
                    .order(ByteOrder.LITTLE_ENDIAN);
            extras.putShort((short) 16).putShort((short) 16).putShort((short) 16);      // BitsPerSample
            extras.putShort((short) 1).putShort((short) 1).putShort((short) 1);         // SampleFormat = uint
            for (int[] row : COLOR_MATRIX1) {
                for (int v : row) {
                    extras.putInt(v).putInt(10000);                                     // SRATIONAL
                }
            }
            extras.putInt(1).putInt(1);                                                 // AsShotNeutral R/G/B
            extras.put(dngVersionBytes(1, 5));                                          // DNGVersion
            extras.put(dngVersionBytes(1, 1));                                          // DNGBackwardVersion
            extras.putInt(shutterNum).putInt(shutterDen);                               // ExposureTime
            extras.putInt((int) (aperture * 100)).putInt(100);                          // FNumber
            extras.putInt((int) (focal * 100)).putInt(100);                             // FocalLength
            extras.putInt((int) (ev * 100)).putInt(100);                                // ExposureCompensation
            if (hasDepth) {
                extras.putInt((int) near * 100).putInt(100);                            // DepthNear
                extras.putInt((int) far * 100).putInt(100);                             // DepthFar
            }
            extras.put(make).put(model).put(software).put(date).put(uniqueModel).put(lensBytes);
            extras.flip();
            while (extras.hasRemaining()) ch.write(extras);

            // ---- 像素与深度 ----
            pixels.flip();
            while (pixels.hasRemaining()) ch.write(pixels);
            if (dmap != null) {
                dmap.flip();
                while (dmap.hasRemaining()) ch.write(dmap);
            }
        }
    }

    private static byte[] dngVersionBytes(int major, int minor) {
        return new byte[]{(byte) major, (byte) minor, 0, 0};
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

    private static byte[] pad(String s) {
        return (s + "\u0000").getBytes(StandardCharsets.US_ASCII);
    }
}
