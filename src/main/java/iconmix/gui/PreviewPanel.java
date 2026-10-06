package iconmix.gui;

import iconmix.model.FolderTask;
import iconmix.model.Tile;

import javax.swing.JPanel;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/**
 * 预览区：等比显示合成结果，并支持<b>拖拽交换</b>图标位置（FR-4.6.5 / 4.7）。
 *
 * <p>所见即所得：本面板绘制的内容与导出 PNG / 生成 ICO 来自同一份 {@link FolderTask}，
 * 并且都按「格子 = cellSize」的比例绘制，所以调整顺序后预览不会与结果不符。
 */
public final class PreviewPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    public interface SwapListener {
        /** @param fromSlot 拖拽起点槽位 @param toSlot 目标槽位（可能是空占位） */
        void onSwap(int fromSlot, int toSlot);
    }

    private transient FolderTask task;
    private transient SwapListener swapListener;
    private int displaySize = 512;

    /** 拖拽源的槽位号（-1 表示没有）；空占位不会被选为源。 */
    private int pressedSlot = -1;
    /** 当前悬停的槽位号。 */
    private int hoverSlot = -1;
    private int dragX;
    private int dragY;
    private boolean dragging;

    public PreviewPanel() {
        setBackground(new Color(0xFA, 0xFA, 0xFA));
        setPreferredSize(new Dimension(displaySize, displaySize));
        setToolTipText("按住某个图标拖到另一个格子上即可交换位置");

        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (task == null) {
                    return;
                }
                int slot = hitTest(e.getX(), e.getY());
                if (slot >= 0) {
                    pressedSlot = slot;
                    dragging = false;
                    dragX = e.getX();
                    dragY = e.getY();
                    repaint();
                }
                // 按在空占位上不进入拖拽：空位没有图标可拖（FR-4.6.11）
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (pressedSlot < 0) {
                    return;
                }
                dragging = true;
                dragX = e.getX();
                dragY = e.getY();
                // 悬停高亮允许指向空占位（提示「可以放到这里」）
                hoverSlot = slotAt(e.getX(), e.getY());
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (pressedSlot >= 0) {
                    int target = hitTest(e.getX(), e.getY());
                    // 目标可以是空占位：图标挪到空位、空位落到原处
                    if (target >= 0 && target != pressedSlot && swapListener != null) {
                        swapListener.onSwap(pressedSlot, target);
                    }
                }
                pressedSlot = -1;
                hoverSlot = -1;
                dragging = false;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (pressedSlot < 0) {
                    hoverSlot = -1;
                    repaint();
                }
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
    }

    public void setTask(FolderTask task) {
        this.task = task;
        this.pressedSlot = -1;
        this.hoverSlot = -1;
        this.dragging = false;
        updatePreferredSize();
        repaint();
    }

    public void setSwapListener(SwapListener listener) {
        this.swapListener = listener;
    }

    public void setDisplaySize(int size) {
        this.displaySize = Math.max(128, size);
        updatePreferredSize();
        repaint();
    }

    private void updatePreferredSize() {
        setPreferredSize(new Dimension(displaySize, displaySize));
        revalidate();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            if (task == null || task.size() == 0) {
                g.setColor(new Color(0x99, 0x99, 0x99));
                g.drawString("把文件夹拖进窗口，或点「选择文件夹」", 20, 30);
                g.drawString("生成后可在下方预览中拖拽图标交换位置", 20, 52);
                return;
            }

            int canvas = task.canvasSize();
            double scale = (double) displaySize / canvas;
            int ox = (getWidth() - displaySize) / 2;
            int oy = (getHeight() - displaySize) / 2;

            // 棋盘格底衬，便于看清透明区域
            drawCheckerboard(g, ox, oy, displaySize);

            int cell = (int) Math.round(task.cellSize() * scale);
            int[][] rects = task.cellRects();
            // 遍历「槽位」而非图标：空占位也要画出虚线框，并且可以作为拖拽交换的目标
            int total = Math.min(rects.length, task.slots().size());
            for (int slot = 0; slot < total; slot++) {
                Tile tile = task.tileAtSlot(slot);
                int[] r = rects[slot];
                int x = ox + (int) Math.round(r[0] * scale);
                int y = oy + (int) Math.round(r[1] * scale);
                int box = Math.max(1, (int) Math.round(cell * 0.82));

                if (tile == null) {
                    // 空占位：透明 + 虚线提示，表示此格可放置图标
                    g.setColor(new Color(0xC8, 0xC8, 0xC8));
                    g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                            10f, new float[]{5f, 5f}, 0f));
                    g.drawRect(x + 2, y + 2, cell - 5, cell - 5);
                } else {
                    // 拖拽中的源格淡化
                    if (dragging && slot == pressedSlot) {
                        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.30f));
                    }
                    drawScaledIcon(g, tile.image(), x, y, cell, box);
                    g.setComposite(AlphaComposite.SrcOver);
                }

                boolean isTarget = pressedSlot >= 0 && slot == hoverSlot && slot != pressedSlot;
                boolean isSource = slot == pressedSlot;
                if (isTarget) {
                    g.setColor(new Color(0x1E, 0x88, 0xE5));
                    g.setStroke(new BasicStroke(2f));
                    g.drawRect(x + 1, y + 1, cell - 3, cell - 3);
                } else if (isSource) {
                    g.setColor(new Color(0xFF, 0x98, 0x00));
                    g.setStroke(new BasicStroke(2f));
                    g.drawRect(x + 1, y + 1, cell - 3, cell - 3);
                }
            }

            // 跟随光标的拖拽影像
            if (dragging && pressedSlot >= 0) {
                Tile tile = task.tileAtSlot(pressedSlot);
                if (tile != null) {
                    int ghost = Math.max(24, cell / 2);
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.70f));
                    drawScaledIcon(g, tile.image(), dragX - ghost / 2, dragY - ghost / 2, ghost, ghost);
                    g.setComposite(AlphaComposite.SrcOver);
                }
            }

            g.setColor(new Color(0xBB, 0xBB, 0xBB));
            g.drawRect(ox, oy, displaySize - 1, displaySize - 1);
        } finally {
            g.dispose();
        }
    }

    private void drawScaledIcon(Graphics2D g, BufferedImage img, int x, int y, int cell, int box) {
        double ratio = Math.min((double) box / img.getWidth(), (double) box / img.getHeight());
        int tw = Math.max(1, (int) Math.round(img.getWidth() * ratio));
        int th = Math.max(1, (int) Math.round(img.getHeight() * ratio));
        int dx = x + (cell - tw) / 2;
        int dy = y + (cell - th) / 2;
        g.drawImage(img, dx, dy, tw, th, null);
    }

    private void drawCheckerboard(Graphics2D g, int x, int y, int size) {
        int cell = 16;
        for (int i = 0; i * cell < size; i++) {
            for (int j = 0; j * cell < size; j++) {
                boolean odd = ((i + j) & 1) == 0;
                g.setColor(odd ? new Color(0xF2, 0xF2, 0xF2) : new Color(0xE4, 0xE4, 0xE4));
                g.fillRect(x + i * cell, y + j * cell,
                        Math.min(cell, size - i * cell), Math.min(cell, size - j * cell));
            }
        }
    }

    /**
     * 把鼠标坐标换算成<b>槽位号</b>；按在空占位上返回 -1（空位不能作为拖拽源）。
     *
     * <p>坐标映射统一委托给 {@link FolderTask#slotAtPixel(int, int)}，避免预览与命中判断不一致。
     */
    int hitTest(int px, int py) {
        if (task == null || task.size() == 0) {
            return -1;
        }
        int canvas = task.canvasSize();
        double scale = (double) displaySize / canvas;
        int ox = (getWidth() - displaySize) / 2;
        int oy = (getHeight() - displaySize) / 2;
        int x = px - ox;
        int y = py - oy;
        if (x < 0 || y < 0 || x >= displaySize || y >= displaySize) {
            return -1;
        }
        // 预览坐标 → 画布坐标
        int cx = (int) Math.floor(x / scale);
        int cy = (int) Math.floor(y / scale);
        int slot = task.slotAtPixel(cx, cy);
        if (slot < 0) {
            return -1;
        }
        // 空占位不作为拖拽源
        return task.hasTile(slot) ? slot : -1;
    }

    /** 直接按坐标求槽位（含空位），内部用于绘制高亮。 */
    int slotAt(int px, int py) {
        if (task == null || task.size() == 0) {
            return -1;
        }
        int canvas = task.canvasSize();
        double scale = (double) displaySize / canvas;
        int ox = (getWidth() - displaySize) / 2;
        int oy = (getHeight() - displaySize) / 2;
        int x = px - ox;
        int y = py - oy;
        if (x < 0 || y < 0 || x >= displaySize || y >= displaySize) {
            return -1;
        }
        return task.slotAtPixel((int) Math.floor(x / scale), (int) Math.floor(y / scale));
    }

    /** 当前预览中某个槽位的屏幕矩形。 */
    public Rectangle cellBounds(int slot) {
        if (task == null || slot < 0 || slot >= task.slots().size()) {
            return null;
        }
        int canvas = task.canvasSize();
        double scale = (double) displaySize / canvas;
        int ox = (getWidth() - displaySize) / 2;
        int oy = (getHeight() - displaySize) / 2;
        int cell = (int) Math.round(task.cellSize() * scale);
        int[][] rects = task.cellRects();
        int[] r = rects[slot];
        return new Rectangle(ox + (int) Math.round(r[0] * scale), oy + (int) Math.round(r[1] * scale),
                cell, cell);
    }
}
