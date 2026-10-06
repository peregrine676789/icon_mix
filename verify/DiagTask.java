import iconmix.core.Renderer;
import iconmix.core.Scanner;
import iconmix.extract.FrameDecoder;
import iconmix.model.FolderTask;
import iconmix.model.Tile;
import iconmix.util.Log;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * 端到端验证：扫描 → 提取 → 网格 → 渲染，并逐格核对
 * 「cellRects 画的坐标」与「indexAt 反查的索引」是否一致（拖拽交换是否可用）。
 */
public final class DiagTask {

    public static void main(String[] args) throws Exception {
        File folder = new File(args[0]);
        Log log = new Log();
        log.addListener(new Log.Listener() {
            @Override
            public void onLog(String line) {
                System.out.println("    " + line);
            }
        });

        System.out.println("目录: " + folder.getAbsolutePath());
        List<File> files = Scanner.scan(folder, 4, log);
        List<Tile> tiles = Scanner.extract(files, 36, log);
        System.out.println("候选文件 = " + files.size() + "，提取并去重后图标 = " + tiles.size());
        if (tiles.isEmpty()) {
            System.out.println("没有图标可用");
            return;
        }

        FolderTask task = new FolderTask(folder);
        for (File f : files) {
            task.addCandidate(f);
        }
        task.setTiles(tiles);

        for (FolderTask.LayoutMode mode : FolderTask.LayoutMode.values()) {
            task.setMode(mode);
            System.out.println();
            System.out.println("=== " + mode.label() + " ===");
            System.out.println("  图标数=" + task.size() + "  列=" + task.columns() + " 行=" + task.rows()
                    + "  槽位=" + task.slotCount() + "  空占位=" + task.emptySlotCount()
                    + "  画布=" + task.canvasSize());
            int[][] rects = task.cellRects();
            boolean allMatched = true;
            int cell = task.cellSize();
            for (int s = 0; s < rects.length; s++) {
                int cx = rects[s][0] + cell / 2;
                int cy = rects[s][1] + cell / 2;
                int back = task.slotAtPixel(cx, cy);
                boolean ok = back == s;
                if (!ok) {
                    allMatched = false;
                }
                Tile tile = task.tileAtSlot(s);
                System.out.println(String.format("    槽 %d @ (%4d,%4d) 反查=%-3d %-14s %s",
                        s, rects[s][0], rects[s][1], back,
                        tile == null ? "[空占位]" : tile.name(), ok ? "" : "  <== 不一致"));
            }
            // 统计画布上能被命中的槽位数
            int hitCount = 0;
            for (int y = 0; y < task.canvasSize(); y += 4) {
                for (int x = 0; x < task.canvasSize(); x += 4) {
                    if (task.slotAtPixel(x, y) >= 0) {
                        hitCount++;
                    }
                }
            }
            System.out.println("  画布可命中采样点 = " + hitCount
                    + (allMatched ? "   映射一致 OK" : "   存在映射不一致 FAIL"));

            // 渲染出来，逐槽位核对：有图标必须绘制、空位必须透明
            BufferedImage canvas = Renderer.render(task, task.cellSize());
            int problems = 0;
            for (int s = 0; s < rects.length; s++) {
                int drawn = 0;
                int x1 = Math.min(rects[s][0] + cell, canvas.getWidth());
                int y1 = Math.min(rects[s][1] + cell, canvas.getHeight());
                for (int y = rects[s][1]; y < y1; y++) {
                    for (int x = rects[s][0]; x < x1; x++) {
                        if (((canvas.getRGB(x, y) >>> 24) & 0xFF) > 8) {
                            drawn++;
                        }
                    }
                }
                boolean shouldDraw = task.hasTile(s);
                if (shouldDraw && drawn == 0) {
                    System.out.println("    !! 槽 " + s + " 有图标却没有绘制内容");
                    problems++;
                }
                if (!shouldDraw && drawn > 0) {
                    System.out.println("    !! 槽 " + s + " 是空占位却有 " + drawn + " 个非透明像素");
                    problems++;
                }
            }
            System.out.println("  渲染 " + canvas.getWidth() + "x" + canvas.getHeight()
                    + "，槽位绘制校验" + (problems == 0 ? "通过 OK" : "失败 " + problems + " 处 FAIL"));
        }
    }

    static {
        // 避免未使用导入告警
        FrameDecoder.newArgb(1, 1);
    }
}
