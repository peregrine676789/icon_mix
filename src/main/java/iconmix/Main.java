package iconmix;

import iconmix.cli.Args;
import iconmix.cli.CliRunner;
import iconmix.gui.MainWindow;
import iconmix.util.Log;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;

/**
 * 程序入口。
 *
 * <ul>
 *   <li>无参数 → 启动图形界面（主用法）</li>
 *   <li>有参数 → 命令行模式，退出码 0 成功 / 1 部分失败 / 2 失败</li>
 * </ul>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] argv) {
        Args args;
        try {
            args = Args.parse(argv);
        } catch (IllegalArgumentException e) {
            System.err.println("[错误] " + e.getMessage());
            System.err.print(Args.usage());
            System.exit(2);
            return;
        }

        if (args.help) {
            printUsage();
            return;
        }

        boolean cli = args.verify || !args.folders.isEmpty();
        if (cli) {
            Log log = new Log();
            int code = CliRunner.run(args, log);
            for (String line : log.snapshot()) {
                System.out.println(line);
            }
            System.exit(code);
            return;
        }

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("[错误] 当前环境无图形界面，请用命令行模式：IconMix <folder>");
            printUsage();
            System.exit(2);
            return;
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 用默认外观即可
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    new MainWindow().setVisible(true);
                } catch (Throwable t) {
                    t.printStackTrace();
                    JOptionPane.showMessageDialog(null,
                            "界面启动失败：" + t, "IconMix", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
    }

    /**
     * 输出帮助与用法。
     *
     * <p>Windows 控制台默认代码页是 GBK，JDK 18+ 的 stdout 默认按 UTF-8 编码会把中文变成乱码。
     * 这里用反射尝试用系统默认编码重建 stdout（JDK 8 上该构造函数不存在，反射失败时静默回退，
     * 保证源码仍兼容 JDK 8）。
     */
    private static void printUsage() {
        System.out.print(Args.usage());
        System.out.flush();
    }

    static {
        try {
            java.lang.reflect.Constructor<java.io.PrintStream> ctor =
                    java.io.PrintStream.class.getConstructor(java.io.OutputStream.class, java.nio.charset.Charset.class);
            java.io.PrintStream ps = ctor.newInstance(System.out, java.nio.charset.Charset.defaultCharset());
            System.setOut(ps);
        } catch (Throwable ignored) {
            // JDK 8 或受限环境：保持原样
        }
    }
}
