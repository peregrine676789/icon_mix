import iconmix.model.FolderTask;
import iconmix.model.IconEntry;
import iconmix.model.Tile;
import iconmix.extract.FrameDecoder;

import java.util.ArrayList;
import java.util.List;

/** 最小隔离测试：验证 7 图标 3×3 补位后，图标与空位交换的行为。 */
public final class SlotProbe {

    public static void main(String[] args) {
        FolderTask t = new FolderTask(new java.io.File("."));
        List<Tile> tiles = new ArrayList<Tile>();
        for (int i = 0; i < 7; i++) {
            tiles.add(new Tile(new IconEntry("src-" + i, "T" + i, "h" + i), FrameDecoder.newArgb(8, 8)));
        }
        t.setTiles(tiles);
        t.setMode(FolderTask.LayoutMode.ROW_FIRST);

        System.out.println("列=" + t.columns() + " 行=" + t.rows()
                + " 槽位数=" + t.slots().size() + " 空位=" + t.emptySlotCount() + " tiles=" + t.size());
        dump(t, "初始");

        String moved = t.tileAtSlot(0).name();
        int last = t.slots().size() - 1;
        System.out.println("把槽 0 的 [" + moved + "] 拖到槽 " + last + "（空位）");
        boolean ok = t.swap(0, last);
        System.out.println("swap 返回 " + ok);
        dump(t, "交换后");

        System.out.println();
        System.out.println("断言：");
        expect("槽 0 变空", t.tileAtSlot(0) == null, String.valueOf(t.tileAtSlot(0)));
        expect("槽 " + last + " 承载 " + moved,
                t.tileAtSlot(last) != null && t.tileAtSlot(last).name().equals(moved),
                String.valueOf(t.tileAtSlot(last)));
        expect("空位总数不变", t.emptySlotCount() == 2, String.valueOf(t.emptySlotCount()));
        expect("图标数仍为 7", t.size() == 7, String.valueOf(t.size()));
        expect("tiles 与槽位顺序一致（导出正确性）", tilesMatchSlots(t), "");
        expect("tiles 末位是 " + moved + "（它现在在最后一个槽位）",
                t.tiles().get(t.size() - 1).name().equals(moved),
                t.tiles().get(t.size() - 1).name());
    }

    /** tiles 必须等于按槽位顺序收集的非空图标。 */
    private static boolean tilesMatchSlots(FolderTask t) {
        java.util.List<Tile> expected = new ArrayList<Tile>();
        for (int i = 0; i < t.slotCount(); i++) {
            Tile tile = t.tileAtSlot(i);
            if (tile != null) {
                expected.add(tile);
            }
        }
        return expected.equals(t.tiles());
    }

    private static void dump(FolderTask t, String label) {
        System.out.println("--- " + label + " ---");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.slots().size(); i++) {
            Tile tile = t.tileAtSlot(i);
            sb.append(i).append(':').append(tile == null ? "空" : tile.name()).append("  ");
        }
        System.out.println("  槽位: " + sb);
        System.out.println("  tiles: " + t.tiles());
    }

    private static void expect(String what, boolean ok, String actual) {
        System.out.println("  " + (ok ? "[OK]  " : "[FAIL]") + " " + what
                + (ok ? "" : "  实际=" + actual));
    }
}
