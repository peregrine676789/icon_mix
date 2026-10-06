package iconmix.core;

import iconmix.util.Log;
import iconmix.util.NativeTools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.List;

/**
 * 分支 A：把生成的图标应用到文件夹（FR-4.10）与还原（FR-4.15）。
 *
 * <p>三条硬事实（实测，见需求说明书 12.1）：
 * <ol>
 *   <li>{@code desktop.ini} 必须 <b>UTF-16LE</b> 编码，否则中文路径读不出来</li>
 *   <li>文件夹必须带 <b>ReadOnly</b> 属性，这是 desktop.ini 生效的必要条件</li>
 *   <li>desktop.ini 需 <b>Hidden + System</b> 属性，与只读文件夹配合才被 shell 采纳</li>
 * </ol>
 * 删除时只认本工具的 {@code DSH_CompositeFolderIcon} 标记，避免误删他人配置。
 */
public final class ShellApplier {

    /** 本工具写入 desktop.ini 的识别标记。 */
    public static final String MARKER = "DSH_CompositeFolderIcon";

    private static final String DESKTOP_INI = "desktop.ini";
    /** 记录本工具在该文件夹里放了哪个 ico，供还原时精确清理。 */
    private static final String RECORD = ".iconmix";

    private ShellApplier() {
    }

    public static final class ApplyResult {
        public File desktopIni;
        public File icoFile;
        public File backup;
        public final java.util.List<String> notes = new java.util.ArrayList<String>();

        public String describe() {
            StringBuilder sb = new StringBuilder("已应用到 " + (desktopIni == null ? "?" : desktopIni.getParent()));
            if (icoFile != null) {
                sb.append("；图标文件 ").append(icoFile.getName());
            }
            for (String n : notes) {
                sb.append("；").append(n);
            }
            return sb.toString();
        }
    }

    /**
     * 把 ico 应用到目标文件夹。
     *
     * @param folder       目标文件夹
     * @param icoData      ico 字节
     * @param icoName      图标文件名（写入到目标文件夹内，保证自包含、不被清理）
     * @param refreshShell 是否通知 shell 刷新
     */
    public static ApplyResult apply(File folder, byte[] icoData, String icoName,
                                    boolean refreshShell, Log log) throws IOException {
        if (folder == null || !folder.isDirectory()) {
            throw new IOException("目标不是文件夹：" + folder);
        }
        ApplyResult result = new ApplyResult();

        File ico = new File(folder, icoName);
        Exporter.writeBytes(ico, icoData);
        result.icoFile = ico;

        File ini = new File(folder, DESKTOP_INI);
        if (ini.isFile() && !containsMarker(ini)) {
            File backup = new File(folder, DESKTOP_INI + ".iconmix.bak");
            if (backup.exists() && !backup.delete()) {
                result.notes.add("旧备份无法覆盖：" + backup.getName());
            }
            copyFile(ini, backup);
            result.backup = backup;
            log.warn("目标文件夹已有他人配置的 desktop.ini，已备份为 " + backup.getName());
        }

        String content = buildIni(ico.getAbsolutePath());
        writeUtf16Le(ini, content);
        result.desktopIni = ini;

        // 属性：desktop.ini 需 Hidden + System；文件夹必须 ReadOnly
        try {
            NativeTools.setAttributes(ini, true, true, true);
            NativeTools.setAttributes(folder, true, false, false);
        } catch (IOException e) {
            log.warn("设置文件属性失败（图标可能不生效）：" + e.getMessage());
            result.notes.add("设置属性失败：" + e.getMessage());
        }

        if (refreshShell) {
            try {
                NativeTools.refreshShell();
            } catch (RuntimeException e) {
                log.warn("shell 刷新失败：" + e.getMessage());
                result.notes.add("shell 刷新失败");
            }
        }

        // 记录产物名，还原时精确删除（不依赖命名约定）
        try {
            writeUtf8(new File(folder, RECORD), ico.getName());
        } catch (IOException e) {
            log.warn("写入记录文件失败：" + e.getMessage());
        }

        log.info("已写入 " + ini.getAbsolutePath() + "（UTF-16LE，含 " + MARKER + " 标记）");
        return result;
    }

