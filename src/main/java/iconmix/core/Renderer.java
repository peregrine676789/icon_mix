package iconmix.core;

import iconmix.extract.FrameDecoder;
import iconmix.model.FolderTask;
import iconmix.model.Tile;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 宫格图合成（FR-4.5）。
 *
 * <p>视觉处理采用「淡描边」方案（FR-4.5.6 中 Java 版一路）：先做边缘 alpha 外扩描边，
 * 再把图标等比缩放居中绘制到单元格内，保证缩小尺寸下轮廓仍然清晰。
 */
public final class Renderer {

    /** 单元格内图标占用的比例，留出视觉呼吸空间。 */
    private static final double ICON_FILL = 0.82;

    private Renderer() {
    }

    /**
     * 渲染完整合成图（用于预览与导出）。
     *
     * <p>画布边长一律取 {@link FolderTask#canvasSize()}（= max(列,行) × 单元格边长），
     * <b>不要在这里另算一遍</b>：曾经这里写成「列数 × 单元格」，列优先（行数 &gt; 列数）时
     * 最后一行会被画到画布外而整行消失。
     *
     * <p>空占位槽位<b>不绘制任何内容</b>（保持全透明），因此导出的图片里空位就是透明的。
     */
    public static BufferedImage render(FolderTask task, int cellSize) {
        int columns = task.columns();
        int rows = task.rows();
        if (columns <= 0 || rows <= 0) {
            return FrameDecoder.newArgb(cellSize, cellSize);
        }
        int canvas = task.canvasSize();
        BufferedImage out = FrameDecoder.newArgb(canvas, canvas);
        Graphics2D g = out.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        int[][] rects = task.cellRects();
        for (int slot = 0; slot < rects.length; slot++) {
            Tile tile = task.tileAtSlot(slot);
            if (tile == null) {
                continue;   // 空占位：保持透明
            }
            drawTile(g, tile.image(), rects[slot][0], rects[slot][1], cellSize);
        }
        g.dispose();
        return out;
    }

    /** 把单个图标画进一个单元格。 */
    private static void drawTile(Graphics2D g, BufferedImage icon, int cellX, int cellY, int cellSize) {
        int target = (int) Math.round(cellSize * ICON_FILL);
        BufferedImage scaled = scaleIcon(icon, target);
        int x = cellX + (cellSize - scaled.getWidth()) / 2;
        int y = cellY + (cellSize - scaled.getHeight()) / 2;
        g.drawImage(scaled, x, y, null);
    }

    /**
     * 等比缩放到目标框内。
     *
     * <p>缩小与放大都走 {@link #smoothScale}，但小尺寸下先做一次 alpha 外缘描边，
     * 让 16×16 这类极小尺寸的图标不至于糊成一团（FR-4.8.4）。
     */
    public static BufferedImage scaleIcon(BufferedImage src, int box) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) {
            return FrameDecoder.newArgb(box, box);
        }
        double ratio = Math.min((double) box / w, (double) box / h);
        int tw = Math.max(1, (int) Math.round(w * ratio));
        int th = Math.max(1, (int) Math.round(h * ratio));
        BufferedImage scaled = smoothScale(src, tw, th);
        BufferedImage stroked = outline(scaled);
        return stroked;
    }

    /**
     * 高质量缩放：先按目标尺寸做「阶梯式」逐级折半（scale-down in steps），
     * 再一次性放大到目标，避免直接大跨度缩放造成的细节丢失与锯齿。
     */
    public static BufferedImage smoothScale(BufferedImage src, int tw, int th) {
        if (tw == src.getWidth() && th == src.getHeight()) {
            return src;
        }
        return smoothScale(src, tw, th, true);
    }

    public static BufferedImage smoothScale(BufferedImage src, int tw, int th, boolean quality) {
        BufferedImage current = src;
        while (current.getWidth() / 2 >= tw && current.getHeight() / 2 >= th && current.getWidth() > 16) {
            int nw = Math.max(tw, current.getWidth() / 2);
            int nh = Math.max(th, current.getHeight() / 2);
            current = drawScaled(current, nw, nh, quality);
            if (nw == tw && nh == th) {
                return current;
            }
        }
        return drawScaled(current, tw, th, quality);
    }

    private static BufferedImage drawScaled(BufferedImage src, int tw, int th, boolean quality) {
        BufferedImage out = FrameDecoder.newArgb(tw, th);
        Graphics2D g = out.createGraphics();
        if (quality) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        } else {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        }
        g.drawImage(src, 0, 0, tw, th, null);
        g.dispose();
        return out;
    }

    /**
     * 透明图标的淡描边：把 alpha 通道外扩一格，用半透明深色填充，
     * 再叠回原图，使浅色图标在浅色背景上也有轮廓。
     */
    public static BufferedImage outline(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = FrameDecoder.newArgb(w, h);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 用 8 个方向的偏移绘制半透明剪影
        int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dy = {-1, -1, -1, 0, 0, 1, 1, 1};
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.28f));
        BufferedImage silhouette = tint(src, 0xFF203040);
        for (int i = 0; i < dx.length; i++) {
            g.drawImage(silhouette, dx[i], dy[i], null);
        }
        g.setComposite(AlphaComposite.SrcOver);
        g.setStroke(new BasicStroke(0f));
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** 把图像所有不透明像素替换成指定颜色，保留 alpha（用于剪影描边）。 */
    public static BufferedImage tint(BufferedImage src, int rgb) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = new int[w * h];
        src.getRGB(0, 0, w, h, px, 0, w);
        int base = rgb & 0x00FFFFFF;
        for (int i = 0; i < px.length; i++) {
            px[i] = (px[i] & 0xFF000000) | base;
        }
        BufferedImage out = FrameDecoder.newArgb(w, h);
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }

    /**
     * 指定尺寸的渲染：大尺寸直接用画布，小尺寸从画布逐级缩放
     * （每个尺寸独立渲染，FR-4.8.4）。
     */
    public static BufferedImage renderAt(FolderTask task, int cellSize, int targetSize) {
        BufferedImage full = render(task, cellSize);
        if (full.getWidth() == targetSize && full.getHeight() == targetSize) {
            return full;
        }
        return smoothScale(full, targetSize, targetSize);
    }
}
