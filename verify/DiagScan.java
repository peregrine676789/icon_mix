import iconmix.extract.IconSources;

import java.io.File;

/** 逐步核对 Scanner 的过滤条件，定位 exe 为何被漏掉。 */
public final class DiagScan {

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        int maxDepth = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        System.out.println("根目录: " + root.getAbsolutePath());
        System.out.println("folderName = [" + root.getName() + "]");
        System.out.println();
        walk(root, 0, maxDepth);
    }

    private static void walk(File dir, int depth, int maxDepth) {
        if (dir == null || !dir.isDirectory() || depth > maxDepth) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            System.out.println("  ".repeat(0) + "[无法读取] " + dir.getAbsolutePath());
            return;
        }
        String indent = "  ".repeat(depth);
        for (File f : children) {
            if (f.isDirectory()) {
                System.out.println(indent + "[目录 d=" + depth + "] " + f.getName());
                walk(f, depth + 1, maxDepth);
                continue;
            }
            if (!IconSources.isExecutable(f) && !IconSources.isShortcut(f) && !IconSources.isImage(f)) {
                continue;
            }
            boolean hidden = f.isHidden();
            boolean executable = IconSources.isExecutable(f);
            System.out.println(String.format("%s[%s d=%d] %s | isHidden=%s | len=%d",
                    indent, executable ? "exe" : "lnk", depth, f.getName(), hidden, f.length()));
        }
        if (depth == maxDepth) {
            System.out.println(indent + "[达到深度上限 " + maxDepth + "，不再深入]");
        }
    }
}
