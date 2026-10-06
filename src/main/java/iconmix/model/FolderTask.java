package iconmix.model;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 合成任务的全部可变状态：图标集合、槽位布局、排布偏好、网格参数。
 *
 * <p>需求说明书 6.3 的关键设计：<b>预览、导出的 PNG、生成的 ICO 三者共用这一份状态</b>，
 * 因此不存在「预览对了、输出还是旧顺序」的可能。
 *
 * <h3>槽位（Slot）模型</h3>
 * 网格里的每个位置都是一个<b>槽位</b>：要么承载一个图标，要么是<b>空占位</b>（透明）。
 * 末行不足时用空占位补齐（7 个图标 → 3×3，补 2 个空位）。
 *
 * <p><b>{@link #slots} 是权威数据，{@link #tiles} 是派生视图</b>（按槽位顺序收集非空槽位）。
 * 之所以这样定：空位是一个<b>位置</b>——图标可以拖进空位，而空位不会被挤到别处。
 * 早期用「压缩数组 + 按位置重新编号」的模型，任何交换都会把空位推回末尾，
 * 表现为「拖到空位后图标跑到了别的位置」，怎么改都自相矛盾。
 */
public final class FolderTask {

    /** 排布偏好：横优先 = 按行填满再换行；列优先 = 按列填满再换列。 */
    public enum LayoutMode {
        ROW_FIRST("横优先"),
        COLUMN_FIRST("列优先");

        private final String label;

        LayoutMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 一个网格槽位：要么承载某个图标，要么是空占位。 */
    public static final class Slot {
        /** 承载的图标；{@code null} 表示空占位。 */
        public final Tile tile;

        Slot(Tile tile) {
            this.tile = tile;
        }

        public boolean isEmpty() {
            return tile == null;
        }
    }

    /** 拖入目录。 */
    private final java.io.File folder;
    /** 图标列表：由槽位派生（顺序 = 槽位顺序，不含空位），导出与 ICO 用它。 */
    private final List<Tile> tiles = new ArrayList<Tile>();
    /** 自动排序得到的默认顺序，用于「恢复默认顺序」。 */
    private final List<Tile> defaultOrder = new ArrayList<Tile>();
    /** 扫描时的候选文件，用于定位「app 所在目录」。 */
    private final List<java.io.File> candidates = new ArrayList<java.io.File>();
    /** 网格槽位（权威数据），长度 = 列数 × 行数。 */
    private final List<Slot> slots = new ArrayList<Slot>();

    private LayoutMode mode = LayoutMode.ROW_FIRST;
    private int columns;
    private int rows;
    private int cellSize = 256;
    private String lastMessage;

    public FolderTask(java.io.File folder) {
        this.folder = folder;
    }

    public java.io.File folder() {
        return folder;
    }

    public String folderName() {
        return folder == null ? "output" : folder.getName();
    }

    /** 图标列表（派生视图，顺序 = 槽位顺序，不含空位）。 */
    public List<Tile> tiles() {
        return tiles;
    }

    public List<java.io.File> candidates() {
        return Collections.unmodifiableList(candidates);
    }

    public void addCandidate(java.io.File f) {
        candidates.add(f);
    }

    /**
     * 设置图标集合并记录为「默认顺序」（初次加载时调用）。
     */
    public void setTiles(List<Tile> list) {
        fillSlots(list);
        defaultOrder.clear();
        defaultOrder.addAll(tiles);
    }

    /**
     * 按给定顺序填入槽位（不触碰 {@link #defaultOrder}）。
     *
     * <p>「恢复默认顺序」必须走这里而不是 {@link #setTiles}：后者会把当前顺序
     * 再记成默认顺序，等于永远恢复不了。
     */
    private void fillSlots(List<Tile> list) {
        computeGrid(list.size());          // 必须先按「新图标数」算网格，此时 tiles 还没填充
        slots.clear();
        int total = columns * rows;
        for (int i = 0; i < total; i++) {
            slots.add(new Slot(i < list.size() ? list.get(i) : null));
        }
        syncTiles();
    }

    /** 恢复默认顺序：按初始顺序重新填入槽位。 */
    public void restoreDefaultOrder() {
        fillSlots(new ArrayList<Tile>(defaultOrder));
    }

    public boolean isDefaultOrder() {
        List<Tile> current = tiles;
        if (current.size() != defaultOrder.size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i) != defaultOrder.get(i)) {
                return false;
            }
        }
        return true;
    }

    public LayoutMode mode() {
        return mode;
    }

    /** 切换排布偏好：图标按顺序重排到新网格，空位重新补到末尾。 */
    public void setMode(LayoutMode mode) {
        this.mode = mode;
        reflow();
    }

    public int cellSize() {
        return cellSize;
    }

    public void setCellSize(int cellSize) {
        this.cellSize = Math.max(64, cellSize);
        reflow();
    }

    public int columns() {
        return columns;
    }

    public int rows() {
        return rows;
    }

    public int size() {
        return tiles.size();
    }

    public String lastMessage() {
        return lastMessage;
    }

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    // ------------------------------------------------------------------ 槽位访问

    /** 槽位列表（只读）。 */
    public List<Slot> slots() {
        return Collections.unmodifiableList(slots);
    }

    /** 槽位总数（= 列 × 行，含空位）。 */
    public int slotCount() {
        return slots.size();
    }

    /** 该槽位是否有图标。 */
    public boolean hasTile(int slotIndex) {
        return slotIndex >= 0 && slotIndex < slots.size() && slots.get(slotIndex).tile != null;
    }

    /** 槽位 → 图标；空位返回 null。 */
    public Tile tileAtSlot(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= slots.size()) {
            return null;
        }
        return slots.get(slotIndex).tile;
    }

    /** 槽位 → 图标在 {@link #tiles} 中的下标；空位返回 -1。 */
    public int tileIndexAtSlot(int slotIndex) {
        Tile t = tileAtSlot(slotIndex);
        return t == null ? -1 : tiles.indexOf(t);
    }

    /** 空占位数量。 */
    public int emptySlotCount() {
        int c = 0;
        for (Slot s : slots) {
            if (s.isEmpty()) {
                c++;
            }
        }
        return c;
    }

    // ------------------------------------------------------------------ 交换

    /**
     * 交换两个槽位的内容（拖拽交换，FR-4.6.5 / 4.6.7）。
     *
     * <p>语义就是「两个位置的内容互换」，因此：
     * <ul>
     *   <li>图标 ↔ 图标：两个图标互换位置；</li>
     *   <li>图标 ↔ 空位：图标搬到空位，空位落到图标原来的位置（界面上就是「图标挪过去了」）。</li>
     * </ul>
     * 交换后 {@link #tiles} 会按新的槽位顺序重新派生，保证导出与预览一致。
     *
     * @return 是否发生了交换
     */
    public boolean swap(int slotA, int slotB) {
        if (slotA == slotB || slotA < 0 || slotB < 0
                || slotA >= slots.size() || slotB >= slots.size()) {
            return false;
        }
        Tile a = slots.get(slotA).tile;
        Tile b = slots.get(slotB).tile;
        slots.set(slotA, new Slot(b));
        slots.set(slotB, new Slot(a));
        syncTiles();
        return true;
    }

    // ------------------------------------------------------------------ 内部

    /** 按当前图标顺序重排网格（偏好/尺寸变化时用），空位补在末尾。 */
    private void reflow() {
        List<Tile> ordered = new ArrayList<Tile>(tiles);
        computeGrid(ordered.size());
        slots.clear();
        int total = columns * rows;
        for (int i = 0; i < total; i++) {
            slots.add(new Slot(i < ordered.size() ? ordered.get(i) : null));
        }
        syncTiles();
    }

    /** 由槽位派生 tiles（按槽位顺序收集非空槽位）。 */
    private void syncTiles() {
        tiles.clear();
        for (Slot s : slots) {
            if (s.tile != null) {
                tiles.add(s.tile);
            }
        }
    }

    /**
     * 依据给定图标数与排布偏好计算网格（FR-4.5.1 / 4.6.1）。
     *
     * <p>用户的排布偏好<b>始终优先</b>：早期版本对 2 个图标强制横排
     * （照搬「避免留白」的默认规则），结果用户在 2 图标场景下切「列优先」毫无反应。
     * 现在 2 个图标也按偏好排：横优先 = 2 列 1 行，列优先 = 1 列 2 行。
     *
     * @param n 图标数（必须显式传入：调用时机常常早于 {@link #tiles} 被填充）
     */
    private void computeGrid(int n) {
        if (n <= 0) {
            columns = 0;
            rows = 0;
            return;
        }
        if (n == 1) {
            columns = 1;
            rows = 1;
            return;
        }
        if (n == 2) {
            if (mode == LayoutMode.COLUMN_FIRST) {
                columns = 1;
                rows = 2;
            } else {
                columns = 2;
                rows = 1;
            }
            return;
        }
        int base = (int) Math.ceil(Math.sqrt(n));
        switch (mode) {
            case COLUMN_FIRST:
                rows = base;
                columns = (n + rows - 1) / rows;
                break;
            case ROW_FIRST:
            default:
                columns = base;
                rows = (n + columns - 1) / columns;
                break;
        }
    }

    /**
     * 画布边长 = max(列数, 行数) × 单元格边长。
     *
     * <p>补位后网格是规整的 {@code 列 × 行}；取 max 是为了保证在行列不等时
     * 画布仍是正方形且装得下所有槽位（T-19）。
     */
    public int canvasSize() {
        return Math.max(1, Math.max(columns, rows) * cellSize);
    }

    /**
     * 槽位左上角坐标（像素），下标为<b>槽位序号</b>。
     *
     * <p>补位后网格完全规整，槽位与坐标一一对应，不需要「末行居中」这类特殊偏移
     * （那正是早期「坐标与索引脱节」的根源，T-20）。
     *
     * @return {@code [][x, y]}
     */
    public int[][] cellRects() {
        int[][] out = new int[slots.size()][];
        for (int i = 0; i < slots.size(); i++) {
            int row = columns == 0 ? 0 : i / columns;
            int col = columns == 0 ? 0 : i % columns;
            out[i] = new int[]{col * cellSize, row * cellSize};
        }
        return out;
    }

    /**
     * 像素坐标 → 槽位序号；画布外返回 -1。
     *
     * <p>这是坐标映射的<b>唯一权威实现</b>（含空位）。
     */
    public int slotAtPixel(int x, int y) {
        if (columns <= 0 || rows <= 0 || slots.isEmpty()) {
            return -1;
        }
        int canvas = canvasSize();
        if (x < 0 || y < 0 || x >= canvas || y >= canvas) {
            return -1;
        }
        int row = y / cellSize;
        int col = x / cellSize;
        if (row < 0 || row >= rows || col < 0 || col >= columns) {
            return -1;
        }
        return row * columns + col;
    }

    /** 像素坐标 → 图标在 {@link #tiles} 中的下标；该处是空位或画布外返回 -1。 */
    public int indexAtPixel(int x, int y) {
        return tileIndexAtSlot(slotAtPixel(x, y));
    }

    /** 相容入口：按格子反查 tiles 下标（空位返回 -1）。 */
    public int indexAt(int col, int row) {
        if (col < 0 || row < 0 || col >= columns || row >= rows) {
            return -1;
        }
        return tileIndexAtSlot(row * columns + col);
    }

    /** 供日志使用的排版摘要。 */
    public String gridSummary() {
        int empty = emptySlotCount();
        return size() + " 个图标 / " + columns + " 列 × " + rows + " 行 / " + mode.label()
                + " / 画布 " + canvasSize() + "px"
                + (empty > 0 ? " / 空占位 " + empty + " 个" : "");
    }

    /** 每个图标的来源路径（调试/日志用）。 */
    public List<String> sourcePaths() {
        List<String> out = new ArrayList<String>();
        for (Tile t : tiles) {
            out.add(t.entry().sourcePath());
        }
        return out;
    }

    /** 提取出的位图最大边长，用于判断是否需要放大。 */
    public static int maxSide(BufferedImage image) {
        return Math.max(image.getWidth(), image.getHeight());
    }
}
