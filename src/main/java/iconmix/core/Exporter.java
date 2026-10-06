package iconmix.core;

import iconmix.model.FolderTask;
import iconmix.util.PngIO;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 结果图片导出（FR-4.11，分支 B）。
 *
 * <p>命名与保留策略（用户最终确认的规则）：
 * <ol>
 *   <li>目标名 = {@code {拖入目录名}_icon({导出当日日期}).png}，括号用<b>英文半角</b>，日期随系统区域短日期（中文区域即 M.d）</li>
 *   <li>写出前<b>先清理</b>导出目录下所有匹配的旧产物（不区分日期），保证同一目录名只留最新一张</li>
 *   <li>匹配规则从严：只有恰好 {@code 目录名_icon} 或 {@code 目录名_icon(合法日期)} 的 .png/.ico 才会被删除</li>
 * </ol>
 */
public final class Exporter {

    private Exporter() {
    }

    public static final class ExportResult {
        public final List<File> removed = new ArrayList<File>();
        public final List<String> warnings = new ArrayList<String>();
        public File png;
        public File ico;

        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (png != null) {
                sb.append("已导出图片：").append(png.getAbsolutePath());
            }
            if (ico != null) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append("已导出图标：").append(ico.getAbsolutePath());
            }
            if (!removed.isEmpty()) {
                sb.append("；清理旧产物 ").append(removed.size()).append(" 个");
            }
            for (String w : warnings) {
                sb.append("；").append(w);
            }
            return sb.toString();
        }
    }

    /** 当日日期字符串，形如 "10.1"。 */
    public static String today() {
        return new SimpleDateFormat("M.d", Locale.getDefault()).format(new Date());
    }

    /** 去掉文件名里 Windows 非法字符。 */
    public static String sanitize(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (c < 0x20 || "<>:\"/\\|?*".indexOf(c) >= 0) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static List<File> matchingProducts(File dir, String folderName) {
        List<File> out = new ArrayList<File>();
        if (dir == null || !dir.isDirectory()) {
            return out;
        }
        String base = sanitize(folderName) + "_icon";
        File[] children = dir.listFiles();
        if (children == null) {
            return out;
        }
        for (File f : children) {
            if (!f.isFile()) {
                continue;
            }
            if (matchesProductName(f.getName(), base)) {
                out.add(f);
            }
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out;
    }

    /**
     * 严格匹配：{@code base.png|ico} 或 {@code base(合法日期).png|ico}。
     *
     * <p>因此 {@code forGame_iconbackup.png}、{@code forGame_icon_v2.png}、
     * {@code forGame_icon(旧).png}、{@code forGame_icon(10.1)副本.png} 都不会被误删（FR-4.11.7）。
     */
    static boolean matchesProductName(String fileName, String base) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        String ext;
        if (lower.endsWith(".png")) {
            ext = ".png";
        } else if (lower.endsWith(".ico")) {
            ext = ".ico";
        } else {
            return false;
        }
        String stem = fileName.substring(0, fileName.length() - ext.length());

        // 原名恰好等于 base：注意大小写比较要一致，base 保留原大小写
        if (stem.equals(base)) {
            return true;
        }
        // 必须精确等于 base + "("
        if (!stem.startsWith(base + "(") || !stem.endsWith(")")) {
            return false;
        }
        String inner = stem.substring(base.length() + 1, stem.length() - 1);
        return isDateLike(inner);
    }

    /** M.d / MM.dd / M.dd / MM.d 形式。 */
    public static boolean isDateLike(String s) {
        int dot = s.indexOf('.');
        if (dot <= 0 || dot == s.length() - 1 || s.indexOf('.', dot + 1) >= 0) {
            return false;
        }
        String month = s.substring(0, dot);
        String day = s.substring(dot + 1);
        if (month.length() < 1 || month.length() > 2 || day.length() < 1 || day.length() > 2) {
            return false;
        }
        for (int i = 0; i < month.length(); i++) {
            if (!Character.isDigit(month.charAt(i))) {
                return false;
            }
        }
        for (int i = 0; i < day.length(); i++) {
            if (!Character.isDigit(day.charAt(i))) {
                return false;
            }
        }
        int m = Integer.parseInt(month);
        int d = Integer.parseInt(day);
        return m >= 1 && m <= 12 && d >= 1 && d <= 31;
    }

    /**
     * 导出 PNG（并可选 ICO）。
     *
     * @param outDir    导出目录（默认 app 可执行文件所在目录）
     * @param cleanOld  是否清理旧产物（FR-4.11.16 的「保留历史」开关）
     */
    public static ExportResult exportPng(FolderTask task, BufferedImage image, byte[] icoData,
                                         File outDir, boolean cleanOld,
                                         List<File> preRemoved, List<String> warnings) throws IOException {
        ExportResult result = new ExportResult();
        if (outDir == null) {
            throw new IOException("导出目录为空");
        }
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IOException("无法创建导出目录：" + outDir.getAbsolutePath());
        }

        if (cleanOld) {
            List<File> old = matchingProducts(outDir, task.folderName());
            for (File f : old) {
                if (f.delete()) {
                    result.removed.add(f);
                } else {
                    result.warnings.add("旧产物被占用，未能删除：" + f.getName());
                }
            }
        }
        if (preRemoved != null) {
            result.removed.addAll(preRemoved);
        }
        if (warnings != null) {
            result.warnings.addAll(warnings);
        }

        String name = sanitize(task.folderName()) + "_icon(" + today() + ")";
        File png = new File(outDir, name + ".png");
        writeBytes(png, PngIO.encode(image));
        result.png = png;

        if (icoData != null) {
            File ico = new File(outDir, name + ".ico");
            writeBytes(ico, icoData);
            result.ico = ico;
        }
        return result;
    }

    /** 用户通过「另存为」指定路径时使用：不做清理，已存在则覆盖（FR-4.11.15）。 */
    public static File exportPngTo(FolderTask task, BufferedImage image, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + parent.getAbsolutePath());
        }
        writeBytes(target, PngIO.encode(image));
        return target;
    }

    /** 原子写出：先写 .tmp 再改名，避免异常退出留下半截文件（NFR-5.4）。 */
    public static void writeBytes(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
            out.flush();
            ((FileOutputStream) out).getFD().sync();
        } finally {
            out.close();
        }
        if (target.exists() && !target.delete()) {
            tmp.delete();
            throw new IOException("目标文件被占用，无法覆盖：" + target.getAbsolutePath());
        }
        if (!tmp.renameTo(target)) {
            // 某些文件系统上 rename 会失败，退化为直接复制
            byte[] copy = readAll(tmp);
            tmp.delete();
            OutputStream direct = new FileOutputStream(target);
            try {
                direct.write(copy);
            } finally {
                direct.close();
            }
        }
    }

    private static byte[] readAll(File f) throws IOException {
        long len = f.length();
        byte[] buf = new byte[(int) len];
        java.io.InputStream in = new java.io.FileInputStream(f);
        try {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        } finally {
            in.close();
        }
        return buf;
    }

    /** 供界面展示的清理预告。 */
    public static List<String> previewCleanup(File dir, String folderName, boolean cleanOld) {
        if (!cleanOld) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (File f : matchingProducts(dir, folderName)) {
            out.add(f.getAbsolutePath());
        }
        return out;
    }
}
