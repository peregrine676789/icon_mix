package iconmix.util;

import java.awt.image.BufferedImage;
import java.awt.image.PixelGrabber;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/**
 * ARGB {@link BufferedImage} → PNG 字节流的手写编码器。
 *
 * <p>之所以不用 {@code ImageIO.write(image, "png", ...)}：本机 JDK 的 AWT/ImageIO
 * 在 Windows 沙箱环境下初始化图形环境不稳定（会出现「无法打开 XPS 文档」类错误），
 * 而本项目只需要「把已知像素写成 PNG」这一件事。手写编码器零依赖、无图形环境，
 * 在任何环境下都能跑通。
 */
public final class PngIO {

    private static final byte[] SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
    };

    private PngIO() {
    }

    /** 把图像编码为 PNG（32bpp RGBA，非隔行）。 */
    public static byte[] encode(BufferedImage image) throws IOException {
        int w = image.getWidth();
        int h = image.getHeight();

        int[] argb = new int[w * h];
        PixelGrabber grabber = new PixelGrabber(image, 0, 0, w, h, argb, 0, w);
        try {
            if (!grabber.grabPixels()) {
                throw new IOException("无法读取图像像素");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("读取图像像素被中断", e);
        }

        boolean hasAlpha = image.getColorModel() != null && image.getColorModel().hasAlpha();

        // 原始扫描线：每行前置 filter type 0
        byte[] raw = new byte[h * (1 + w * 4)];
        int p = 0;
        for (int y = 0; y < h; y++) {
            raw[p++] = 0;
            for (int x = 0; x < w; x++) {
                int c = argb[y * w + x];
                int a = hasAlpha ? ((c >>> 24) & 0xFF) : 0xFF;
                raw[p++] = (byte) ((c >>> 16) & 0xFF);
                raw[p++] = (byte) ((c >>> 8) & 0xFF);
                raw[p++] = (byte) (c & 0xFF);
                raw[p++] = (byte) a;
            }
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DeflaterOutputStream deflater = new DeflaterOutputStream(body);
        deflater.write(raw);
        deflater.finish();
        deflater.close();
        byte[] compressed = body.toByteArray();

        LittleEndian.Writer out = new LittleEndian.Writer();
        out.raw(SIGNATURE);
        out.raw(chunk("IHDR", ihdr(w, h)));
        out.raw(chunk("IDAT", compressed));
        out.raw(chunk("IEND", new byte[0]));
        return out.toByteArray();
    }

    private static byte[] ihdr(int w, int h) {
        byte[] b = new byte[13];
        // IHDR 的宽高同样是大端
        b[0] = (byte) ((w >>> 24) & 0xFF);
        b[1] = (byte) ((w >>> 16) & 0xFF);
        b[2] = (byte) ((w >>> 8) & 0xFF);
        b[3] = (byte) (w & 0xFF);
        b[4] = (byte) ((h >>> 24) & 0xFF);
        b[5] = (byte) ((h >>> 16) & 0xFF);
        b[6] = (byte) ((h >>> 8) & 0xFF);
        b[7] = (byte) (h & 0xFF);
        b[8] = 8;   // bit depth
        b[9] = 6;   // color type: truecolor + alpha
        b[10] = 0;  // compression
        b[11] = 0;  // filter
        b[12] = 0;  // interlace
        return b;
    }

    private static byte[] chunk(String type, byte[] data) {
        LittleEndian.Writer out = new LittleEndian.Writer();
        // PNG 的块长度与 CRC 均为<b>大端</b>（ICO/DIB 才是小端，不要混用）
        out.be32(data.length);
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.raw(typeBytes);
        out.raw(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.be32((int) crc.getValue());
        return out.toByteArray();
    }
}
