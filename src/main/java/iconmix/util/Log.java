package iconmix.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 面向 GUI 的日志收集器。所有模块只往这里写，界面订阅刷新。
 */
public final class Log {

    public interface Listener {
        void onLog(String line);
    }

    private final List<String> lines = new ArrayList<String>();
    private final List<Listener> listeners = new ArrayList<Listener>();

    public synchronized void info(String msg) {
        add("[信息] " + msg);
    }

    public synchronized void skip(String msg) {
        add("[跳过] " + msg);
    }

    public synchronized void warn(String msg) {
        add("[警告] " + msg);
    }

    public synchronized void error(String msg) {
        add("[错误] " + msg);
    }

    private void add(String line) {
        lines.add(line);
        for (Listener l : listeners) {
            l.onLog(line);
        }
    }

    public synchronized void addListener(Listener l) {
        listeners.add(l);
    }

    public synchronized List<String> snapshot() {
        return Collections.unmodifiableList(new ArrayList<String>(lines));
    }

    public synchronized void clear() {
        lines.clear();
    }
}
