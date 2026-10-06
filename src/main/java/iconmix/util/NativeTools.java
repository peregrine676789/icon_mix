package iconmix.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 需要落到 Windows 系统层面的少量操作。
 *
 * <p>只用两个系统自带能力，避免引入 JNA 之类的第三方依赖：
 * <ul>
 *   <li>{@code attrib} —— 设置文件夹的只读属性（desktop.ini 生效的必要条件）</li>
 *   <li>{@code powershell -Command} —— 调用 {@code SHChangeNotify} 通知 shell 刷新图标缓存</li>
 * </ul>
 * 二者都通过 {@code ProcessBuilder} 直接传参数组，不拼命令行，避免中文/空格路径被拆碎。
 */
public final class NativeTools {

    private static final Charset CONSOLE = Charset.defaultCharset();

    private NativeTools() {
    }

    /** SHCNE_ASSOCCHANGED */
    public static final int SHCNE_ASSOCCHANGED = 0x08000000;

    /**
     * 设置文件/文件夹属性。
     *
     * <p>需求说明书 T-8：PowerShell 5.1 的 {@code Set-ItemProperty -Name Attributes}
     * 不接受 {@code Directory}，{@code java.io.File#setReadOnly} 又无法设置 Hidden/System，
     * 因此统一走 {@code attrib}。
     *
     * <p><b>注意</b>：{@code attrib} 不接受把多个旗标拼成一个参数（{@code +R-H-S} 会被判为
     * 「Invalid switch」且<b>静默失败</b>），必须每个旗标单独作为一个参数传入。
     *
     * @param target 目标文件或文件夹
     * @param readOnly true 表示 +R，false 表示 -R
     * @param hidden true 表示 +H，false 表示 -H
     * @param system true 表示 +S，false 表示 -S
     */
    public static void setAttributes(File target, boolean readOnly, boolean hidden, boolean system)
            throws IOException {
        List<String> cmd = new ArrayList<String>();
        cmd.add("attrib");
        cmd.add(readOnly ? "+R" : "-R");
        cmd.add(hidden ? "+H" : "-H");
        cmd.add(system ? "+S" : "-S");
        cmd.add(target.getAbsolutePath());
        run(cmd.toArray(new String[0]));
    }

    /** 通知 shell 刷新图标缓存（SHChangeNotify + SHCNF_IDLIST）。 */
    public static void refreshShell() {
        String script = "Add-Type -Namespace IconMixNative -Name Shell -MemberDefinition '"
                + "[System.Runtime.InteropServices.DllImport(\"shell32.dll\")]"
                + "public static extern void SHChangeNotify(int eventId, int flags, System.IntPtr item1, System.IntPtr item2);';"
                + "[IconMixNative.Shell]::SHChangeNotify(0x08000000, 0x0000, [System.IntPtr]::Zero, [System.IntPtr]::Zero);";
        try {
            List<String> cmd = new ArrayList<String>();
            cmd.add("powershell");
            cmd.add("-NoProfile");
            cmd.add("-NonInteractive");
            cmd.add("-ExecutionPolicy");
            cmd.add("Bypass");
            cmd.add("-Command");
            cmd.add(script);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            drain(p);
            p.waitFor();
        } catch (Exception e) {
            // 刷新失败不影响图标已写入的事实，仅提示
            throw new IllegalStateException("shell 刷新失败: " + e.getMessage(), e);
        }
    }

    /** 是否有可用的 powershell。 */
    public static boolean hasPowerShell() {
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", "exit 0")
                    .redirectErrorStream(true).start();
            drain(p);
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void run(String... argv) throws IOException {
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
        String output;
        try {
            output = drain(p);
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("命令失败(" + code + "): " + join(argv) + " -> " + output.trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("命令被中断: " + join(argv), e);
        }
    }

    private static String drain(Process p) throws IOException {
        java.io.InputStream in = p.getInputStream();
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) > 0) {
            buf.write(tmp, 0, n);
        }
        return new String(buf.toByteArray(), CONSOLE);
    }

    private static String join(String[] argv) {
        return Arrays.toString(argv);
    }
}
