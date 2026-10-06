package iconmix.extract;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ICO / PE 内嵌的图标帧解码。
 *
 * <p>支持两种帧：内嵌 PNG（头 8 字节为 PNG 签名）与标准 BMP/DIB 帧。
 * 位深支持 1 / 4 / 8 / 16 / 24 / 32 bpp，并做两处兜底：
 * <ul>
 *   <li>位深为 0 时按 32bpp 猜测（部分图标工具写 0）</li>
 *   <li>32bpp 但 alpha 全 0 时按「颜色有效、透明失效」的旧图标处理，整体置为不透明（FR-4.3.7）</li>
 * </ul>
 */
public final class FrameDecoder {

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
    };

    private FrameDecoder() {
    }

    public static boolean isPng(byte[] data, int off) {
        if (data.length < off + PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[off + i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解码一帧图标。
     *
     * @param data 帧数据（DIB 或 PNG）
     * @param dibIncludesAndMask ICO 容器里的 DIB 帧后面带 AND 掩码；PE 资源里的 RT_ICON 同样带掩码，
     *                           而独立 .bmp 没有。这里统一按「可能存在掩码」处理，掩码缺失时忽略。
     */
    public static BufferedImage decode(byte[] data, boolean dibIncludesAndMask) throws IOException {
        if (isPng(data, 0)) {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null) {
                throw new IOException("PNG 帧解码失败");
            }
            return toArgb(img);
        }
        return decodeDib(data, dibIncludesAndMask);
    }

    /** 从完整 .ico 文件里解出最大的一帧。 */
    public static BufferedImage decodeIco(byte[] ico) throws IOException {
        if (ico.length < 6) {
            throw new IOException("ICO 文件过小");
        }
        int reserved = u16(ico, 0);
        int type = u16(ico, 2);
        int count = u16(ico, 4);
        if (reserved != 0 || type != 1 || count <= 0) {
            throw new IOException("不是合法的 ICO（reserved=" + reserved + " type=" + type + " count=" + count + "）");
        }
        long bestScore = -1;
        byte[] best = null;
        for (int i = 0; i < count; i++) {
            int entry = 6 + i * 16;
            if (entry + 16 > ico.length) {
                break;
            }
            int w = ico[entry] & 0xFF;
            int h = ico[entry + 1] & 0xFF;
            if (w == 0) {
                w = 256;
            }
            if (h == 0) {
                h = 256;
            }
            int bytes = u32(ico, entry + 8);
            int offset = u32(ico, entry + 12);
            if (offset < 0 || bytes < 0 || offset + bytes > ico.length) {
                continue;
            }
            long score = (long) w * h;
            if (score > bestScore) {
                bestScore = score;
                best = new byte[bytes];
                System.arraycopy(ico, offset, best, 0, bytes);
            }
        }
        if (best == null) {
            throw new IOException("ICO 中没有可用的帧");
        }
        return decode(best, true);
    }

    private static BufferedImage decodeDib(byte[] data, boolean hasAndMask) throws IOException {
        if (data.length < 40) {
            throw new IOException("DIB 帧过小");
        }
        int biSize = u32(data, 0);
        if (biSize < 40) {
            throw new IOException("不支持的 BITMAPINFOHEADER 大小: " + biSize);
        }
        int width = u32(data, 4);
        int height2 = u32(data, 8);
        int bitCount = u16(data, 14);
        int compression = u32(data, 16);
        int clrUsed = u32(data, 32);

        if (width <= 0 || height2 <= 0) {
            throw new IOException("DIB 尺寸非法: " + width + "x" + height2);
        }
        int height = height2 / 2;         // ICO/DIB 帧的 biHeight 是实际高度的两倍
        if (height <= 0) {
            height = height2;
        }
        if (compression != 0) {
            throw new IOException("不支持的 DIB 压缩方式: " + compression + "（仅支持 BI_RGB）");
        }
        if (bitCount == 0) {
            bitCount = 32;
        }
        int bytesPerPixel = bitCount / 8;
        if (bytesPerPixel == 0) {
            bytesPerPixel = 1;
        }

        int paletteEntries;
        if (bitCount <= 8) {
            paletteEntries = clrUsed != 0 ? clrUsed : (1 << bitCount);
        } else {
            paletteEntries = clrUsed;
        }
        int paletteOffset = biSize;
        int pixelOffset = paletteOffset + paletteEntries * 4;

        int[] palette = new int[Math.max(1, paletteEntries)];
        for (int i = 0; i < paletteEntries; i++) {
            int p = paletteOffset + i * 4;
            if (p + 3 >= data.length) {
                break;
            }
            int b = data[p] & 0xFF;
            int g = data[p + 1] & 0xFF;
            int r = data[p + 2] & 0xFF;
            palette[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        int stride = ((width * bitCount + 31) / 32) * 4;
        long needed = (long) pixelOffset + (long) stride * height;
        if (needed > data.length) {
            throw new IOException("DIB 像素数据不足: 需要 " + needed + " 实际 " + data.length);
        }

        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            int srcRow = pixelOffset + (height - 1 - y) * stride;   // 自下而上
            for (int x = 0; x < width; x++) {
                int c = readPixel(data, srcRow, x, bitCount, palette);
                // 只有 32bpp 自带 alpha；其余位深补不透明
                argb[y * width + x] = (bitCount == 32) ? c : (c | 0xFF000000);
            }
        }

        BufferedImage image = newArgb(width, height);
        image.setRGB(0, 0, width, height, argb, 0, width);

        // AND 掩码：1 表示透明
        if (hasAndMask && bitCount < 32) {
            int maskStride = ((width + 31) / 32) * 4;
            int maskOffset = pixelOffset + stride * height;
            if ((long) maskOffset + (long) maskStride * height <= data.length) {
                for (int y = 0; y < height; y++) {
                    int srcRow = maskOffset + (height - 1 - y) * maskStride;
                    for (int x = 0; x < width; x++) {
                        int byteIndex = srcRow + (x >> 3);
                        int bit = 7 - (x & 7);
                        if (((data[byteIndex] >> bit) & 1) != 0) {
                            int c = argb[y * width + x];
                            argb[y * width + x] = c & 0x00FFFFFF;
                        }
                    }
                }
                image.setRGB(0, 0, width, height, argb, 0, width);
            }
        } else if (hasAndMask && bitCount == 32) {
            // 32bpp 的 AND 掩码通常全 0；只有当 alpha 全 0 时才用它判断是否整体透明
            boolean anyAlpha = false;
            for (int c : argb) {
                if (((c >>> 24) & 0xFF) != 0) {
                    anyAlpha = true;
                    break;
                }
            }
            if (!anyAlpha) {
                boolean anyColor = false;
                for (int c : argb) {
                    if ((c & 0x00FFFFFF) != 0) {
                        anyColor = true;
                        break;
                    }
                }
                for (int i = 0; i < argb.length; i++) {
                    // alpha 全 0：旧图标常见，按不透明还原（有颜色则保留颜色，无色则透明）
                    argb[i] = anyColor ? (argb[i] | 0xFF000000) : (argb[i] & 0x00FFFFFF);
                }
                image.setRGB(0, 0, width, height, argb, 0, width);
            }
        } else if (!hasAndMask && bitCount == 32) {
            boolean anyAlpha = false;
            for (int c : argb) {
                if (((c >>> 24) & 0xFF) != 0) {
                    anyAlpha = true;
                    break;
                }
            }
            if (!anyAlpha) {
                for (int i = 0; i < argb.length; i++) {
                    argb[i] |= 0xFF000000;
                }
                image.setRGB(0, 0, width, height, argb, 0, width);
            }
        }

        return image;
    }

    private static int readPixel(byte[] data, int rowStart, int x, int bitCount, int[] palette) {
        switch (bitCount) {
            case 32: {
                int p = rowStart + x * 4;
                int b = data[p] & 0xFF;
                int g = data[p + 1] & 0xFF;
                int r = data[p + 2] & 0xFF;
                int a = data[p + 3] & 0xFF;
                return (a << 24) | (r << 16) | (g << 8) | b;
            }
            case 24: {
                int p = rowStart + x * 3;
                int b = data[p] & 0xFF;
                int g = data[p + 1] & 0xFF;
                int r = data[p + 2] & 0xFF;
                return 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            case 16: {
                int p = rowStart + x * 2;
                int v = (data[p] & 0xFF) | ((data[p + 1] & 0xFF) << 8);
                // 默认 5-5-5
                int b = v & 0x1F;
                int g = (v >> 5) & 0x1F;
                int r = (v >> 10) & 0x1F;
                return 0xFF000000 | ((r << 3 | r >> 2) << 16) | ((g << 3 | g >> 2) << 8) | (b << 3 | b >> 2);
            }
            case 8: {
                int idx = data[rowStart + x] & 0xFF;
                return idx < palette.length ? palette[idx] : 0xFFFFFFFF;
            }
            case 4: {
                int b = data[rowStart + (x >> 1)] & 0xFF;
                int idx = ((x & 1) == 0) ? (b >> 4) : (b & 0x0F);
                return idx < palette.length ? palette[idx] : 0xFFFFFFFF;
            }
            case 1: {
                int b = data[rowStart + (x >> 3)] & 0xFF;
                int idx = (b >> (7 - (x & 7))) & 1;
                return idx < palette.length ? palette[idx] : 0xFFFFFFFF;
            }
            default:
                return 0xFFFFFFFF;
        }
    }

    /** 统一转成 TYPE_INT_ARGB，后续所有处理只认这一种。 */
    public static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) {
            return src;
        }
        BufferedImage out = newArgb(src.getWidth(), src.getHeight());
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /**
     * 创建 TYPE_INT_ARGB 图像。
     *
     * <p>用 new BufferedImage(...) 而非 createCompatibleImage：后者在无图形环境
     * （无头模式 / 沙箱）下可能失败。
     */
    public static BufferedImage newArgb(int w, int h) {
        return new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
    }

    /** 解析 .ico 中所有帧的尺寸，用于校验与测试。 */
    public static List<int[]> listFrames(byte[] ico) {
        List<int[]> out = new ArrayList<int[]>();
        if (ico.length < 6) {
            return out;
        }
        int count = u16(ico, 4);
        for (int i = 0; i < count; i++) {
            int entry = 6 + i * 16;
            if (entry + 16 > ico.length) {
                break;
            }
            int w = ico[entry] & 0xFF;
            int h = ico[entry + 1] & 0xFF;
            if (w == 0) {
                w = 256;
            }
            if (h == 0) {
                h = 256;
            }
            int bytes = u32(ico, entry + 8);
            int offset = u32(ico, entry + 12);
            out.add(new int[]{w, h, offset, bytes});
        }
        return out;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }
}
