package iconmix.cli;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令行参数（FR-4.13 / 7.2）。
 */
public final class Args {

    public final List<File> folders = new ArrayList<File>();
    public String layout = "auto";          // auto | row | column
    public boolean exportOnly = false;
    public File outDir;
    public String name;
    public int[] sizes;
    public boolean exportIco = true;
    public int depth = 4;
    public boolean quiet = false;
    public boolean keepHistory = false;
    public boolean applyIcon = false;
    public boolean revert = false;
    public boolean verify = false;
    public File verifyIco;
    public boolean help = false;

    public static Args parse(String[] argv) {
        Args a = new Args();
        for (int i = 0; i < argv.length; i++) {
            String s = argv[i];
            switch (s) {
                case "-h":
                case "--help":
                    a.help = true;
                    break;
                case "--layout":
                    a.layout = next(argv, ++i, s);
                    break;
                case "--export-only":
                    a.exportOnly = true;
                    break;
                case "--apply":
                    a.applyIcon = true;
                    break;
                case "--revert":
                    a.revert = true;
                    break;
                case "--out-dir":
                    a.outDir = new File(next(argv, ++i, s));
                    break;
                case "--name":
                    a.name = next(argv, ++i, s);
                    break;
                case "--sizes":
                    a.sizes = parseSizes(next(argv, ++i, s));
                    break;
                case "--ico":
                    a.exportIco = !"off".equalsIgnoreCase(next(argv, ++i, s));
                    break;
                case "--depth":
                    a.depth = Integer.parseInt(next(argv, ++i, s));
                    break;
                case "--quiet":
                    a.quiet = true;
                    break;
                case "--keep-history":
                    a.keepHistory = true;
                    break;
                case "--verify":
                    a.verify = true;
                    if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                        a.verifyIco = new File(argv[++i]);
                    }
                    break;
                default:
                    if (s.startsWith("--")) {
                        throw new IllegalArgumentException("未知参数: " + s);
                    }
                    a.folders.add(new File(s));
            }
        }
        return a;
    }

    private static String next(String[] argv, int i, String flag) {
        if (i >= argv.length) {
            throw new IllegalArgumentException(flag + " 缺少参数值");
        }
        return argv[i];
    }

    private static int[] parseSizes(String s) {
        String[] parts = s.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            int v = Integer.parseInt(parts[i].trim());
            if (v <= 0 || v > 256) {
                throw new IllegalArgumentException("尺寸非法: " + v + "（允许 1..256）");
            }
            out[i] = v;
        }
        // 由大到小排序，保证 ICO 帧顺序稳定
        java.util.Arrays.sort(out);
        for (int i = 0; i < out.length / 2; i++) {
            int t = out[i];
            out[i] = out[out.length - 1 - i];
            out[out.length - 1 - i] = t;
        }
        return out;
    }

    public static String usage() {
        return "IconMix —— 文件夹图标合成工具\n"
                + "\n"
                + "用法:\n"
                + "  IconMix                                 启动图形界面（把文件夹拖进窗口）\n"
                + "  IconMix <folder> [folder...] [选项]      命令行处理\n"
                + "  IconMix --verify <file.ico>              校验 ICO 结构\n"
                + "\n"
                + "选项:\n"
                + "  --layout row|column     排布偏好（默认自动）\n"
                + "  --export-only           仅导出结果图片，不写文件夹配置\n"
                + "  --apply                 直接应用到文件夹（写 desktop.ini）\n"
                + "  --revert                还原文件夹图标配置\n"
                + "  --out-dir <dir>         导出目录（默认 app 可执行文件所在目录）\n"
                + "  --name <name>           导出文件名（不含扩展名与日期后缀）\n"
                + "  --sizes 256,128,64,...  ICO 尺寸集合\n"
                + "  --ico on|off            是否同时导出 .ico（默认 on）\n"
                + "  --depth <n>             扫描深度（默认 4）\n"
                + "  --keep-history          保留历史产物（默认导出前清理旧产物）\n"
                + "  --quiet                 静默模式\n"
                + "  -h, --help              显示本帮助\n"
                + "\n"
                + "退出码: 0 成功 / 1 部分失败 / 2 失败\n";
    }
}
