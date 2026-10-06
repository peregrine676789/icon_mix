package iconmix.extract;

import iconmix.model.IconEntry;
import iconmix.model.Tile;
import iconmix.util.Log;
import iconmix.util.PngIO;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 图标来源的统一入口：从文件解出 ARGB 位图，并做像素级去重。
 *
 * <p>支持的类型：.exe/.dll（PE 资源）、.lnk（解析目标后递归取 PE 图标）、.ico、png/jpg/bmp。
 */
public final class IconSources {

    private IconSources() {
    }

    public static boolean isExecutable(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".exe") || n.endsWith(".dll") || n.endsWith(".ocx") || n.endsWith(".scr");
    }

    public static boolean isShortcut(File f) {
        return f.getName().toLowerCase(Locale.ROOT).endsWith(".lnk");
    }

    public static boolean isImage(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".bmp") || n.endsWith(".ico") || n.endsWith(".gif");
    }

    /**
     * 从一个文件解出图标位图。
     *
     * @param log 记录跳过原因
     * @return 解码后的位图；无法解出返回 null
     */
    public static BufferedImage loadIcon(File file, Log log) {
        String name = file.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        try {
            if (isShortcut(file)) {
                return loadFromShortcut(file, log, 0);
            }
            if (isExecutable(file)) {
                return loadPeIcon(file, log);
            }
            if (lower.endsWith(".ico")) {
                return FrameDecoder.decodeIco(readFully(file));
            }
            if (isImage(file)) {
                String fromIco = lower.endsWith(".ico") ? "ICO" : "图片";
                try {
                    BufferedImage img = ImageIO.read(file);
                    if (img == null) {
                        log.skip(name + "：" + fromIco + "读取失败，无法解码该格式");
                        return null;
                    }
                    return FrameDecoder.toArgb(img);
                } catch (IOException iao) {
                    log.skip(name + "：" + fromIco + "读取异常（" + iao.getMessage() + "）");
                    return null;
                }
            }
            return null;
        } catch (IOException e) {
            log.skip(name + "：" + e.getMessage());
            return null;
        } catch (RuntimeException e) {
            log.skip(name + "：解析异常 " + e);
            return null;
        }
    }

    /**
     * 快捷方式取图标。
     *
     * <p>关键：<b>不能只认目标文件</b>。实测开始菜单 166 个快捷方式里有 117 个的目标已不存在
     * （升级残留、卸载残留、指向参数而非程序），但它们的 {@code IconLocation} 依然有效。
     * 因此按 4.3.3/4.3.4 的优先级逐个候选尝试，任一成功即返回。
     */
    private static BufferedImage loadFromShortcut(File lnk, Log log, int depth) throws IOException {
        if (depth > 2) {
            log.skip(lnk.getName() + "：快捷方式嵌套层级过深");
            return null;
        }
        byte[] data = readFully(lnk);
        LnkParser.Link link = LnkParser.parse(data);
        List<String> candidates = link.iconCandidates();
        if (candidates.isEmpty()) {
            log.skip(lnk.getName() + "：无法解析快捷方式目标"
                    + (link.error == null ? "" : "（" + link.error + "）"));
            return null;
        }
        List<String> tried = new ArrayList<String>();
        for (String candidate : candidates) {
            File target = new File(candidate);
            if (!target.isFile()) {
                continue;
            }
            tried.add(candidate);
            if (isShortcut(target)) {
                BufferedImage nested = loadFromShortcut(target, log, depth + 1);
                if (nested != null) {
                    return nested;
                }
                continue;
            }
            if (isExecutable(target) || isImage(target)) {
                BufferedImage img = loadIcon(target, log);
                if (img != null) {
                    return img;
                }
            }
        }
        if (tried.isEmpty()) {
            log.skip(lnk.getName() + "：候选来源都不存在（首个候选 " + candidates.get(0) + "）");
        } else {
            log.skip(lnk.getName() + "：候选 " + tried.size() + " 个均未取到图标（首个 " + tried.get(0) + "）");
        }
        return null;
    }

    /**
     * 提取+去重。去重键为像素内容的 SHA-256（需求说明书 FR-4.4.1 / 4.4.2）：
     * 同一程序的不同快捷方式只保留首次出现的那个。
     */
    public static List<Tile> extractAndDedup(List<File> files, int maxCount, Log log) {
        List<Tile> tiles = new ArrayList<Tile>();
        Set<String> hashes = new HashSet<String>();
        for (File f : files) {
            if (tiles.size() >= maxCount) {
                log.info("已达到图标数量上限 " + maxCount + "，其余文件不再处理");
                break;
            }
            BufferedImage img = loadIcon(f, log);
            if (img == null) {
                continue;
            }
            String hash = hashImage(img);
            if (!hashes.add(hash)) {
                log.info("去重：跳过内容相同的 " + f.getName());
                continue;
            }
            tiles.add(new Tile(new IconEntry(f.getAbsolutePath(), baseName(f), hash), img));
        }
        return tiles;
    }

    private static String baseName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** 像素内容哈希：用 PngIO 编码后的字节做 SHA-256。 */
    public static String hashImage(BufferedImage img) {
        try {
            byte[] png = PngIO.encode(img);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(png);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (IOException e) {
            return "img@" + System.identityHashCode(img);
        } catch (NoSuchAlgorithmException e) {
            return "img@" + System.identityHashCode(img);
        }
    }

    /** 通用整文件读取的上限：仅用于 .lnk / .ico / 图片等小文件（哈希、解析都需要全量）。 */
    public static final long MAX_INLINE_FILE = 64L * 1024 * 1024;

    /** PE 文件可以非常大（实测 VS Code 的 Code.exe 达 235MB），上限放宽到 2GB。 */
    public static final long MAX_PE_FILE = 2L * 1024 * 1024 * 1024;

    public static byte[] readFully(File f) throws IOException {
        return readFully(f, MAX_INLINE_FILE);
    }

    /**
     * 读入整个文件。
     *
     * @param maxBytes 上限；超过则抛异常（调用方据此提示"文件过大"）
     */
    public static byte[] readFully(File f, long maxBytes) throws IOException {
        long len = f.length();
        if (len > maxBytes) {
            throw new IOException("文件过大（" + len + " 字节，上限 " + maxBytes + "）");
        }
        if (len <= 0) {
            return new byte[0];
        }
        byte[] buf = new byte[(int) len];
        InputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            if (off < buf.length) {
                byte[] shorter = new byte[off];
                System.arraycopy(buf, 0, shorter, 0, off);
                return shorter;
            }
            return buf;
        } finally {
            in.close();
        }
    }

    /**
     * 提取 PE 图标。用「随机访问需要的那部分」而不是整文件读入：
     * 图标定义在 PE 头与资源节里，把一个 235MB 的 exe 整个读进内存既慢又浪费。
     */
    private static BufferedImage loadPeIcon(File file, Log log) throws IOException {
        long length = file.length();
        if (length > MAX_PE_FILE) {
            throw new IOException("文件过大（" + length + " 字节）");
        }
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            byte[] iconData = PeResourceParser.extractLargestIcon(raf);
            return FrameDecoder.decode(iconData, true);
        } finally {
            raf.close();
        }
    }

    /** 从内存字节解出图标（测试用）。 */
    public static BufferedImage loadIconFromBytes(byte[] data, String hint) throws IOException {
        if (hint.toLowerCase(Locale.ROOT).endsWith(".ico")) {
            return FrameDecoder.decodeIco(data);
        }
        if (isPngData(data)) {
            InputStream in = new java.io.ByteArrayInputStream(data);
            BufferedImage img = ImageIO.read(in);
            in.close();
            if (img == null) {
                throw new IOException("PNG 解码失败");
            }
            return FrameDecoder.toArgb(img);
        }
        return FrameDecoder.decode(PeResourceParser.extractLargestIcon(data), true);
    }

    private static boolean isPngData(byte[] data) {
        return FrameDecoder.isPng(data, 0);
    }
}
