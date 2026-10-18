import iconmix.core.IcoWriter;
import iconmix.extract.FrameDecoder;
import iconmix.util.PngIO;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 生成应用图标（打包用）。
 *
 * <p>图形语义与产品一致：深蓝圆角底 + 3×3 宫格，其中右下 2 格只画描边，
 * 表示「空占位」——正是 IconMix 的补位特性。
 *
 * <p>产物：{@code assets/icon.ico}（多尺寸，给 jpackage 用）与 {@code assets/icon.png}。
 * 用法：{@code java -cp out tools.MakeAppIcon}（或直接 javac 后运行）。
 */
public final class MakeAppIcon {

    /** 设计基准尺寸：ICO 最大帧就是 256。 */
    private static final int SIZE = 256;
    /** 宫格：3×3。 */
    private static final int GRID = 3;
    /** 右下角的空占位数量。 */
    private static final int EMPTY_SLOTS = 2;

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "assets");
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IllegalStateException("无法创建目录: " + outDir.getAbsolutePath());
        }

        BufferedImage icon = draw(SIZE);

        // 多尺寸 ICO（同一张图缩放到各尺寸，图标是矢量式几何图形，缩放质量足够）
        byte[] ico = IcoWriter.write(icon, IcoWriter.DEFAULT_SIZES, null);
        File icoFile = new File(outDir, "icon.ico");
        iconmix.core.Exporter.writeBytes(icoFile, ico);

        File pngFile = new File(outDir, "icon.png");
        iconmix.core.Exporter.writeBytes(pngFile, PngIO.encode(icon));

        System.out.println("已生成:");
        System.out.println("  " + icoFile.getAbsolutePath() + "  (" + ico.length + " 字节)");
        System.out.println("  " + pngFile.getAbsolutePath() + "  (" + pngFile.length() + " 字节)");

        // 自检：用项目自己的校验器验证
        iconmix.util.PngVerify.Result pv = iconmix.util.PngVerify.verify(
                iconmix.extract.IconSources.readFully(pngFile), "icon.png");
        if (!pv.ok) {
            System.out.println("PNG 自检失败:\n" + pv.report);
            System.exit(1);
        }
        int frames = FrameDecoder.listFrames(iconmix.extract.IconSources.readFully(icoFile)).size();
        System.out.println("自检通过：PNG " + pv.width + "x" + pv.height + "，ICO " + frames + " 帧");
    }

    /** 绘制图标。 */
    public static BufferedImage draw(int size) {
        BufferedImage img = FrameDecoder.newArgb(size, size);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // 背景：圆角矩形 + 斜向渐变
        double pad = size * 0.06;
        RoundRectangle2D bg = new RoundRectangle2D.Double(
                pad, pad, size - pad * 2, size - pad * 2, size * 0.22, size * 0.22);
        g.setPaint(new GradientPaint(
                (float) pad, (float) pad, new Color(0x2B, 0x7C, 0xE8),
                (float) (size - pad), (float) (size - pad), new Color(0x0D, 0x3F, 0x8C)));
        g.fill(bg);

        // 内层高光（让图标更有体积感，小尺寸下也更清晰）
        g.setStroke(new BasicStroke(Math.max(1f, size * 0.012f)));
        g.setColor(new Color(0xFF, 0xFF, 0xFF, 0x55));
        g.draw(new RoundRectangle2D.Double(
                pad + size * 0.02, pad + size * 0.02,
                size - pad * 2 - size * 0.04, size - pad * 2 - size * 0.04,
                size * 0.2, size * 0.2));

        // 宫格：3×3，最后 EMPTY_SLOTS 格只描边（表示空占位）
        int total = GRID * GRID;
        double gridPad = size * 0.17;
        double gap = size * 0.035;
        double cell = (size - gridPad * 2 - gap * (GRID - 1)) / GRID;
        double arc = cell * 0.3;
        int filled = total - EMPTY_SLOTS;

        for (int i = 0; i < total; i++) {
            int row = i / GRID;
            int col = i % GRID;
            double x = gridPad + col * (cell + gap);
            double y = gridPad + row * (cell + gap);
            RoundRectangle2D r = new RoundRectangle2D.Double(x, y, cell, cell, arc, arc);
            if (i < filled) {
                g.setColor(Color.WHITE);
                g.fill(r);
            } else {
                // 空占位：虚线描边
                g.setColor(new Color(0xFF, 0xFF, 0xFF, 0x90));
                g.setStroke(new BasicStroke(
                        Math.max(1f, (float) (size * 0.014)),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                        10f, new float[]{(float) (cell * 0.22), (float) (cell * 0.16)}, 0f));
                g.draw(r);
            }
        }
        g.dispose();
        return img;
    }

    private MakeAppIcon() {
    }
}