    /** 还原：仅当 desktop.ini 含本工具标记时才删除（FR-4.15.2）。 */
    public static boolean revert(File folder, boolean refreshShell, Log log) throws IOException {
        if (folder == null || !folder.isDirectory()) {
            throw new IOException("目标不是文件夹：" + folder);
        }
        File ini = new File(folder, DESKTOP_INI);
        boolean removed = false;
        if (ini.isFile()) {
            if (containsMarker(ini)) {
                // 先清属性再删，否则只读文件删不掉
                try {
                    NativeTools.setAttributes(ini, false, false, false);
                } catch (IOException e) {
                    log.warn("清除 desktop.ini 属性失败：" + e.getMessage());
                }
                removed = ini.delete();
                if (removed) {
                    log.info("已删除本工具写入的 desktop.ini");
                } else {
                    log.warn("desktop.ini 删除失败（可能被占用）");
                }
            } else {
                log.warn("desktop.ini 不含 " + MARKER + " 标记，判定为他人配置，保持不动");
            }
        } else {
            log.info("该文件夹没有 desktop.ini，无需还原");
        }

        File backup = new File(folder, DESKTOP_INI + ".iconmix.bak");
        if (backup.isFile()) {
            try {
                NativeTools.setAttributes(backup, false, false, false);
                copyFile(backup, ini);
                backup.delete();
                NativeTools.setAttributes(ini, true, true, true);
                log.info("已从备份恢复原有 desktop.ini");
            } catch (IOException e) {
                log.warn("恢复备份失败：" + e.getMessage());
            }
        }

        // 文件夹的只读属性由本工具设置，还原时清掉
        try {
            NativeTools.setAttributes(folder, false, false, false);
        } catch (IOException e) {
            log.warn("清除文件夹只读属性失败：" + e.getMessage());
        }

        // 清掉本工具放进文件夹的 ico：优先按记录文件精确删除，回退到命名约定
        File record = new File(folder, RECORD);
        List<String> icoNames = new java.util.ArrayList<String>();
        if (record.isFile()) {
            try {
                String recorded = new String(readFile(record), java.nio.charset.Charset.forName("UTF-8")).trim();
                if (recorded.length() > 0) {
                    icoNames.add(recorded);
                }
            } catch (IOException e) {
                log.warn("读取记录文件失败：" + e.getMessage());
            }
        }
        icoNames.add(folder.getName() + "_icon.ico");
        for (String icoName : icoNames) {
            File ico = new File(folder, icoName);
            if (ico.isFile()) {
                try {
                    NativeTools.setAttributes(ico, false, false, false);
                } catch (IOException e) {
                    log.warn("清除图标文件属性失败：" + e.getMessage());
                }
                if (ico.delete()) {
                    log.info("已删除图标文件 " + icoName);
                } else {
                    log.warn("图标文件删除失败：" + icoName);
                }
            }
        }
        if (record.isFile() && !record.delete()) {
            log.warn("记录文件删除失败：" + RECORD);
        }

        if (refreshShell) {
            try {
                NativeTools.refreshShell();
            } catch (RuntimeException e) {
                log.warn("shell 刷新失败：" + e.getMessage());
            }
        }
        return removed;
    }

    /** desktop.ini 内容（UTF-16LE 写出）。 */
    static String buildIni(String icoAbsolutePath) {
        return "[.ShellClassInfo]\r\n"
                + "IconResource=" + icoAbsolutePath + ",0\r\n"
                + MARKER + "=1\r\n";
    }

    /** 读取 desktop.ini（UTF-16LE 优先，兼容 UTF-8 / ANSI）。 */
    public static String readIni(File ini) throws IOException {
        byte[] raw = readFile(ini);
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE) {
            return new String(raw, 2, raw.length - 2, Charset.forName("UTF-16LE"));
        }
        // 无 BOM 时按 UTF-16LE 试解（偶数长度且含大量 0x00）
        if (raw.length >= 4 && raw.length % 2 == 0) {
            int zeros = 0;
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] == 0) {
                    zeros++;
                }
            }
            if (zeros > raw.length / 4) {
                return new String(raw, Charset.forName("UTF-16LE"));
            }
        }
        return new String(raw, Charset.defaultCharset());
    }

    private static boolean containsMarker(File ini) {
        try {
            return readIni(ini).contains(MARKER);
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readFile(File f) throws IOException {
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

    /** 以 UTF-16LE + BOM 写出（Windows 的 desktop.ini 约定）。 */
    public static void writeUtf16Le(File target, String content) throws IOException {
        byte[] body = content.getBytes(Charset.forName("UTF-16LE"));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(0xFF);
        buf.write(0xFE);
        buf.write(body);
        // 先清掉只读属性，否则覆盖会失败
        if (target.exists()) {
            try {
                NativeTools.setAttributes(target, false, false, false);
            } catch (IOException ignored) {
                // 属性清理失败时继续尝试写入
            }
        }
        OutputStream out = new java.io.FileOutputStream(target);
        try {
            out.write(buf.toByteArray());
            out.flush();
        } finally {
            out.close();
        }
    }

    /** 以 UTF-8 写出（记录文件用，内容只有 ASCII/文件名）。 */
    private static void writeUtf8(File target, String content) throws IOException {
        if (target.exists()) {
            try {
                NativeTools.setAttributes(target, false, false, false);
            } catch (IOException ignored) {
                // 属性清理失败时继续
            }
        }
        OutputStream out = new java.io.FileOutputStream(target);
        try {
            out.write(content.getBytes(java.nio.charset.Charset.forName("UTF-8")));
            out.flush();
        } finally {
            out.close();
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        byte[] data = readFile(src);
        OutputStream out = new java.io.FileOutputStream(dst);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }
}
