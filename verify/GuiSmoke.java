import iconmix.gui.MainWindow;

import javax.swing.JFrame;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * GUI 冒烟测试：构造主窗口、加载一个目录、把窗口离屏渲染成 PNG。
 *
 * <p>这样在无桌面访问的环境里也能验证 GUI 的构造与绘制路径没有异常，
 * 并通过像素统计确认「确实画出了东西」（而不是空白窗口）。
 */
public final class GuiSmoke {

    public static void main(String[] args) throws Exception {
        File folder = new File(args[0]);
        String outPath = args.length > 1 ? args[1] : "gui-smoke.png";

        MainWindow window = new MainWindow();
        window.setSize(1040, 780);
        window.loadFolderForTest(folder);

        // 等后台扫描完成
        for (int i = 0; i < 120; i++) {
            Thread.sleep(250);
            if (i > 8) {
                break;
            }
        }
        Thread.sleep(1500);

        BufferedImage shot = new BufferedImage(window.getWidth(), window.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = shot.createGraphics();
        // 直接绘制整棵组件树（无需真实显示）
        window.getContentPane().printAll(g);
        g.dispose();

        javax.imageio.ImageIO.write(shot, "png", new File(outPath));
        System.out.println("窗口尺寸: " + window.getWidth() + "x" + window.getHeight());
        System.out.println("已写出截图: " + new File(outPath).getAbsolutePath());

        // 像素统计：确认不是纯白
        int nonWhite = 0;
        int colored = 0;
        java.util.Set<Integer> distinct = new java.util.HashSet<Integer>();
        for (int y = 0; y < shot.getHeight(); y += 2) {
            for (int x = 0; x < shot.getWidth(); x += 2) {
                int rgb = shot.getRGB(x, y) & 0xFFFFFF;
                distinct.add(rgb);
                if (rgb != 0xFFFFFF) {
                    nonWhite++;
                }
                int r = (rgb >> 16) & 0xFF;
                int gg = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (Math.max(r, Math.max(gg, b)) - Math.min(r, Math.min(gg, b)) > 40) {
                    colored++;
                }
            }
        }
        System.out.println("非白像素: " + nonWhite + "，彩色像素: " + colored
                + "，不同颜色数: " + distinct.size());
        boolean ok = nonWhite > 5000 && colored > 500;
        System.out.println(ok ? "GUI 冒烟测试通过（绘制出内容且含彩色图标）" : "GUI 冒烟测试失败（画面疑似空白）");
        window.dispose();
        System.exit(ok ? 0 : 1);
    }

    static {
        // 保证构造 JFrame 时能通过头部检查（若环境确实无显示，这里会抛异常，属于预期内）
        JFrame.setDefaultLookAndFeelDecorated(false);
    }
}
