package iconmix.core;

import iconmix.extract.IconSources;
import iconmix.model.Tile;
import iconmix.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 目录扫描（FR-4.2）与素材收集。
 *
 * <p>规则要点：
 * <ul>
 *   <li>深度默认 4 层（FR-4.2.2）</li>
 *   <li>只收 .exe/.dll 与 .lnk；目录内的图片作为备选素材（FR-4.2.4）</li>
 *   <li>无扩展名文件一律跳过且不报错（FR-4.2.6）</li>
 *   <li>跳过 desktop.ini 与隐藏/系统文件，避免自我引用（FR-4.2.8）</li>
 *   <li>排序：.exe 优先于 .lnk，同组按文件名升序（FR-4.4.3）</li>
 * </ul>
 */
public final class Scanner {

    private static final String DESKTOP_INI = "desktop.ini";

    private Scanner() {
    }

    /** 扫描并返回排序后的候选文件。 */
    public static List<File> scan(File folder, int maxDepth, Log log) {
        List<File> exes = new ArrayList<File>();
        List<File> links = new ArrayList<File>();
        List<File> images = new ArrayList<File>();
        walk(folder, 0, maxDepth, exes, links, images, log);

        Collections.sort(exes, BY_NAME);
        Collections.sort(links, BY_NAME);
        Collections.sort(images, BY_NAME);

        // 排序：先 exe，再 lnk，最后图片素材
        List<File> all = new ArrayList<File>();
        all.addAll(exes);
        all.addAll(links);
        all.addAll(images);
        log.info("扫描完成：exe/dll " + exes.size() + " 个，快捷方式 " + links.size()
                + " 个，图片素材 " + images.size() + " 个");
        return all;
    }

    private static final Comparator<File> BY_NAME = new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            return a.getName().compareToIgnoreCase(b.getName());
        }
    };

    private static void walk(File dir, int depth, int maxDepth,
                             List<File> exes, List<File> links, List<File> images, Log log) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        if (depth > maxDepth) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            log.skip("无权限读取目录：" + dir.getAbsolutePath());
            return;
        }
        for (File f : children) {
            if (f.isDirectory()) {
                walk(f, depth + 1, maxDepth, exes, links, images, log);
                continue;
            }
            String name = f.getName();
            if (DESKTOP_INI.equalsIgnoreCase(name)) {
                continue;       // 不把 desktop.ini 当素材
            }
            if (isHiddenOrSystem(f)) {
                continue;
            }
            if (isOwnProduct(f, dir.getName())) {
                // 本文件夹自己产出的结果图/图标不参与合成，否则重复运行会把自己的输出吃成素材（FR-4.2.8）
                log.skip(name + "：本文件夹自己的产物，不作为素材");
                continue;
            }
            if (IconSources.isExecutable(f)) {
                exes.add(f);
            } else if (IconSources.isShortcut(f)) {
                links.add(f);
            } else if (IconSources.isImage(f)) {
                images.add(f);
            }
            // 其余（含无扩展名资源文件）静默跳过
        }
    }

    /**
     * 是否是「本文件夹自己」的产物：{@code {文件夹名}_icon.png/ico} 或 {@code {文件夹名}_icon(日期).png/ico}。
     *
     * <p>只排除与当前文件夹同名的产物，而不是「所有含 _icon 的文件」——
     * 否则用户真的想用 {@code xxx_icon.png} 当素材时会被误伤。
     * 命名规则与 {@link iconmix.core.Exporter} 保持一致。
     */
    static boolean isOwnProduct(File f, String folderName) {
        String name = f.getName();
        if (".iconmix".equalsIgnoreCase(name)) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        String ext;
        if (lower.endsWith(".png")) {
            ext = ".png";
        } else if (lower.endsWith(".ico")) {
            ext = ".ico";
        } else {
            return false;
        }
        String stem = name.substring(0, name.length() - ext.length());
        String base = folderName + "_icon";
        if (stem.equals(base)) {
            return true;
        }
        if (!stem.startsWith(base + "(") || !stem.endsWith(")")) {
            return false;
        }
        String inner = stem.substring(base.length() + 1, stem.length() - 1);
        return iconmix.core.Exporter.isDateLike(inner);
    }

    /**
     * 隐藏/系统属性判断。
     *
     * <p>{@code File#isHidden()} 在 Windows 上只认 Hidden 位，这里再额外排除
     * 点开头的临时文件与 $ 结尾的卷影文件。
     */
    private static boolean isHiddenOrSystem(File f) {
        if (f.isHidden()) {
            return true;
        }
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.startsWith("~$") || n.endsWith(".tmp");
    }

    /** 主程序（首个 exe）所在目录，用于 FR-4.11.2 的默认导出目录。 */
    public static File primaryExeDir(List<File> candidates) {
        for (File f : candidates) {
            if (IconSources.isExecutable(f)) {
                return f.getParentFile();
            }
        }
        return null;
    }

    /** 提取图标：把候选文件交给 IconSources 做提取与去重。 */
    public static List<Tile> extract(List<File> candidates, int maxCount, Log log) {
        return IconSources.extractAndDedup(candidates, maxCount, log);
    }
}
