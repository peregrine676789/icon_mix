import iconmix.extract.IconSources;
import iconmix.util.Log;

import java.awt.image.BufferedImage;
import java.io.File;

/** 针对单个文件（含超大 exe）的图标提取验证。 */
public final class DiagFile {

    public static void main(String[] args) throws Exception {
        for (String path : args) {
            File f = new File(path);
            if (!f.isFile()) {
                System.out.println("[缺失] " + path);
                continue;
            }
            long size = f.length();
            Log log = new Log();
            log.addListener(new Log.Listener() {
                @Override
                public void onLog(String line) {
                    System.out.println("        " + line);
                }
            });
            long t0 = System.currentTimeMillis();
            BufferedImage img = IconSources.loadIcon(f, log);
            long ms = System.currentTimeMillis() - t0;
            System.out.println(String.format("%-28s %8.1f MB  ->  %s  (%d ms)",
                    f.getName(), size / 1048576.0,
                    img == null ? "提取失败" : img.getWidth() + "x" + img.getHeight(),
                    ms));
        }
    }
}
