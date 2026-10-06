package iconmix.util;

import java.util.zip.CRC32;

/**
 * PNG 结构校验器（配合 {@link PngIO}）。
 *
 * <p>存在的理由：PNG 的块长度与 CRC 是<b>大端</b>，而项目的 ICO/DIB 全是小端。
 * 用错字节序时文件看起来「有正确的 PNG 签名」，但解码器只会给出
 * 「I/O error reading PNG header!」这种毫无指向性的报错。这个校验器逐块报出
 * 真实原因，避免再花时间去猜。
 */
public final class PngVerify {

    private static final byte[] SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
    };

    private PngVerify() {
    }

    public static final class Result {
        public boolean ok;
        public int width;
        public int height;
        public int bitDepth;
        public int colorType;
        public int chunkCount;
        public final StringBuilder report = new StringBuilder();
    }

    public static Result verify(byte[] png, String label) {
        Result r = new Result();
        r.ok = true;
        r.report.append("=== 校验 PNG ").append(label).append("（").append(png.length).append(" 字节）===\n");

        if (png.length < 8 + 12) {
            r.ok = false;
            r.report.append("文件过短，连签名+IHDR 都不完整\n");
            return r;
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (png[i] != SIGNATURE[i]) {
                r.ok = false;
                r.report.append("PNG 签名不匹配，第 ").append(i).append(" 字节为 0x")
                        .append(Integer.toHexString(png[i] & 0xFF)).append('\n');
                return r;
            }
        }

        int off = 8;
        boolean sawIhdr = false;
        boolean sawIdat = false;
        boolean sawIend = false;

        while (off + 12 <= png.length) {
            long len = readBeUInt(png, off);
            String type = new String(png, off + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (len < 0 || off + 12 + len > png.length) {
                r.ok = false;
                r.report.append("块 ").append(type).append(" 声明长度 ").append(len)
                        .append("，但从偏移 ").append(off).append(" 起只剩 ")
                        .append(png.length - off).append(" 字节 —— 大端长度写错时就是这个现象\n");
                if (len == ((long) Integer.reverseBytes((int) len) & 0xFFFFFFFFL)) {
                    r.report.append("  （长度值疑似字节序写反）\n");
                }
                return r;
            }
            CRC32 crc = new CRC32();
            crc.update(png, off + 4, (int) (4 + len));
            long stored = readBeUInt(png, (int) (off + 8 + len));
            if (crc.getValue() != stored) {
                r.ok = false;
                r.report.append("块 ").append(type).append(" 的 CRC 不匹配：文件内 ")
                        .append(Long.toHexString(stored)).append("，实算 ")
                        .append(Long.toHexString(crc.getValue())).append('\n');
            }
            r.chunkCount++;

            if ("IHDR".equals(type)) {
                sawIhdr = true;
                r.width = readBeInt(png, off + 8);
                r.height = readBeInt(png, off + 12);
                r.bitDepth = png[off + 16] & 0xFF;
                r.colorType = png[off + 17] & 0xFF;
                r.report.append("IHDR: ").append(r.width).append('x').append(r.height)
                        .append(" bitDepth=").append(r.bitDepth)
                        .append(" colorType=").append(r.colorType)
                        .append(r.colorType == 6 ? "（RGBA）" : "").append('\n');
            } else if ("IDAT".equals(type)) {
                sawIdat = true;
            } else if ("IEND".equals(type)) {
                sawIend = true;
            }
            off += (int) (12 + len);
            if (sawIend) {
                break;
            }
        }

        if (!sawIhdr) {
            r.ok = false;
            r.report.append("缺少 IHDR 块\n");
        }
        if (!sawIdat) {
            r.ok = false;
            r.report.append("缺少 IDAT 块\n");
        }
        if (!sawIend) {
            r.ok = false;
            r.report.append("缺少 IEND 块（或结构提前断裂）\n");
        }
        if (off != png.length) {
            r.ok = false;
            r.report.append("块遍历结束于偏移 ").append(off)
                    .append("，文件长度为 ").append(png.length).append(" —— 有多余或缺失字节\n");
        }
        r.report.append(r.ok ? "PNG 校验通过：共 " + r.chunkCount + " 个块\n"
                : "PNG 校验未通过\n");
        return r;
    }

    private static long readBeUInt(byte[] b, int o) {
        return ((long) (b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
                | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static int readBeInt(byte[] b, int o) {
        return (int) readBeUInt(b, o);
    }
}
