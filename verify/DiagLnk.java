import iconmix.extract.IconSources;
import iconmix.extract.LnkParser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 快捷方式解析诊断：这是「一个图标都抓不到」时最可能的原因。 */
public final class DiagLnk {

    public static void main(String[] args) throws Exception {
        List<File> lnks = new ArrayList<File>();
        for (String d : args) {
            collect(new File(d), lnks, 0);
        }
        System.out.println("共发现快捷方式 " + lnks.size() + " 个");
        System.out.println();

        int resolved = 0;
        int targetMissing = 0;
        int iconOk = 0;
        int noTarget = 0;
        int noIcon = 0;
        int shown = 0;

        for (File f : lnks) {
            String target = null;
            try {
                target = LnkParser.resolveTarget(IconSources.readFully(f));
            } catch (Exception e) {
                target = null;
            }
            if (target == null) {
                noTarget++;
                if (shown++ < 25) {
                    System.out.println("[无目标] " + f.getName());
                }
                continue;
            }
            resolved++;
            boolean exists = new File(target).isFile();
            if (!exists) {
                targetMissing++;
            }
            java.awt.image.BufferedImage img = IconSources.loadIcon(f, new iconmix.util.Log());
            if (img == null) {
                noIcon++;
                if (shown++ < 25) {
                    System.out.println("[取不到图标] " + f.getName()
                            + " -> " + target + (exists ? "" : "（目标文件不存在）"));
                }
            } else {
                iconOk++;
            }
        }

        System.out.println();
        System.out.println("解析出目标      : " + resolved);
        System.out.println("  其中目标不存在: " + targetMissing);
        System.out.println("未解析出目标    : " + noTarget);
        System.out.println("成功取到图标    : " + iconOk);
        System.out.println("目标在但无图标  : " + noIcon);
    }

    private static void collect(File dir, List<File> out, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 6) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (f.isDirectory()) {
                collect(f, out, depth + 1);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".lnk")) {
                out.add(f);
            }
        }
    }
}
