import iconmix.core.Exporter;
import iconmix.core.ShellApplier;
import iconmix.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 手工验收脚本：覆盖「先清理后写入」「应用到文件夹 / 还原」「网格与槽位」三组最危险的路径。
 * 不进入 src/main/java，避免污染主源码树。用法：
 *   javac -encoding UTF-8 -cp out -d out-test verify/Harness.java
 *   java -cp "out;out-test" Harness <文件夹>
 */
public final class Harness {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        File folder = new File(args[0]);
        File dir = folder.getParentFile();
        Log log = new Log();
        log.addListener(new Log.Listener() {
            @Override
            public void onLog(String m) {
                System.out.println("   · " + m);
            }
        });

        String name = folder.getName();

        section("测试前置：清空该目录名下既有产物（保证用例可重复执行）");
        for (File f : Exporter.matchingProducts(dir, name)) {
            f.delete();
        }
        File staleIni = new File(folder, "desktop.ini");
        if (staleIni.isFile()) {
            staleIni.delete();
        }
        new File(folder, ".iconmix").delete();
        check("前置清理完成", Exporter.matchingProducts(dir, name).isEmpty(), "");

        section("清理匹配规则（AC-9.1.16：非本工具文件零改动）");
        File[] decoys = {
                new File(dir, name + "_iconbackup.png"),
                new File(dir, name + "_icon_v2.png"),
                new File(dir, name + "_icon(旧).png"),
                new File(dir, name + "_icon(10.1)副本.png"),
                new File(dir, name + "_icon.txt"),
                new File(dir, "other_icon(10.1).png"),
        };
        for (File f : decoys) {
            Exporter.writeBytes(f, new byte[]{1, 2, 3});
        }
        File[] products = {
                new File(dir, name + "_icon.png"),
                new File(dir, name + "_icon(10.1).png"),
                new File(dir, name + "_icon(9.30).png"),
                new File(dir, name + "_icon(10.5).ico"),
        };
        for (File f : products) {
            Exporter.writeBytes(f, new byte[]{4, 5, 6});
        }

        java.util.List<File> matched = Exporter.matchingProducts(dir, name);
        check("匹配到 4 个本工具旧产物", matched.size() == 4, "实际 " + matched.size());
        for (File f : matched) {
            System.out.println("   · 命中：" + f.getName());
        }
        boolean decoysSafe = true;
        for (File f : decoys) {
            if (!f.exists()) {
                decoysSafe = false;
                System.out.println("   ! 误删：" + f.getName());
            }
        }
        check("6 个干扰文件全部保留", decoysSafe, "");

        section("清理执行（AC-9.1.13~9.1.15）");
        iconmix.model.FolderTask task = new iconmix.model.FolderTask(folder);
        java.awt.image.BufferedImage img =
                iconmix.extract.FrameDecoder.newArgb(64, 64);
        Exporter.ExportResult r = Exporter.exportPng(task, img, null, dir, true, null, null);
        check("清理了 4 个旧产物", r.removed.size() == 4, "实际 " + r.removed.size());
        check("新产物已写出", r.png != null && r.png.isFile(), String.valueOf(r.png));
        System.out.println("   · 新产物：" + (r.png == null ? "null" : r.png.getName()));
        boolean onlyOne = true;
        for (File f : products) {
            if (f.exists() && !f.equals(r.png)) {
                onlyOne = false;
            }
        }
        check("该目录名下只剩 1 个产物", onlyOne, "");

        section("保留历史开关（FR-4.11.16）");
        File keep = new File(dir, name + "_icon(10.1).png");
        Exporter.writeBytes(keep, new byte[]{7});
        Exporter.exportPng(task, img, null, dir, false, null, null);
        check("--keep-history 时不清理", keep.isFile(), "");

        section("应用到文件夹（FR-4.10）");
        new File(dir, name + "_icon(10.1).png").delete();
        File icon = new File(dir, "harness.ico");
        Exporter.writeBytes(icon, iconmix.extract.IconSources.readFully(
                new File(folder, name + "_icon(10.5).ico")));
        ShellApplier.ApplyResult ar = ShellApplier.apply(folder, iconmix.extract.IconSources.readFully(icon),
                "harness_icon.ico", false, log);
        File ini = new File(folder, "desktop.ini");
        check("desktop.ini 已生成", ini.isFile(), ini.getAbsolutePath());
        byte[] raw = iconmix.extract.IconSources.readFully(ini);
        check("desktop.ini 是 UTF-16LE + BOM",
                raw.length > 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE,
                "前两字节 " + (raw[0] & 0xFF) + "," + (raw[1] & 0xFF));
        String text = ShellApplier.readIni(ini);
        check("含 IconResource", text.contains("[.ShellClassInfo]") && text.contains("IconResource="), text.trim());
        check("含本工具标记", text.contains(ShellApplier.MARKER), "");
        check("IconResource 指向 Harness 写入的 ico",
                text.contains("harness_icon.ico"), "");

