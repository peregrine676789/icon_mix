package iconmix.model;

import java.awt.image.BufferedImage;

/**
 * 图标实体：来源信息 + 已解码的 ARGB 位图。
 *
 * <p>位图在内存中长期保留，布局偏好切换与拖拽交换只重排顺序、重新渲染，
 * <b>不重新提取</b>（见 FR-4.6.4）。
 */
public final class Tile {

    private final IconEntry entry;
    private final BufferedImage image;

    public Tile(IconEntry entry, BufferedImage image) {
        this.entry = entry;
        this.image = image;
    }

    public IconEntry entry() {
        return entry;
    }

    public BufferedImage image() {
        return image;
    }

    public String name() {
        return entry.displayName();
    }

    @Override
    public String toString() {
        return name();
    }
}
