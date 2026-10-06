package iconmix.model;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次扫描的结果：候选文件清单 + 成功提取出的图标。
 */
public final class ScanResult {

    private final File folder;
    private final List<File> candidates = new ArrayList<File>();
    private final List<Tile> tiles = new ArrayList<Tile>();
    private final List<String> skipped = new ArrayList<String>();

    public ScanResult(File folder) {
        this.folder = folder;
    }

    public File folder() {
        return folder;
    }

    public List<File> candidates() {
        return Collections.unmodifiableList(candidates);
    }

    public void addCandidate(File f) {
        candidates.add(f);
    }

    public List<Tile> tiles() {
        return tiles;
    }

    public void addTile(Tile t) {
        tiles.add(t);
    }

    public List<String> skipped() {
        return Collections.unmodifiableList(skipped);
    }

    public void addSkipped(String reason) {
        skipped.add(reason);
    }

    /** 去重后被丢弃的数量，用于日志。 */
    public int duplicateCount() {
        return Math.max(0, candidates.size() - skipped.size() - tiles.size());
    }
}