        section("属性（FR-4.10.3）");
        String attrs = attrib(folder);
        check("文件夹带只读属性", attrs.contains("R"), "[" + attrs + "]");
        String iniAttrs = attrib(ini);
        check("desktop.ini 带 Hidden+System", iniAttrs.contains("H") && iniAttrs.contains("S"),
                "[" + iniAttrs + "]");

        section("还原（FR-4.15）");
        File other = new File(folder, "other-desktop-ini-test.txt");
        Exporter.writeBytes(other, new byte[]{9});
        boolean removed = ShellApplier.revert(folder, false, log);
        check("revert 报告已删除", removed, "");
        check("desktop.ini 已删除", !ini.isFile(), "");
        check("ico 已删除", !new File(folder, "harness_icon.ico").isFile(), "");
        check("文件夹只读属性已清除", !attrib(folder).contains("R"), "[" + attrib(folder) + "]");
        check("同目录其他文件未受影响", other.isFile(), "");

        section("他人 desktop.ini 保护（FR-4.15.2）");
        File foreign = new File(folder, "desktop.ini");
        java.io.OutputStream os = new java.io.FileOutputStream(foreign);
        os.write("[.ShellClassInfo]\r\nIconResource=C:\\somewhere\\other.ico,0\r\n".getBytes("UTF-16LE"));
        os.close();
        boolean removed2 = ShellApplier.revert(folder, false, log);
        check("不含标记时不删除", !removed2 && foreign.isFile(), "removed=" + removed2);
        foreign.delete();

        section("网格与槽位（T-19 画布裁切 / T-20 映射脱节 / 补位与空位交换）");
        int gridFail = 0;
        StringBuilder gridDetail = new StringBuilder();
        for (int n = 1; n <= 36; n++) {
            for (iconmix.model.FolderTask.LayoutMode mode
                    : iconmix.model.FolderTask.LayoutMode.values()) {
                iconmix.model.FolderTask t = syntheticTask(folder, n, mode);
                int canvas = t.canvasSize();
                int cell = t.cellSize();
                if (canvas < t.columns() * cell || canvas < t.rows() * cell) {
                    gridFail++;
                    gridDetail.append("n=").append(n).append(' ').append(mode.label())
                            .append(" 画布装不下; ");
                    continue;
                }
                int[][] rects = t.cellRects();
                if (rects.length != t.columns() * t.rows()) {
                    gridFail++;
                    gridDetail.append("n=").append(n).append(' ').append(mode.label())
                            .append(" 槽位数=").append(rects.length).append(" 应为 ")
                            .append(t.columns() * t.rows()).append("; ");
                }
                for (int i = 0; i < rects.length; i++) {
                    if (rects[i][0] < 0 || rects[i][1] < 0
                            || rects[i][0] + cell > canvas || rects[i][1] + cell > canvas) {
                        gridFail++;
                        gridDetail.append("n=").append(n).append(' ').append(mode.label())
                                .append(" 槽 ").append(i).append(" 越界; ");
                        break;
                    }
                }
                for (int i = 0; i < rects.length; i++) {
                    int back = t.slotAtPixel(rects[i][0] + cell / 2, rects[i][1] + cell / 2);
                    if (back != i) {
                        gridFail++;
                        gridDetail.append("n=").append(n).append(' ').append(mode.label())
                                .append(" 槽 ").append(i).append(" 反查=").append(back).append("; ");
                        break;
                    }
                }
                if (t.size() + t.emptySlotCount() != rects.length) {
                    gridFail++;
                    gridDetail.append("n=").append(n).append(' ').append(mode.label())
                            .append(" 图标+空位=").append(t.size() + t.emptySlotCount())
                            .append(" != 槽位=").append(rects.length).append("; ");
                }
                java.awt.image.BufferedImage gridCanvas = iconmix.core.Renderer.render(t, cell);
                for (int i = 0; i < rects.length; i++) {
                    int drawn = 0;
                    int x1 = Math.min(rects[i][0] + cell, gridCanvas.getWidth());
                    int y1 = Math.min(rects[i][1] + cell, gridCanvas.getHeight());
                    for (int y = rects[i][1]; y < y1 && drawn == 0; y += 3) {
                        for (int x = rects[i][0]; x < x1; x += 3) {
                            if (((gridCanvas.getRGB(x, y) >>> 24) & 0xFF) > 8) {
                                drawn++;
                                break;
                            }
                        }
                    }
                    boolean shouldDraw = t.hasTile(i);
                    if (shouldDraw && drawn == 0) {
                        gridFail++;
                        gridDetail.append("n=").append(n).append(' ').append(mode.label())
                                .append(" 槽 ").append(i).append(" 有图标却空白; ");
                        break;
                    }
                    if (!shouldDraw && drawn > 0) {
                        gridFail++;
                        gridDetail.append("n=").append(n).append(' ').append(mode.label())
                                .append(" 槽 ").append(i).append(" 空位却有绘制内容; ");
                        break;
                    }
                }
            }
        }
        check("n=1..36 × 两种排布：槽位数/越界/映射/补位/绘制 全部正确", gridFail == 0,
                gridDetail.length() > 200 ? gridDetail.substring(0, 200) + "..." : gridDetail.toString());

