package iconmix.cli;

import iconmix.core.Exporter;
import iconmix.core.IcoWriter;
import iconmix.core.Renderer;
import iconmix.core.Scanner;
import iconmix.core.ShellApplier;
import iconmix.extract.FrameDecoder;
import iconmix.model.FolderTask;
import iconmix.model.Tile;
import iconmix.util.Log;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令行执行器（FR-4.13）。
 *
 * <p>批量模式下单个目录失败不阻断后续目录（FR-4.13.5），退出码按最坏情况上报。
 */
public final class CliRunner {

    private CliRunner() {
    }

    /** @return 退出码：0 成功 / 1 部分失败 / 2 失败 */
    public static int run(Args args, Log log) {
        if (args.verify) {
            return IcoVerifier.run(args, log) ? 0 : 2;
        }
        if (args.folders.isEmpty()) {
            System.out.print(Args.usage());
            return 2;
        }
        int ok = 0;
        int failed = 0;
        for (File folder : args.folders) {
            try {
                processOne(folder, args, log);
                ok++;
            } catch (Exception e) {
                failed++;
                log.error(folder + " 处理失败：" + e.getMessage());
                if (!args.quiet) {
                    System.out.println("[失败] " + folder + "：" + e.getMessage());
                }
            }
        }
        log.info("批量处理结束：成功 " + ok + " 个，失败 " + failed + " 个");
        if (failed == 0) {
            return 0;
        }
        return ok == 0 ? 2 : 1;
    }

    private static void processOne(File folder, Args args, Log log) throws IOException {
        if (!folder.exists()) {
            throw new IOException("目录不存在");
        }
        if (args.revert) {
            ShellApplier.revert(folder, true, log);
            return;
        }
        if (!folder.isDirectory()) {
            throw new IOException("不是文件夹");
        }

        List<File> files = Scanner.scan(folder, args.depth, log);
        if (files.isEmpty()) {
            throw new IOException("目录内没有可提取图标的程序或快捷方式");
        }
        List<Tile> tiles = Scanner.extract(files, 36, log);
        if (tiles.isEmpty()) {
            throw new IOException("未能从任何文件提取到图标");
        }

        FolderTask task = new FolderTask(folder);
        for (File f : files) {
            task.addCandidate(f);      // 用于定位 app 可执行文件所在目录（FR-4.11.2）
        }
        task.setTiles(tiles);
        if ("row".equalsIgnoreCase(args.layout)) {
            task.setMode(FolderTask.LayoutMode.ROW_FIRST);
        } else if ("column".equalsIgnoreCase(args.layout)) {
            task.setMode(FolderTask.LayoutMode.COLUMN_FIRST);
        }
        log.info(task.gridSummary());

        final FolderTask finalTask = task;
        int[] sizes = args.sizes != null ? args.sizes : IcoWriter.DEFAULT_SIZES;
        byte[] ico = IcoWriter.write(null, sizes, new IcoWriter.SizeRenderer() {
            @Override
            public BufferedImage render(int size) {
                return Renderer.renderAt(finalTask, finalTask.cellSize(), size);
            }
        });
        BufferedImage canvas = Renderer.render(task, task.cellSize());

        if (!args.exportOnly && args.applyIcon) {
            String icoName = Exporter.sanitize(task.folderName()) + "_icon.ico";
            ShellApplier.ApplyResult r = ShellApplier.apply(folder, ico, icoName, true, log);
            log.info(r.describe());
            // 应用后按需求仍然导出一份结果图到 app 目录，便于用户留档
            exportImage(task, canvas, ico, args, log);
            return;
        }

        exportImage(task, canvas, args.exportIco ? ico : null, args, log);
    }

    private static void exportImage(FolderTask task, BufferedImage canvas, byte[] ico,
                                    Args args, Log log) throws IOException {
        File dir = args.outDir != null ? args.outDir : Scanner.primaryExeDir(task.candidates());
        if (dir == null) {
            dir = task.folder().getParentFile() != null ? task.folder().getParentFile() : task.folder();
            log.warn("未找到 exe 所在目录，导出到 " + dir.getAbsolutePath());
        }
        if (args.name != null) {
            File explicit = new File(dir, args.name + ".png");
            File written = Exporter.exportPngTo(task, canvas, explicit);
            log.info("已导出图片：" + written.getAbsolutePath());
            if (ico != null) {
                Exporter.writeBytes(new File(dir, args.name + ".ico"), ico);
            }
            return;
        }
        Exporter.ExportResult result = Exporter.exportPng(task, canvas, ico, dir,
                !args.keepHistory, null, null);
        log.info(result.describe());
        if (!args.quiet) {
            System.out.println(result.describe());
        }
        for (String w : result.warnings) {
            log.warn(w);
        }
        List<int[]> frames = IcoWriter.inspect(ico);
        StringBuilder sb = new StringBuilder();
        for (int[] f : frames) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(f[0]).append("x").append(f[1]);
        }
        log.info("ICO 帧：" + sb);
    }

    /** 供 Main 使用的空日志。 */
    public static Log silentLog() {
        return new Log();
    }

    static List<String> frameSummary(byte[] ico) {
        List<String> out = new ArrayList<String>();
        for (int[] f : FrameDecoder.listFrames(ico)) {
            out.add(f[0] + "x" + f[1] + "@" + f[2] + "+" + f[3]);
        }
        return out;
    }
}
