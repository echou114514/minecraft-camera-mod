package com.example.camera.client;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

public class TiffWriter {

    public static void writeRgb16(File file, short[] rgb16, int w, int h) throws IOException {
        int dataSize = w * h * 3 * 2;
        int entries = 12;
        int ifdSize = 2 + entries * 12 + 4;
        long ifdOffset = 8;
        long dataOffset = ifdOffset + ifdSize;
        long bpsOffset = dataOffset + dataSize;
        long sfOffset = bpsOffset + 6;

        try (FileOutputStream fos = new FileOutputStream(file);
             FileChannel ch = fos.getChannel()) {

            ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            header.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt((int) ifdOffset);
            header.flip();
            ch.write(header);

            ByteBuffer ifd = ByteBuffer.allocate(ifdSize).order(ByteOrder.LITTLE_ENDIAN);
            ifd.putShort((short) entries);
            putEntry(ifd, 256, 3, 1, w);
            putEntry(ifd, 257, 3, 1, h);
            putEntry(ifd, 258, 3, 3, bpsOffset);
            putEntry(ifd, 259, 3, 1, 1);
            putEntry(ifd, 262, 3, 1, 2);
            putEntry(ifd, 273, 4, 1, dataOffset);
            putEntry(ifd, 277, 3, 1, 3);
            putEntry(ifd, 278, 3, 1, h);
            putEntry(ifd, 279, 4, 1, dataSize);
            putEntry(ifd, 282, 5, 1, 0);
            putEntry(ifd, 283, 5, 1, 0);
            putEntry(ifd, 339, 3, 3, sfOffset);
            ifd.putInt(0);
            ifd.flip();
            ch.write(ifd);

            ByteBuffer data = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < w * h; i++) {
                data.putShort(rgb16[i * 3]);
                data.putShort(rgb16[i * 3 + 1]);
                data.putShort(rgb16[i * 3 + 2]);
            }
            data.flip();
            ch.write(data);

            ByteBuffer bps = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
            bps.putShort((short) 16).putShort((short) 16).putShort((short) 16);
            bps.flip();
            ch.position(bpsOffset);
            ch.write(bps);

            ByteBuffer sf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
            sf.putShort((short) 1).putShort((short) 1).putShort((short) 1);
            sf.flip();
            ch.position(sfOffset);
            ch.write(sf);
        }
    }

    public static void writeRgb32fBuffer(File file, ByteBuffer rgbData, int w, int h,
                                         int iso, int shutter, float aperture, float focal, float ev,
                                         int wbValue, int meteringMode, int exposureProgram, int exposureMode,
                                         boolean wbAuto, String lensModel, String cameraModel, String dateStr) throws IOException {
        byte[] makeBytes = padEven("Minecraft".getBytes(StandardCharsets.US_ASCII));
        byte[] modelBytes = padEven(cameraModel.getBytes(StandardCharsets.US_ASCII));
        byte[] dateBytes = padEven(dateStr.getBytes(StandardCharsets.US_ASCII));
        byte[] lensBytes = padEven(lensModel.getBytes(StandardCharsets.US_ASCII));

        int dataSize = w * h * 3 * 4;
        int ifd0Entries = 14;
        int ifd0Size = 2 + ifd0Entries * 12 + 4;
        int exifEntries = 13;
        int exifSize = 2 + exifEntries * 12 + 4;

        long ifd0Offset = 8;
        long exifIfdOffset = ifd0Offset + ifd0Size;
        long dataOffset = exifIfdOffset + exifSize;

        long bpsOffset = dataOffset;
        long sfOffset = bpsOffset + 6;
        long expOffset = sfOffset + 6;
        long fnumOffset = expOffset + 8;
        long focalOffset = fnumOffset + 8;
        long evOffset = focalOffset + 8;
        long dateOffset = evOffset + 8;
        long makeOffset = dateOffset + dateBytes.length;
        long modelOffset = makeOffset + makeBytes.length;
        long lensOffset = modelOffset + modelBytes.length;
        long pixelOffset = lensOffset + lensBytes.length;

        try (FileOutputStream fos = new FileOutputStream(file);
             FileChannel ch = fos.getChannel()) {

            ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            header.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt((int) ifd0Offset);
            header.flip();
            ch.write(header);

            ByteBuffer ifd0 = ByteBuffer.allocate(ifd0Size).order(ByteOrder.LITTLE_ENDIAN);
            ifd0.putShort((short) ifd0Entries);
            putEntry(ifd0, 256, 3, 1, w);
            putEntry(ifd0, 257, 3, 1, h);
            putEntry(ifd0, 258, 3, 3, bpsOffset);
            putEntry(ifd0, 259, 3, 1, 1);
            putEntry(ifd0, 262, 3, 1, 2);
            putEntry(ifd0, 271, 2, makeBytes.length, makeOffset);
            putEntry(ifd0, 272, 2, modelBytes.length, modelOffset);
            putEntry(ifd0, 273, 4, 1, pixelOffset);
            putEntry(ifd0, 277, 3, 1, 3);
            putEntry(ifd0, 278, 4, 1, h);
            putEntry(ifd0, 279, 4, 1, dataSize);
            putEntry(ifd0, 306, 2, dateBytes.length, dateOffset);
            putEntry(ifd0, 339, 3, 3, sfOffset);
            putEntry(ifd0, 34665, 4, 1, exifIfdOffset);
            ifd0.putInt(0);
            ifd0.flip();
            ch.write(ifd0);

            ByteBuffer exifIfd = ByteBuffer.allocate(exifSize).order(ByteOrder.LITTLE_ENDIAN);
            exifIfd.putShort((short) exifEntries);
            putEntry(exifIfd, 33434, 5, 1, expOffset);
            putEntry(exifIfd, 33437, 5, 1, fnumOffset);
            putEntry(exifIfd, 34850, 3, 1, exposureProgram);
            putEntry(exifIfd, 34855, 3, 1, iso);
            putEntry(exifIfd, 36867, 2, dateBytes.length, dateOffset);
            putEntry(exifIfd, 36868, 2, dateBytes.length, dateOffset);
            putEntry(exifIfd, 37380, 10, 1, evOffset);
            putEntry(exifIfd, 37383, 3, 1, meteringMode);
            putEntry(exifIfd, 37386, 5, 1, focalOffset);
            putEntry(exifIfd, 40961, 3, 1, 1);
            putEntry(exifIfd, 41986, 3, 1, exposureMode);
            putEntry(exifIfd, 41987, 3, 1, wbAuto ? 0 : 1);
            putEntry(exifIfd, 42036, 2, lensBytes.length, lensOffset);
            exifIfd.putInt(0);
            exifIfd.flip();
            ch.write(exifIfd);

            ch.position(bpsOffset);
            ch.write(ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort((short)32).putShort((short)32).putShort((short)32).flip());

            ch.position(sfOffset);
            ch.write(ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort((short)3).putShort((short)3).putShort((short)3).flip());

            ch.position(expOffset);
            ByteBuffer exp = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            if (shutter > 0) exp.putInt(1).putInt(shutter); else exp.putInt(-shutter / 100).putInt(1);
            exp.flip(); ch.write(exp);

            ch.position(fnumOffset);
            ch.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt((int)(aperture * 100)).putInt(100).flip());

            ch.position(focalOffset);
            ch.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt((int)(focal * 100)).putInt(100).flip());

            ch.position(evOffset);
            ch.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt((int)(ev * 100)).putInt(100).flip());

            ch.position(dateOffset); ch.write(ByteBuffer.wrap(dateBytes));
            ch.position(makeOffset); ch.write(ByteBuffer.wrap(makeBytes));
            ch.position(modelOffset); ch.write(ByteBuffer.wrap(modelBytes));
            ch.position(lensOffset); ch.write(ByteBuffer.wrap(lensBytes));

            ch.position(pixelOffset);
            ch.write(rgbData);
        }
    }

    private static byte[] padEven(byte[] b) {
        if (b.length % 2 == 1) {
            byte[] out = new byte[b.length + 1];
            System.arraycopy(b, 0, out, 0, b.length);
            return out;
        }
        return b;
    }

    private static void putEntry(ByteBuffer buf, int tag, int type, int count, long value) {
        buf.putShort((short) tag);
        buf.putShort((short) type);
        buf.putInt(count);
        if (type == 3 && count == 1) {
            buf.putShort((short) value).putShort((short) 0);
        } else {
            buf.putInt((int) value);
        }
    }
}