        section("空占位补齐与空位交换（FR-4.5.9 / 4.6.11）");
        iconmix.model.FolderTask seven = syntheticTask(folder, 7,
                iconmix.model.FolderTask.LayoutMode.ROW_FIRST);
        check("7 个图标 → 3×3 且补 2 个空占位",
                seven.columns() == 3 && seven.rows() == 3 && seven.emptySlotCount() == 2,
                seven.columns() + "x" + seven.rows() + " 槽位=" + seven.slots().size()
                        + " 空位=" + seven.emptySlotCount());
        check("补位后画布为 768×768", seven.canvasSize() == 768, String.valueOf(seven.canvasSize()));

        int lastSlot = seven.slotCount() - 1;
        String moved = seven.tileAtSlot(0).name();
        boolean swapped = seven.swap(0, lastSlot);
        check("图标可与空占位交换", swapped, "");
        check("交换后图标落到空位（槽 " + lastSlot + "）",
                seven.tileAtSlot(lastSlot) != null && seven.tileAtSlot(lastSlot).name().equals(moved),
                "槽 " + lastSlot + " = " + seven.tileAtSlot(lastSlot));
        check("交换后原槽位 0 变成空位", seven.tileAtSlot(0) == null, String.valueOf(seven.tileAtSlot(0)));
        check("交换后图标总数与空位数不变",
                seven.size() == 7 && seven.emptySlotCount() == 2,
                "图标=" + seven.size() + " 空位=" + seven.emptySlotCount());
        check("交换后 tiles 顺序与槽位顺序一致", tilesMatchSlots(seven), "");

        iconmix.model.FolderTask order = syntheticTask(folder, 5,
                iconmix.model.FolderTask.LayoutMode.ROW_FIRST);
        // 5 个图标是 3 列 × 2 行（槽位 0..5），最后一个槽位是空占位
        int emptySlot = order.slotCount() - 1;
        int filledSlot = 0;
        String filledName = order.tileAtSlot(filledSlot).name();
        check("5 个图标时末尾槽位是空占位", order.tileAtSlot(emptySlot) == null,
                String.valueOf(order.tileAtSlot(emptySlot)));
        order.swap(filledSlot, emptySlot);
        check("交换后槽位 0 承载原槽位 " + emptySlot + " 的内容（空位落到 0）",
                order.tileAtSlot(filledSlot) == null, String.valueOf(order.tileAtSlot(filledSlot)));
        check("原图标已移到末尾槽位",
                order.tileAtSlot(emptySlot) != null && order.tileAtSlot(emptySlot).name().equals(filledName),
                String.valueOf(order.tileAtSlot(emptySlot)));
        check("交换后 tiles 顺序与槽位顺序仍一致", tilesMatchSlots(order), "");
        check("恢复默认顺序后回到自动排序", restoreAndCheck(order), "");

