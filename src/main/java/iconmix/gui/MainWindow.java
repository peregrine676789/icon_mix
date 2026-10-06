package iconmix.gui;

import iconmix.core.Exporter;
import iconmix.core.IcoWriter;
import iconmix.core.Renderer;
import iconmix.core.Scanner;
import iconmix.core.ShellApplier;
import iconmix.model.FolderTask;
import iconmix.model.Tile;
import iconmix.util.Log;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 主窗口（FR-4.12）。
 *
 * <p>布局：上方拖拽区 → 左侧控制区 + 中间预览区 → 下方日志区。
 */
public final class MainWindow extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final int MAX_ICONS = 36;

    private final transient Log log = new Log();
    private transient FolderTask task;
    private transient File lastFolder;

    private final PreviewPanel preview = new PreviewPanel();
    private final JTextArea logArea = new JTextArea(9, 80);
    private final JLabel statusLabel = new JLabel("把文件夹拖进窗口，或点「选择文件夹」");
    private final JLabel summaryLabel = new JLabel(" ");
    private final JPanel dropZone = new JPanel(new BorderLayout());
    private final JProgressBar progress = new JProgressBar();
    private final JRadioButton rowFirst = new JRadioButton("横优先", true);
    private final JRadioButton columnFirst = new JRadioButton("列优先");
    private final JCheckBox keepHistory = new JCheckBox("保留历史产物（不清理旧图）");
    private final JButton applyButton = new JButton("应用到文件夹");
    private final JButton exportButton = new JButton("仅导出图片");
    private final JButton saveAsButton = new JButton("另存为…");
    private final JButton revertButton = new JButton("还原文件夹图标");
    private final JButton restoreOrderButton = new JButton("恢复默认顺序");
    private final JComboBox<String> sizeCombo =
            new JComboBox<String>(new String[]{"预览 256", "预览 384", "预览 512", "预览 768"});
    private final JButton chooseButton = new JButton("选择文件夹");

    public MainWindow() {
        super("IconMix —— 文件夹图标合成工具");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(980, 720));
        setLocationByPlatform(true);

        buildUi();
        wireEvents();
        log.addListener(new Log.Listener() {
            @Override
            public void onLog(final String line) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        logArea.append(line + "\n");
                        logArea.setCaretPosition(logArea.getDocument().getLength());
                    }
                });
            }
        });
        log.info("IconMix 已就绪。把文件夹拖进上方区域即可开始。");
    }

    private void buildUi() {
        // ---- 顶部拖拽区 ----
        dropZone.setBorder(BorderFactory.createDashedBorder(new Color(0x9E, 0x9E, 0x9E), 2f, 6f, 4f, true));
        dropZone.setBackground(new Color(0xF7, 0xFB, 0xFF));
        dropZone.setPreferredSize(new Dimension(100, 96));
        JLabel hint = new JLabel("把文件夹拖到这里（支持中文与空格路径）", JLabel.CENTER);
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 15f));
        dropZone.add(hint, BorderLayout.CENTER);

        JPanel topRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        topRight.add(chooseButton);
        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        top.add(dropZone, BorderLayout.CENTER);
        top.add(topRight, BorderLayout.EAST);

        // ---- 左侧控制区 ----
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 6));
        controls.setPreferredSize(new Dimension(300, 400));
        controls.setMinimumSize(new Dimension(300, 200));

        controls.add(sectionLabel("排布偏好"));
        ButtonGroup group = new ButtonGroup();
        group.add(rowFirst);
        group.add(columnFirst);
        controls.add(rowFirst);
        controls.add(columnFirst);
        controls.add(Box.createVerticalStrut(6));
        controls.add(restoreOrderButton);
        controls.add(Box.createVerticalStrut(12));

        controls.add(sectionLabel("预览尺寸"));
        sizeCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        controls.add(sizeCombo);
        controls.add(Box.createVerticalStrut(12));

        controls.add(sectionLabel("结果落地"));
        applyButton.setToolTipText("一键把图标应用到文件夹：写 desktop.ini 并把文件夹设为只读（可随时还原）");
        applyButton.setActionCommand("一键应用到文件夹");
        exportButton.setToolTipText("只在 app 可执行文件所在目录生成结果图片，不改动文件夹配置");
        saveAsButton.setToolTipText("自选位置与文件名保存结果图片");
        controls.add(fullWidth(applyButton));
        controls.add(Box.createVerticalStrut(4));
        controls.add(fullWidth(exportButton));
        controls.add(Box.createVerticalStrut(4));
        controls.add(fullWidth(saveAsButton));
        controls.add(Box.createVerticalStrut(6));
        JLabel landing = new JLabel("<html>应用到文件夹 = 改文件夹图标；<br>仅导出 = 只出图片不改配置</html>");
        landing.setFont(landing.getFont().deriveFont(Font.PLAIN, 11.5f));
        landing.setForeground(new Color(0x60, 0x60, 0x60));
        landing.setAlignmentX(LEFT_ALIGNMENT);
        controls.add(landing);
        controls.add(Box.createVerticalStrut(8));
        keepHistory.setToolTipText("勾选后导出不再清理同一目录名下的旧结果图");
        keepHistory.setFont(keepHistory.getFont().deriveFont(Font.PLAIN, 12f));
        controls.add(keepHistory);
        controls.add(Box.createVerticalStrut(12));

        controls.add(sectionLabel("其他"));
        controls.add(fullWidth(revertButton));
        controls.add(Box.createVerticalGlue());

        setActionsEnabled(false);

        // ---- 中间预览区 ----
        JPanel previewHolder = new JPanel(new BorderLayout());
        previewHolder.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 10));
        previewHolder.add(new JScrollPane(preview), BorderLayout.CENTER);

        JPanel middle = new JPanel(new BorderLayout(6, 6));
        middle.add(controls, BorderLayout.WEST);
        middle.add(previewHolder, BorderLayout.CENTER);

        // ---- 底部日志 ----
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setRows(10);
        logArea.setMargin(new java.awt.Insets(4, 6, 4, 6));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("执行日志"));
        progress.setStringPainted(true);
        progress.setVisible(false);
        JPanel bottom = new JPanel(new BorderLayout(6, 4));
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
        bottom.add(logScroll, BorderLayout.CENTER);
        bottom.add(progress, BorderLayout.SOUTH);

        JPanel statusBar = new JPanel(new BorderLayout(8, 0));
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(summaryLabel, BorderLayout.EAST);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, middle, bottom);
        split.setResizeWeight(0.74);
        split.setBorder(null);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        pack();
        setSize(1040, 780);

        // 拖拽支持（窗口整体接收）
        TransferHandler handler = new DropHandler();
        dropZone.setTransferHandler(handler);
        setTransferHandler(handler);
        preview.setSwapListener(new PreviewPanel.SwapListener() {
            @Override
            public void onSwap(int fromSlot, int toSlot) {
                if (task == null) {
                    return;
                }
                String fromName = task.tileAtSlot(fromSlot) == null
                        ? "空位" : task.tileAtSlot(fromSlot).name();
                String toName = task.tileAtSlot(toSlot) == null
                        ? "空位" : task.tileAtSlot(toSlot).name();
                if (task.swap(fromSlot, toSlot)) {
                    log.info("交换位置：" + fromName + " ↔ " + toName);
                    refreshPreview();
                }
            }
        });
    }

    private JComponent sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12.5f));
        label.setAlignmentX(LEFT_ALIGNMENT);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setAlignmentX(LEFT_ALIGNMENT);
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        wrap.add(label, BorderLayout.WEST);
        return wrap;
    }

    private JComponent fullWidth(JButton button) {
        button.setAlignmentX(LEFT_ALIGNMENT);
        // 关键：Windows 外观下按钮左右内边距很大，不设 preferred 会被截断成「一键应用到文」
        button.setPreferredSize(new Dimension(180, 34));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        button.setMinimumSize(new Dimension(140, 34));
        return button;
    }

    private void wireEvents() {
        chooseButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                chooseFolder();
            }
        });
        rowFirst.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (task != null) {
                    task.setMode(FolderTask.LayoutMode.ROW_FIRST);
                    log.info("排布偏好改为横优先 → " + task.gridSummary());
                    refreshPreview();
                }
            }
        });
        columnFirst.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (task != null) {
                    task.setMode(FolderTask.LayoutMode.COLUMN_FIRST);
                    log.info("排布偏好改为列优先 → " + task.gridSummary());
                    refreshPreview();
                }
            }
        });
        restoreOrderButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (task != null) {
                    task.restoreDefaultOrder();
                    log.info("已恢复默认顺序（" + task.gridSummary() + "）");
                    refreshPreview();
                }
            }
        });
        sizeCombo.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String s = (String) sizeCombo.getSelectedItem();
                int size = 512;
                try {
                    size = Integer.parseInt(s.replaceAll("[^0-9]", ""));
                } catch (NumberFormatException ignored) {
                    // 保持默认
                }
                preview.setDisplaySize(size);
            }
        });
        applyButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                doApply();
            }
        });
        exportButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                doExportDefault();
            }
        });
        saveAsButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                doSaveAs();
            }
        });
        revertButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                doRevert();
            }
        });
    }

    private void setActionsEnabled(boolean enabled) {
        applyButton.setEnabled(enabled);
        exportButton.setEnabled(enabled);
        saveAsButton.setEnabled(enabled);
        restoreOrderButton.setEnabled(enabled);
    }

    // ------------------------------------------------------------------ 扫描

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser(lastFolder != null ? lastFolder
                : new File(System.getProperty("user.home")));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择要合成图标的文件夹");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadFolder(chooser.getSelectedFile());
        }
    }

    private void loadFolder(final File folder) {
        if (folder == null || !folder.isDirectory()) {
            JOptionPane.showMessageDialog(this, "请选择文件夹", "IconMix", JOptionPane.WARNING_MESSAGE);
            return;
        }
        lastFolder = folder;
        setActionsEnabled(false);
        statusLabel.setText("正在扫描：" + folder.getAbsolutePath());
        progress.setVisible(true);
        progress.setIndeterminate(true);
        log.clear();
        logArea.setText("");
        log.info("开始处理目录：" + folder.getAbsolutePath());

        new SwingWorker<FolderTask, String>() {
            @Override
            protected FolderTask doInBackground() throws Exception {
                List<File> files = Scanner.scan(folder, 4, log);
                if (files.isEmpty()) {
                    throw new IOException("该目录里没有可提取图标的程序、快捷方式或图片");
                }
                List<Tile> tiles = Scanner.extract(files, MAX_ICONS, log);
                if (tiles.isEmpty()) {
                    throw new IOException("未能从任何文件提取到图标（详见日志中的跳过原因）");
                }
                FolderTask t = new FolderTask(folder);
                for (File f : files) {
                    t.addCandidate(f);
                }
                t.setTiles(tiles);
                return t;
            }

            @Override
            protected void done() {
                progress.setVisible(false);
                progress.setIndeterminate(false);
                try {
                    FolderTask t = get();
                    task = t;
                    if (columnFirst.isSelected()) {
                        t.setMode(FolderTask.LayoutMode.COLUMN_FIRST);
                    } else {
                        t.setMode(FolderTask.LayoutMode.ROW_FIRST);
                    }
                    preview.setTask(t);
                    setActionsEnabled(true);
                    statusLabel.setText("已就绪：" + t.folderName() + "（可拖拽图标交换位置）");
                    summaryLabel.setText(t.gridSummary());
                    log.info("提取到 " + t.size() + " 个图标，" + t.gridSummary());
                    if (t.size() == 2) {
                        log.info("只有 2 个图标，将按你选择的排布偏好排列（横优先 = 2 列 1 行，列优先 = 1 列 2 行）");
                    }
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    statusLabel.setText("处理失败：" + cause.getMessage());
                    log.error("处理失败：" + cause.getMessage());
                    JOptionPane.showMessageDialog(MainWindow.this,
                            "处理失败：\n" + cause.getMessage(), "IconMix", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void refreshPreview() {
        preview.repaint();
        if (task != null) {
            summaryLabel.setText(task.gridSummary());
        }
    }

    // ------------------------------------------------------------------ 落地

    private void doApply() {
        if (task == null) {
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "一键应用到文件夹，将执行以下操作：\n\n"
                        + "1. 在文件夹内写入 " + Exporter.sanitize(task.folderName()) + "_icon.ico\n"
                        + "2. 写入 desktop.ini（UTF-16LE）指向该图标\n"
                        + "3. 把文件夹设为只读属性（desktop.ini 生效的必要条件）\n"
                        + "4. 通知资源管理器刷新图标\n\n"
                        + "目标：" + task.folder().getAbsolutePath() + "\n\n"
                        + "该操作可随时用「还原文件夹图标」撤销。是否继续？",
                "一键应用到文件夹", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            log.info("用户取消了「应用到文件夹」");
            return;
        }
        runBackground(new Task() {
            @Override
            public String run() throws Exception {
                final FolderTask t = task;
                byte[] ico = IcoWriter.write(null, IcoWriter.DEFAULT_SIZES, new IcoWriter.SizeRenderer() {
                    @Override
                    public BufferedImage render(int size) {
                        return Renderer.renderAt(t, t.cellSize(), size);
                    }
                });
                String icoName = Exporter.sanitize(t.folderName()) + "_icon.ico";
                ShellApplier.ApplyResult r = ShellApplier.apply(t.folder(), ico, icoName, true, log);
                // 应用成功后仍导出一份预览图到 app 目录，方便留档
                BufferedImage canvas = Renderer.render(t, t.cellSize());
                File dir = defaultExportDir();
                Exporter.ExportResult er = Exporter.exportPng(t, canvas, null, dir,
                        !keepHistory.isSelected(), null, null);
                return r.describe() + "\n" + er.describe();
            }
        }, "应用完成");
    }

    private void doExportDefault() {
        if (task == null) {
            return;
        }
        final File dir = defaultExportDir();
        if (!keepHistory.isSelected()) {
            List<String> old = Exporter.previewCleanup(dir, task.folderName(), true);
            if (!old.isEmpty()) {
                StringBuilder sb = new StringBuilder("导出前将清理以下旧产物（保持只保留最新一张）：\n\n");
                for (String s : old) {
                    sb.append("• ").append(s).append("\n");
                }
                sb.append("\n是否继续？");
                int answer = JOptionPane.showConfirmDialog(this, sb.toString(),
                        "确认清理旧产物", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.OK_OPTION) {
                    log.info("用户取消了导出（未清理任何文件）");
                    return;
                }
            }
        }
        runBackground(new Task() {
            @Override
            public String run() throws Exception {
                final FolderTask t = task;
                BufferedImage canvas = Renderer.render(t, t.cellSize());
                byte[] ico = IcoWriter.write(null, IcoWriter.DEFAULT_SIZES, new IcoWriter.SizeRenderer() {
                    @Override
                    public BufferedImage render(int size) {
                        return Renderer.renderAt(t, t.cellSize(), size);
                    }
                });
                Exporter.ExportResult er = Exporter.exportPng(t, canvas, ico, dir,
                        !keepHistory.isSelected(), null, null);
                return er.describe();
            }
        }, "导出完成");
    }

    private void doSaveAs() {
        if (task == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser(defaultExportDir());
        chooser.setDialogTitle("保存结果图片");
        chooser.setSelectedFile(new File(Exporter.sanitize(task.folderName())
                + "_icon(" + Exporter.today() + ").png"));
        chooser.setFileFilter(new FileNameExtensionFilter("PNG 图片", "png"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        final File target = chooser.getSelectedFile();
        if (target.exists()) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "文件已存在，是否覆盖？\n" + target.getAbsolutePath(),
                    "确认覆盖", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }
        }
        runBackground(new Task() {
            @Override
            public String run() throws Exception {
                BufferedImage canvas = Renderer.render(task, task.cellSize());
                File written = Exporter.exportPngTo(task, canvas, target);
                return "已保存：" + written.getAbsolutePath();
            }
        }, "保存完成");
    }

    private void doRevert() {
        File folder = task != null ? task.folder() : lastFolder;
        if (folder == null) {
            JOptionPane.showMessageDialog(this, "请先选择一个文件夹", "IconMix", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "将删除该文件夹中由本工具写入的 desktop.ini 与 ico，并清除只读属性。\n"
                        + "（含 DSH_CompositeFolderIcon 标记时才会删除，他人配置不受影响）\n\n"
                        + folder.getAbsolutePath() + "\n\n是否继续？",
                "还原文件夹图标", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        final File target = folder;
        runBackground(new Task() {
            @Override
            public String run() throws Exception {
                boolean removed = ShellApplier.revert(target, true, log);
                return removed ? "已还原：" + target.getAbsolutePath()
                        : "未发现本工具写入的 desktop.ini（可能本来就未应用过）";
            }
        }, "还原完成");
    }

    /** 解析界面上的导出路径选择。 */
    private File defaultExportDir() {
        File dir = null;
        if (task != null) {
            dir = Scanner.primaryExeDir(task.candidates());
        }
        if (dir == null && task != null) {
            dir = task.folder().getParentFile();
        }
        if (dir == null) {
            dir = lastFolder != null ? lastFolder : new File(System.getProperty("user.home"));
        }
        return dir;
    }

    // ------------------------------------------------------------------ 任务封装

    private interface Task {
        String run() throws Exception;
    }

    private void runBackground(final Task body, final String okTitle) {
        progress.setVisible(true);
        progress.setIndeterminate(true);
        setActionsEnabled(false);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return body.run();
            }

            @Override
            protected void done() {
                progress.setVisible(false);
                progress.setIndeterminate(false);
                setActionsEnabled(task != null);
                try {
                    String message = get();
                    log.info(message);
                    statusLabel.setText(okTitle);
                    JOptionPane.showMessageDialog(MainWindow.this, message, okTitle,
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    log.error(okTitle.replace("完成", "失败") + "：" + cause.getMessage());
                    statusLabel.setText("失败：" + cause.getMessage());
                    JOptionPane.showMessageDialog(MainWindow.this,
                            "操作失败：\n" + cause.getMessage()
                                    + "\n\n若因权限不足，可改用「仅导出结果图片」。",
                            "IconMix", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /** 接收资源管理器拖来的文件夹。 */
    @SuppressWarnings("serial")
    private final class DropHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            try {
                List<File> files = (List<File>) support.getTransferable()
                        .getTransferData(DataFlavor.javaFileListFlavor);
                if (files == null || files.isEmpty()) {
                    return false;
                }
                List<File> dirs = new ArrayList<File>();
                for (File f : files) {
                    if (f.isDirectory()) {
                        dirs.add(f);
                    }
                }
                if (dirs.isEmpty()) {
                    // 拖进来的是文件时，取其所在目录
                    File parent = files.get(0).getParentFile();
                    if (parent != null) {
                        dirs.add(parent);
                    }
                }
                if (dirs.isEmpty()) {
                    return false;
                }
                if (dirs.size() > 1) {
                    log.info("一次拖入多个文件夹，先处理第一个：" + dirs.get(0).getName());
                }
                loadFolder(dirs.get(0));
                return true;
            } catch (Exception e) {
                log.error("拖入处理失败：" + e.getMessage());
                return false;
            }
        }
    }

    /** 供测试与外部调用：以编程方式加载目录。 */
    public void loadFolderForTest(File folder) {
        loadFolder(folder);
    }
}
