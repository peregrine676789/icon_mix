import iconmix.extract.FrameDecoder;
import iconmix.extract.IconSources;
import iconmix.extract.LnkParser;
import iconmix.extract.PeResourceParser;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 提取诊断：对目录里每个 exe/lnk 分阶段报告成功与失败原因，定位提取率的瓶颈。 */
public final class Diag {

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 300;

        List<File> exes = new ArrayList<File>();
        List<File> lnks = new ArrayList<File>();
        collect(dir, exes, lnks, limit);

        System.out.println("目录: " + dir.getAbsolutePath());
        System.out.println("exe/dll: " + exes.size() + " 个，lnk: " + lnks.size() + " 个");
        System.out.println();

        int okPe = 0;
        int parseFail = 0;
        int noGroup = 0;
        int groupNoIcon = 0;
        int decodeFail = 0;
        int okImage = 0;
        List<String> failures = new ArrayList<String>();
        java.util.Set<String> hashes = new java.util.HashSet<String>();

        for (File f : exes) {
            String stage;
            try {
                byte[] data = IconSources.readFully(f);
                if (data.length == 0) {
                    failures.add(f.getName() + " | 空文件");
                    continue;
                }
                PeResourceParser pe;
                try {
                    pe = PeResourceParser.parse(data);
                } catch (Exception e) {
                    parseFail++;
                    failures.add(f.getName() + " | PE解析失败: " + e.getMessage());
                    continue;
                }
                List<PeResourceParser.IconGroupHeader> groups = pe.readIconGroups();
                if (groups.isEmpty()) {
                    noGroup++;
                    failures.add(f.getName() + " | 无 RT_GROUP_ICON 资源");
                    continue;
                }
                byte[] raw = null;
                try {
                    raw = PeResourceParser.extractLargestIcon(data);
                } catch (Exception e) {
                    groupNoIcon++;
                    failures.add(f.getName() + " | 有图标组但取不到 RT_ICON: " + e.getMessage());
                    continue;
                }
                try {
                    BufferedImage img = FrameDecoder.decode(raw, true);
                    okPe++;
                    String h = hash(img);
                    hashes.add(h);
                } catch (Exception e) {
                    decodeFail++;
                    failures.add(f.getName() + " | 帧解码失败: " + e.getMessage()
                            + " (首字节 " + (raw.length > 4 ? String.format("%02X %02X", raw[0], raw[1]) : "?") + ")");
                }
            } catch (Exception e) {
                failures.add(f.getName() + " | 未知: " + e);
            }
        }

        for (File f : lnks) {
            BufferedImage img = IconSources.loadIcon(f, new iconmix.util.Log());
            if (img == null) {
                String target = "?";
                try {
                    target = LnkParser.resolveTarget(IconSources.readFully(f));
                } catch (Exception ignored) {
                    // 忽略
                }
                failures.add(f.getName() + " | 快捷方式取不到图标（目标=" + target + "）");
            } else {
                okImage++;
                hashes.add(hash(img));
            }
        }

        System.out.println("PE 提取成功      : " + okPe);
        System.out.println("快捷方式成功     : " + okImage);
        System.out.println("去重后唯一图标数 : " + hashes.size());
        System.out.println();
        System.out.println("失败分类：");
        System.out.println("  PE 解析失败        : " + parseFail);
        System.out.println("  无 RT_GROUP_ICON   : " + noGroup);
        System.out.println("  有组但取不到 RT_ICON: " + groupNoIcon);
        System.out.println("  帧解码失败         : " + decodeFail);
        System.out.println();
        System.out.println("失败明细（按类别，各列前 12 条）：");
        for (String cat : new String[]{"PE解析失败", "无 RT_GROUP_ICON", "有图标组但取不到", "帧解码失败", "快捷方式取不到图标", "未知"}) {
            int shown = 0;
            for (String s : failures) {
                if (s.contains(cat)) {
                    if (shown < 12) {
                        System.out.println("  " + s);
                    }
                    shown++;
                }
            }
            if (shown > 12) {
                System.out.println("  ...（" + cat + " 共 " + shown + " 条）");
            }
        }
    }

    private static void collect(File dir, List<File> exes, List<File> lnks, int limit) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (exes.size() + lnks.size() >= limit) {
                return;
            }
            if (f.isDirectory()) {
                continue;
            }
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".exe") || n.endsWith(".dll")) {
                exes.add(f);
            } else if (n.endsWith(".lnk")) {
                lnks.add(f);
            }
        }
    }

    private static String hash(BufferedImage img) {
        return IconSources.hashImage(img);
    }
}