        section("PNG 往返（T-11：PNG 是大端，ICO 是小端）");
        java.awt.image.BufferedImage probe = iconmix.extract.FrameDecoder.newArgb(16, 16);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                probe.setRGB(x, y, (x * 16) << 16 | (y * 16) << 8 | 0x80);
            }
        }
        byte[] png = iconmix.util.PngIO.encode(probe);
        iconmix.util.PngVerify.Result pv = iconmix.util.PngVerify.verify(png, "内存图");
        check("PNG 结构校验通过", pv.ok, pv.report.toString().trim());
        check("PNG 尺寸为 16x16", pv.width == 16 && pv.height == 16, pv.width + "x" + pv.height);
        try {
            java.awt.image.BufferedImage back = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(png));
            check("ImageIO 能读回自研 PNG", back != null
                            && back.getWidth() == 16 && back.getHeight() == 16,
                    String.valueOf(back));
            if (back != null) {
                check("像素内容往返一致", back.getRGB(5, 7) == probe.getRGB(5, 7),
                        Integer.toHexString(back.getRGB(5, 7)) + " vs "
                                + Integer.toHexString(probe.getRGB(5, 7)));
            }
        } catch (Exception e) {
            check("ImageIO 能读回自研 PNG", false, String.valueOf(e));
        }

        System.out.println();
        System.out.println("====== 通过 " + pass + " 项，失败 " + fail + " 项 ======");
        System.exit(fail == 0 ? 0 : 1);
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("   [OK] " + what);
        } else {
            fail++;
            System.out.println("   [FAIL] " + what + "  " + detail);
        }
    }

    /** 校验 tiles 顺序与槽位顺序一致（导出正确性的前提）。 */
    private static boolean tilesMatchSlots(iconmix.model.FolderTask t) {
        List<iconmix.model.Tile> expected = new java.util.ArrayList<iconmix.model.Tile>();
        for (int i = 0; i < t.slotCount(); i++) {
            iconmix.model.Tile tile = t.tileAtSlot(i);
            if (tile != null) {
                expected.add(tile);
            }
        }
        if (expected.size() != t.tiles().size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (expected.get(i) != t.tiles().get(i)) {
                return false;
            }
        }
        return true;
    }

    /** 交换后恢复默认顺序，校验顺序回到初始状态。 */
    private static boolean restoreAndCheck(iconmix.model.FolderTask t) {
        List<String> before = new java.util.ArrayList<String>();
        for (iconmix.model.Tile tile : t.tiles()) {
            before.add(tile.name());
        }
        t.restoreDefaultOrder();
        List<String> after = new java.util.ArrayList<String>();
        for (iconmix.model.Tile tile : t.tiles()) {
            after.add(tile.name());
        }
        java.util.Collections.sort(before);
        java.util.Collections.sort(after);
        return before.equals(after) && tilesMatchSlots(t);
    }

    /** 构造 n 个纯色图标的合成任务，用于网格与渲染的纯逻辑回归。 */
    private static iconmix.model.FolderTask syntheticTask(
            File folder, int n, iconmix.model.FolderTask.LayoutMode mode) {
        iconmix.model.FolderTask t = new iconmix.model.FolderTask(folder);
        List<iconmix.model.Tile> tiles = new java.util.ArrayList<iconmix.model.Tile>();
        for (int i = 0; i < n; i++) {
            java.awt.image.BufferedImage img = iconmix.extract.FrameDecoder.newArgb(64, 64);
            java.awt.Graphics2D g = img.createGraphics();
            g.setColor(new java.awt.Color(0x20 + (i * 7) % 200, 0x40 + (i * 11) % 180, 0x60 + (i * 13) % 160));
            g.fillOval(4, 4, 56, 56);
            g.dispose();
            tiles.add(new iconmix.model.Tile(
                    new iconmix.model.IconEntry("synthetic-" + i, "synthetic-" + i, "h" + i), img));
        }
        t.setTiles(tiles);
        t.setMode(mode);
        return t;
    }

    private static String attrib(File f) throws IOException {
        Process p = new ProcessBuilder("cmd", "/c", "attrib", f.getAbsolutePath())
                .redirectErrorStream(true).start();
        java.io.InputStream in = p.getInputStream();
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[512];
        int n;
        while ((n = in.read(tmp)) > 0) {
            buf.write(tmp, 0, n);
        }
        try {
            p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String s = new String(buf.toByteArray(), java.nio.charset.Charset.defaultCharset());
        int end = s.indexOf('\n');
        if (end < 0) {
            end = s.length();
        }
        s = s.substring(0, end);
        StringBuilder flags = new StringBuilder();
        for (int i = 0; i < s.length() && i < 21; i++) {
            char c = s.charAt(i);
            if (c > ' ') {
                flags.append(c);
            }
        }
        return flags.toString();
    }
}
