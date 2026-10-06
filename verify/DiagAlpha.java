import iconmix.core.Scanner;
import iconmix.core.Renderer;
import iconmix.model.FolderTask;
import iconmix.model.Tile;
import iconmix.util.Log;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/** 验证空占位在导出图片中完全透明，且图标槽位有内容。 */
public final class DiagAlpha {

    public static void main(String[] args) throws Exception {
        File folder = new File(args[0]);
        Log log = new Log();
        List<File> files = Scanner.scan(folder, 4, log);
        List<Tile> tiles = Scanner.extract(files, 36, log);

        FolderTask task = new FolderTask(folder);
        task.setTiles(tiles);

        for (FolderTask.LayoutMode mode : FolderTask.LayoutMode.values()) {
            task.setMode(mode);
            BufferedImage canvas = Renderer.render(task, task.cellSize());
            int cell = task.cellSize();
            int[][] rects = task.cellRects();
            System.out.println("=== " + mode.label() + "：图标=" + task.size()
                    + " 槽位=" + task.slotCount() + " 空位=" + task.emptySlotCount()
                    + " 画布=" + canvas.getWidth() + "x" + canvas.getHeight() + " ===");
            int bad = 0;
            for (int s = 0; s < rects.length; s++) {
                long opaque = 0;
                long total = 0;
                int x1 = Math.min(rects[s][0] + cell, canvas.getWidth());
                int y1 = Math.min(rects[s][1] + cell, canvas.getHeight());
                for (int y = rects[s][1]; y < y1; y++) {
                    for (int x = rects[s][0]; x < x1; x++) {
                        total++;
                        if (((canvas.getRGB(x, y) >>> 24) & 0xFF) > 0) {
                            opaque++;
                        }
                    }
                }
                boolean shouldDraw = task.hasTile(s);
                String verdict;
                if (shouldDraw) {
                    verdict = opaque > 0 ? "有内容 OK" : "有图标却全透明 FAIL";
                    if (opaque == 0) {
                        bad++;
                    }
                } else {
                    verdict = opaque == 0 ? "完全透明 OK" : ("空位却有 " + opaque + " 个不透明像素 FAIL");
                    if (opaque != 0) {
                        bad++;
                    }
                }
                System.out.println(String.format("  槽 %d %-10s 不透明像素 %6d / %6d  %s",
                        s, shouldDraw ? "[图标]" : "[空位]", opaque, total, verdict));
            }
            System.out.println(bad == 0 ? "  结论：空位透明性与图标绘制均正确" : "  结论：有 " + bad + " 处异常");
        }
    }
}
