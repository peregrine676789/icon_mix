package iconmix.cli;

import iconmix.core.IcoWriter;
import iconmix.extract.FrameDecoder;
import iconmix.util.Log;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * ICO 结构校验器（FR-4.8.5 / 9.2）。
 *
 * <p>逐帧报告尺寸、偏移、越界，并用最严格的加载路径做解码测试。
 * 之所以要专门做校验：历史上 PNG 压缩帧能骗过资源管理器，却让
 * {@code System.Drawing.Icon} 抛「Requested range extends past the end of the array」（T-3）。
 */
public final class IcoVerifier {

    private IcoVerifier() {
    }

    /** @return 是否通过 */
    public static boolean run(Args args, Log log) {
        File target = args.verifyIco;
        if (target == null && !args.folders.isEmpty()) {
            target = args.folders.get(0);
        }
        if (target == null || !target.isFile()) {
            log.error("--verify 需要指定一个 .ico 文件");
            return false;
        }
        try {
            byte[] data = iconmix.extract.IconSources.readFully(target);
            String lower = target.getName().toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".png")) {
                iconmix.util.PngVerify.Result r = iconmix.util.PngVerify.verify(data, target.getName());
                for (String line : r.report.toString().split("\n")) {
                    log.info(line);
                }
                return r.ok;
            }
            boolean ok = verify(data, target.getName(), log);
            return ok;
        } catch (IOException e) {
            log.error("读取失败：" + e.getMessage());
            return false;
        }
    }

    /** 校验内存中的 ICO 数据。 */
    public static boolean verify(byte[] data, String label, Log log) {
        boolean ok = true;
        log.info("=== 校验 " + label + "（" + data.length + " 字节）===");

        if (data.length < 6) {
            log.error("文件过小，连 ICONDIR 都不完整");
            return false;
        }
        int reserved = u16(data, 0);
        int type = u16(data, 2);
        int count = u16(data, 4);
        if (reserved != 0) {
            log.error("Reserved 应为 0，实际 " + reserved);
            ok = false;
        }
        if (type != 1) {
            log.error("Type 应为 1（图标），实际 " + type + "（若为 256 说明大小端写反了）");
            ok = false;
        }
        if (count <= 0 || count > 64) {
            log.error("帧数异常：" + count);
            return false;
        }
        log.info("Type=1 帧数=" + count);

        int expected = 6 + count * 16;
        for (int i = 0; i < count; i++) {
            int base = 6 + i * 16;
            int w = data[base] & 0xFF;
            int h = data[base + 1] & 0xFF;
            int dw = w == 0 ? 256 : w;
            int dh = h == 0 ? 256 : h;
            int bytes = u32(data, base + 8);
            int offset = u32(data, base + 12);
            StringBuilder line = new StringBuilder();
            line.append("帧 ").append(i).append(": ").append(dw).append("x").append(dh)
                    .append(" offset=").append(offset).append(" bytes=").append(bytes);
            boolean frameOk = true;
            if (bytes <= 0) {
                line.append(" [字节数非法]");
                frameOk = false;
            }
            if (offset < expected) {
                line.append(" [偏移小于头部末尾 ").append(expected).append("]");
                frameOk = false;
            }
            if ((long) offset + bytes > data.length) {
                line.append(" [越界：末尾 ").append((long) offset + bytes).append(" > ").append(data.length).append("]");
                frameOk = false;
            }
            if (w != h) {
                line.append(" [非正方形]");
            }
            if (frameOk) {
                int biSize = u32(data, offset);
                int biWidth = u32(data, offset + 4);
                int biHeight = u32(data, offset + 8);
                int biBitCount = u16(data, offset + 14);
                int compression = u32(data, offset + 16);
                line.append(" DIB[biSize=").append(biSize)
                        .append(" ").append(biWidth).append("x").append(biHeight / 2)
                        .append(" bpp=").append(biBitCount)
                        .append(" compression=").append(compression).append("]");
                if (biSize != 40) {
                    line.append(" [预期 40 字节 BITMAPINFOHEADER]");
                    frameOk = false;
                }
                if (biWidth != dw || biHeight != dh * 2) {
                    line.append(" [DIB 尺寸与目录项不一致]");
                    frameOk = false;
                }
                if (biBitCount != 32 || compression != 0) {
                    line.append(" [非 32bpp BI_RGB]");
                    frameOk = false;
                }
                // 解码测试
                try {
                    BufferedImage img = FrameDecoder.decode(
                            java.util.Arrays.copyOfRange(data, offset, offset + bytes), true);
                    line.append(" 解码OK ").append(img.getWidth()).append("x").append(img.getHeight());
                } catch (Exception e) {
                    line.append(" [解码失败: ").append(e.getMessage()).append("]");
                    frameOk = false;
                }
            }
            if (frameOk) {
                log.info(line.toString());
            } else {
                log.error(line.toString());
                ok = false;
            }
        }

        // 整体解码（取最大帧）
        try {
            BufferedImage biggest = FrameDecoder.decodeIco(data);
            log.info("最大帧解码成功：" + biggest.getWidth() + "x" + biggest.getHeight());
        } catch (Exception e) {
            log.error("整体解码失败：" + e.getMessage());
            ok = false;
        }

        List<int[]> frames = IcoWriter.inspect(data);
        log.info(ok ? "校验通过：共 " + frames.size() + " 帧" : "校验未通过");
        return ok;
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
