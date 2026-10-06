import iconmix.extract.IconSources;
import iconmix.extract.LnkParser;

import java.io.File;

/** 打印某个快捷方式的全部候选与存在性，定位「候选都取不到图标」的原因。 */
public final class DiagOne {

    public static void main(String[] args) throws Exception {
        File f = new File(args[0]);
        byte[] data = IconSources.readFully(f);
        LnkParser.Link link = LnkParser.parse(data);
        System.out.println("文件: " + f.getName() + "  长度=" + data.length);
        System.out.println("error       = " + link.error);
        System.out.println("target      = " + link.target);
        System.out.println("idListTarget= " + link.idListTarget);
        System.out.println("iconLocation= " + link.iconLocation);
        System.out.println("localBase   = " + link.localBasePath);
        System.out.println("relative    = " + link.relativePath);
        System.out.println("workingDir  = " + link.workingDir);
        System.out.println();
        System.out.println("iconLocation 展开 = " + LnkParser.expand(link.iconLocation));
        System.out.println();
        System.out.println("扫描到的路径候选（" + link.scannedPaths.size() + " 个）：");
        for (String p : link.scannedPaths) {
            System.out.println("  " + (new File(p).isFile() ? "[存在]" : "[不存在]") + " " + p);
        }
        System.out.println();
        System.out.println("最终候选顺序（iconCandidates）：");
        int i = 0;
        for (String p : link.iconCandidates()) {
            File pf = new File(p);
            System.out.println(String.format("  %2d. %-11s %s", ++i,
                    pf.isFile() ? "[文件]" : (pf.isDirectory() ? "[目录]" : "[不存在]"), p));
            System.out.println("      码点: " + codePoints(p));
        }
    }

    /** 以 U+XXXX 形式输出码点，绕过控制台编码问题。 */
    private static String codePoints(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && i < 120; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                sb.append(c);
            } else {
                sb.append(String.format("<U+%04X>", (int) c));
            }
        }
        return sb.toString();
    }
}
