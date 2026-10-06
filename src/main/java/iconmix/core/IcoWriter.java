package iconmix.core;

import iconmix.extract.FrameDecoder;
import iconmix.util.LittleEndian;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * ICO 写出（FR-4.8）。
 *
 * <p>格式要点：
 * <ul>
 *   <li>容器：ICONDIR(6) + n × ICONDIRENTRY(16) + 各帧数据，全部<b>小端</b>（T-4）</li>
 *   <li>帧：标准 BMP/DIB —— BITMAPINFOHEADER(40) + XOR 色彩数据 + AND 掩码，
 *       {@code biHeight} 为实际高度的<b>两倍</b>，像素自下而上（T-3）</li>
 *   <li>不用 PNG 压缩帧：资源管理器能认，但 {@code System.Drawing} 解析会抛异常</li>
 * </ul>
 */
public final class IcoWriter {

    /** 默认尺寸集合（FR-4.8.1）。 */
    public static final int[] DEFAULT_SIZES = {256, 128, 64, 48, 32, 16};

    private IcoWriter() {
    }

    /**
     * 由一张合成图生成多尺寸 ICO。
     *
     * @param source    合成图（方形）
     * @param sizes     目标尺寸，需要按由大到小排列
     * @param perSize   每个尺寸的独立渲染器；为 null 时直接从 source 缩放
     */
    public static byte[] write(BufferedImage source, int[] sizes, SizeRenderer perSize) throws IOException {
        int count = sizes.length;
        byte[][] frames = new byte[count][];
        for (int i = 0; i < count; i++) {
            BufferedImage img = perSize != null ? perSize.render(sizes[i]) : source;
            if (img.getWidth() != sizes[i] || img.getHeight() != sizes[i]) {
                img = Renderer.smoothScale(img, sizes[i], sizes[i]);
            }
            frames[i] = encodeDib(img);
        }

        int headerSize = 6 + count * 16;
        int total = headerSize;
        for (byte[] f : frames) {
            total += f.length;
        }

        LittleEndian.Writer out = new LittleEndian.Writer();
        out.u16(0);            // Reserved
        out.u16(1);            // Type: 1 = icon
        out.u16(count);        // Count

        int offset = headerSize;
        for (int i = 0; i < count; i++) {
            int size = sizes[i];
            out.u8(size >= 256 ? 0 : size);       // bWidth，0 表示 256
            out.u8(size >= 256 ? 0 : size);       // bHeight
            out.u8(0);                            // bColorCount
            out.u8(0);                            // bReserved
            out.u16(1);                           // wPlanes
            out.u16(32);                          // wBitCount
            out.u32(frames[i].length);            // dwBytesInRes
            out.u32(offset);                      // dwImageOffset
            offset += frames[i].length;
        }
        for (int i = 0; i < count; i++) {
            out.raw(frames[i]);
        }
        if (out.size() != total) {
            throw new IOException("ICO 尺寸不一致: " + out.size() + " != " + total);
        }
        return out.toByteArray();
    }

    public interface SizeRenderer {
        BufferedImage render(int size) throws IOException;
    }

    /**
     * 把一张 ARGB 图编码成 ICO 用的 DIB 帧：40 字节头 + 自下而上的 BGRA + AND 掩码。
     */
    public static byte[] encodeDib(BufferedImage image) throws IOException {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) {
            throw new IOException("图像尺寸非法: " + w + "x" + h);
        }

        int[] px = new int[w * h];
        image.getRGB(0, 0, w, h, px, 0, w);

        int xorStride = w * 4;
        int andStride = ((w + 31) / 32) * 4;
        int xorSize = xorStride * h;
        int andSize = andStride * h;

        byte[] out = new byte[40 + xorSize + andSize];
        LittleEndian.putInt(out, 0, 40);                  // biSize
        LittleEndian.putInt(out, 4, w);                   // biWidth
        LittleEndian.putInt(out, 8, h * 2);               // biHeight = 两倍
        LittleEndian.putShort(out, 12, 1);                // biPlanes
        LittleEndian.putShort(out, 14, 32);               // biBitCount
        LittleEndian.putInt(out, 16, 0);                  // biCompression = BI_RGB
        LittleEndian.putInt(out, 20, xorSize + andSize);  // biSizeImage
        LittleEndian.putInt(out, 24, 0);                  // biXPelsPerMeter
        LittleEndian.putInt(out, 28, 0);                  // biYPelsPerMeter
        LittleEndian.putInt(out, 32, 0);                  // biClrUsed
        LittleEndian.putInt(out, 36, 0);                  // biClrImportant

        int xorBase = 40;
        int andBase = 40 + xorSize;

        for (int y = 0; y < h; y++) {
            int srcRow = y;
            int dstRow = h - 1 - y;                       // 自下而上
            int dst = xorBase + dstRow * xorStride;
            int maskRow = andBase + dstRow * andStride;
            for (int x = 0; x < w; x++) {
                int c = px[srcRow * w + x];
                int a = (c >>> 24) & 0xFF;
                out[dst + x * 4] = (byte) (c & 0xFF);              // B
                out[dst + x * 4 + 1] = (byte) ((c >>> 8) & 0xFF);  // G
                out[dst + x * 4 + 2] = (byte) ((c >>> 16) & 0xFF); // R
                out[dst + x * 4 + 3] = (byte) a;                   // A
                if (a == 0) {
                    int byteIndex = maskRow + (x >> 3);
                    out[byteIndex] |= (byte) (0x80 >> (x & 7));    // 1 = 透明
                }
            }
        }
        return out;
    }

    /** 校验用的尺寸解析：读取 ICO 头，返回 [w, h, offset, bytes] 列表。 */
    public static java.util.List<int[]> inspect(byte[] ico) {
        return FrameDecoder.listFrames(ico);
    }
}
