package iconmix.model;

/**
 * 一个参与合成的图标。
 */
public final class IconEntry {

    private final String sourcePath;
    private final String displayName;
    private final String pixelHash;

    public IconEntry(String sourcePath, String displayName, String pixelHash) {
        this.sourcePath = sourcePath;
        this.displayName = displayName;
        this.pixelHash = pixelHash;
    }

    public String sourcePath() {
        return sourcePath;
    }

    /** 用于界面展示的名字（通常是 exe 文件名去掉扩展名）。 */
    public String displayName() {
        return displayName;
    }

    public String pixelHash() {
        return pixelHash;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
