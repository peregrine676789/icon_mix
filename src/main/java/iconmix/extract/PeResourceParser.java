package iconmix.extract;

import iconmix.util.LittleEndian;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 PE 资源解析器：只做「取出 RT_GROUP_ICON 与 RT_ICON」这一件事。
 *
 * <p>基于 {@link RandomAccessFile} 按需读取，而不是把整个文件读进内存：
 * 实测 VS Code 的 {@code Code.exe} 达 235MB，Electron / Unity / 游戏类程序的 exe
 * 普遍很大，整文件读入既慢又容易撞上内存上限（早期 64MB 上限直接把这类程序整个跳过，
 * 表现为「文件夹里的图标抓不全」）。
 *
 * <p>结构速查（与需求说明书 6.3 一致）：
 * <pre>
 * DOS 头:      e_magic='MZ'(0x5A4D) @0,  e_lfanew @0x3C (LE32)
 * NT 头:       Signature 'PE\0\0' @e_lfanew
 * COFF 头:     @e_lfanew+4, NumberOfSections @+2, SizeOfOptionalHeader @+16, Magic @+20
 * Optional 头: @coff+20
 *    Magic 0x20B = PE32+ → 数据目录起点 coff+132
 *    Magic 0x10B = PE32  → 数据目录起点 coff+116
 *    数据目录索引: 2 = Resource
 * 节表:        @ coff+20+SizeOfOptionalHeader, 每项 40 字节
 *    VirtualSize @+8, VirtualAddress @+12, SizeOfRawData @+16, PointerToRawData @+20
 * 资源三层树:  类型 → 名称/ID → 语言 → 数据项
 *    目录项 8 字节: Name/ID(LE32) + OffsetToData(LE32, 高位=1 表示子目录)
 *    数据项 16 字节: DataRVA(LE32) + Size(LE32) + CodePage + Reserved
 * </pre>
 */
public final class PeResourceParser {

    public static final int RT_ICON = 3;
    public static final int RT_GROUP_ICON = 14;

    private static final int DIR_HEADER_SIZE = 16;
    private static final int DIR_ENTRY_SIZE = 8;
    private static final int DATA_ENTRY_SIZE = 16;
    /** 单次读取的上限，防御异常 size 造成的大块分配。 */
    private static final int MAX_RESOURCE_BYTES = 32 * 1024 * 1024;

    private final Source source;
    private final long fileLength;
    private final List<Section> sections = new ArrayList<Section>();
    private int resourceBase;

    private PeResourceParser(Source source) {
        this.source = source;
        this.fileLength = source.length();
    }

    // ------------------------------------------------------------------ 入口

    /** 从内存字节提取最大图标帧（小文件与测试用）。 */
    public static byte[] extractLargestIcon(byte[] peData) throws IOException {
        return parse(peData).largestIconBytes();
    }

    /**
     * 直接从文件提取最大图标帧。
     *
     * <p>只按需读取 PE 头、节表与资源节，不会把整个文件读进内存——
     * 实测 VS Code 的 {@code Code.exe} 达 235MB，整文件读入会直接失败。
     */
    public static byte[] extractLargestIcon(RandomAccessFile raf) throws IOException {
        return parse(raf).largestIconBytes();
    }

    public static PeResourceParser parse(byte[] data) throws IOException {
        PeResourceParser pe = new PeResourceParser(new ByteSource(data));
        pe.readHeaders();
        return pe;
    }

    public static PeResourceParser parse(RandomAccessFile raf) throws IOException {
        PeResourceParser pe = new PeResourceParser(new FileSource(raf));
        pe.readHeaders();
        return pe;
    }

    // ------------------------------------------------------------------ 头部

    private void readHeaders() throws IOException {
        if (fileLength < 0x40) {
            throw new IOException("文件过小，不是 PE");
        }
        byte[] dos = readAt(0, 0x40);
        if (LittleEndian.getShort(dos, 0) != 0x5A4D) {
            throw new IOException("缺少 MZ 头");
        }
        int lfanew = LittleEndian.getInt(dos, 0x3C);
        if (lfanew <= 0 || lfanew + 24 > fileLength) {
            throw new IOException("e_lfanew 越界: " + lfanew);
        }
        byte[] nt = readAt(lfanew, 24);
        if (LittleEndian.getInt(nt, 0) != 0x00004550) {
            throw new IOException("缺少 PE 签名");
        }
        int coff = lfanew + 4;
        // COFF File Header 共 20 字节；紧跟其后的 Optional Header 头两个字节才是 Magic
        byte[] coffAndMagic = readAt(coff, 22);
        int numberOfSections = LittleEndian.getShort(coffAndMagic, 2);
        int sizeOfOptionalHeader = LittleEndian.getShort(coffAndMagic, 16);
        int magic = LittleEndian.getShort(coffAndMagic, 20);

        int dataDirStart;
        if (magic == 0x20B) {
            dataDirStart = coff + 132;   // PE32+
        } else if (magic == 0x10B) {
            dataDirStart = coff + 116;   // PE32
        } else {
            throw new IOException("未知的可选头 Magic: 0x" + Integer.toHexString(magic));
        }

        long sectionTable = (long) coff + 20 + sizeOfOptionalHeader;
        if (sectionTable + (long) numberOfSections * 40 > fileLength) {
            throw new IOException("节表越界");
        }
        byte[] table = readAt(sectionTable, numberOfSections * 40);
        for (int i = 0; i < numberOfSections; i++) {
            int off = i * 40;
            Section s = new Section();
            s.virtualSize = LittleEndian.getInt(table, off + 8);
            s.virtualAddress = LittleEndian.getInt(table, off + 12);
            s.sizeOfRawData = LittleEndian.getInt(table, off + 16);
            s.pointerToRawData = LittleEndian.getInt(table, off + 20);
            sections.add(s);
        }

        byte[] dataDir = readAt(dataDirStart, 16 * 8);
        int resourceRva = LittleEndian.getInt(dataDir, 2 * 8);
        if (resourceRva == 0) {
            throw new IOException("没有资源目录");
        }
        this.resourceBase = rvaToOffset(resourceRva);
    }

    /** RVA → 文件偏移。 */
    public int rvaToOffset(int rva) throws IOException {
        for (Section s : sections) {
            long size = Math.max(s.virtualSize, s.sizeOfRawData);
            if ((long) rva >= (s.virtualAddress & 0xFFFFFFFFL)
                    && (long) rva < (s.virtualAddress & 0xFFFFFFFFL) + size) {
                long offset = (s.pointerToRawData & 0xFFFFFFFFL) + ((rva & 0xFFFFFFFFL) - (s.virtualAddress & 0xFFFFFFFFL));
                if (offset < 0 || offset > fileLength) {
                    throw new IOException("RVA 映射越界: rva=" + rva);
                }
                return (int) offset;
            }
        }
        if (rva >= 0 && rva < fileLength) {
            return rva;   // 有些文件的资源 RVA 直接就是文件偏移
        }
        throw new IOException("RVA 不属于任何节: 0x" + Integer.toHexString(rva));
    }

    // ------------------------------------------------------------------ 资源树

    private static final class Entry {
        int id;
        boolean isDirectory;
        int offset;
    }

    private List<Entry> readEntries(int dirOffset) throws IOException {
        long base = (long) resourceBase + dirOffset;
        byte[] header = readAt(base, DIR_HEADER_SIZE);
        int namedCount = LittleEndian.getShort(header, 12);
        int idCount = LittleEndian.getShort(header, 14);
        int total = namedCount + idCount;
        if (total <= 0 || total > 8192) {
            return new ArrayList<Entry>();
        }
        byte[] raw = readAt(base + DIR_HEADER_SIZE, total * DIR_ENTRY_SIZE);
        List<Entry> list = new ArrayList<Entry>(total);
        for (int i = 0; i < total; i++) {
            int off = i * DIR_ENTRY_SIZE;
            Entry e = new Entry();
            e.id = LittleEndian.getInt(raw, off);
            int offsetToData = LittleEndian.getInt(raw, off + 4);
            e.isDirectory = (offsetToData & 0x80000000) != 0;
            e.offset = offsetToData & 0x7FFFFFFF;
            list.add(e);
        }
        return list;
    }

    private byte[] readDataEntry(int dirOffset) throws IOException {
        byte[] header = readAt((long) resourceBase + dirOffset, DATA_ENTRY_SIZE);
        int dataRva = LittleEndian.getInt(header, 0);
        int size = LittleEndian.getInt(header, 4);
        if (size < 0) {
            throw new IOException("资源大小非法: " + size);
        }
        if (size > MAX_RESOURCE_BYTES) {
            throw new IOException("资源过大: " + size);
        }
        int off = rvaToOffset(dataRva);
        if ((long) off + size > fileLength) {
            throw new IOException("资源数据越界: off=" + off + " size=" + size);
        }
        return readAt(off, size);
    }

    /** 取某个资源类型下所有数据项：name/id → 首个语言的数据。 */
    private Map<Integer, byte[]> collectType(int type) {
        Map<Integer, byte[]> result = new LinkedHashMap<Integer, byte[]>();
        try {
            for (Entry typeEntry : readEntries(0)) {
                if (typeEntry.id != type || !typeEntry.isDirectory) {
                    continue;
                }
                for (Entry nameEntry : readEntries(typeEntry.offset)) {
                    if (!nameEntry.isDirectory) {
                        continue;
                    }
                    for (Entry langEntry : readEntries(nameEntry.offset)) {
                        if (langEntry.isDirectory) {
                            continue;
                        }
                        try {
                            result.put(nameEntry.id, readDataEntry(langEntry.offset));
                        } catch (IOException ignored) {
                            // 该语言数据损坏，尝试下一个
                        }
                        break;
                    }
                }
            }
        } catch (IOException e) {
            // 整个类型读取失败时返回已完成的部分
        }
        return result;
    }

    // ------------------------------------------------------------------ 图标

    public static final class GroupEntry {
        public int width;
        public int height;
        public int bitCount;
        public int bytesInRes;
        public int id;

        /** 0 表示 256（格式约定）。 */
        public int displayWidth() {
            return width == 0 ? 256 : width;
        }

        public int displayHeight() {
            return height == 0 ? 256 : height;
        }
    }

    public static final class IconGroup {
        public final List<GroupEntry> entries = new ArrayList<GroupEntry>();
    }

    public static final class IconGroupHeader {
        public final int groupId;
        public final IconGroup group;

        IconGroupHeader(int groupId, IconGroup group) {
            this.groupId = groupId;
            this.group = group;
        }
    }

    /** 解析所有 RT_GROUP_ICON。 */
    public List<IconGroupHeader> readIconGroups() {
        List<IconGroupHeader> out = new ArrayList<IconGroupHeader>();
        for (Map.Entry<Integer, byte[]> e : collectType(RT_GROUP_ICON).entrySet()) {
            try {
                out.add(new IconGroupHeader(e.getKey(), parseGroup(e.getValue())));
            } catch (IOException ignored) {
                // 单个组损坏，跳过
            }
        }
        return out;
    }

    private static IconGroup parseGroup(byte[] grp) throws IOException {
        LittleEndian.Reader r = new LittleEndian.Reader(grp);
        r.u16();                       // Reserved
        int type = r.u16();            // 1 = icon
        if (type != 1) {
            throw new IOException("GRPICONDIR type != 1");
        }
        int count = r.u16();
        if (count <= 0 || count > 256) {
            throw new IOException("GRPICONDIR 帧数异常: " + count);
        }
        IconGroup group = new IconGroup();
        for (int i = 0; i < count; i++) {
            GroupEntry e = new GroupEntry();
            e.width = r.u8();
            e.height = r.u8();
            r.u8();                    // bColorCount
            r.u8();                    // bReserved
            r.u16();                   // wPlanes
            e.bitCount = r.u16();
            e.bytesInRes = r.u32();
            e.id = r.u16();
            group.entries.add(e);
        }
        return group;
    }

    /** 取某个 RT_ICON 的原始数据（DIB 或内嵌 PNG）；找不到返回 null。 */
    public byte[] readIconData(int id) {
        return collectType(RT_ICON).get(id);
    }

    /** 取该 PE 中尺寸最大的图标帧原始数据。 */
    private byte[] largestIconBytes() throws IOException {
        List<IconGroupHeader> groups = readIconGroups();
        if (groups.isEmpty()) {
            throw new IOException("PE 中没有 RT_GROUP_ICON 资源");
        }
        Map<Integer, byte[]> iconCache = collectType(RT_ICON);
        byte[] best = null;
        long bestScore = -1;
        for (IconGroupHeader h : groups) {
            for (GroupEntry e : h.group.entries) {
                long score = (long) e.displayWidth() * e.displayHeight() * 1000L + e.bitCount;
                if (score > bestScore) {
                    byte[] raw = iconCache.get(e.id);
                    if (raw != null) {
                        bestScore = score;
                        best = raw;
                    }
                }
            }
        }
        if (best == null) {
            throw new IOException("RT_GROUP_ICON 引用的 RT_ICON 均缺失");
        }
        return best;
    }

    /** 供调试打印。 */
    public static String describe(byte[] data) {
        try {
            PeResourceParser pe = parse(data);
            List<IconGroupHeader> groups = pe.readIconGroups();
            StringBuilder sb = new StringBuilder();
            sb.append("sections=").append(pe.sections.size());
            sb.append(" groups=").append(groups.size());
            for (IconGroupHeader h : groups) {
                sb.append(" [id=").append(h.groupId).append(" frames=").append(h.group.entries.size()).append("]");
            }
            return sb.toString();
        } catch (IOException e) {
            return "解析失败: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ 读取

    /** 定长读取，越界即抛异常。 */
    private byte[] readAt(long offset, int length) throws IOException {
        if (length < 0 || offset < 0 || offset + length > fileLength) {
            throw new IOException("越界读取: offset=" + offset + " len=" + length + " size=" + fileLength);
        }
        byte[] buf = new byte[length];
        source.read(offset, buf);
        return buf;
    }

    /** 数据来源抽象：内存字节或磁盘文件，解析逻辑只写一份。 */
    private interface Source extends java.io.Closeable {
        long length();

        void read(long offset, byte[] buf) throws IOException;
    }

    private static final class ByteSource implements Source {
        private final byte[] data;

        ByteSource(byte[] data) {
            this.data = data;
        }

        @Override
        public long length() {
            return data.length;
        }

        @Override
        public void read(long offset, byte[] buf) throws IOException {
            if (offset < 0 || offset + buf.length > data.length) {
                throw new IOException("越界读取: offset=" + offset + " len=" + buf.length
                        + " size=" + data.length);
            }
            System.arraycopy(data, (int) offset, buf, 0, buf.length);
        }

        @Override
        public void close() {
            // 内存实现无需关闭
        }
    }

    private static final class FileSource implements Source {
        private final RandomAccessFile raf;

        FileSource(RandomAccessFile raf) {
            this.raf = raf;
        }

        @Override
        public long length() {
            try {
                return raf.length();
            } catch (IOException e) {
                return 0;
            }
        }

        @Override
        public void read(long offset, byte[] buf) throws IOException {
            if (offset < 0 || offset + buf.length > raf.length()) {
                throw new IOException("越界读取: offset=" + offset + " len=" + buf.length
                        + " size=" + raf.length());
            }
            raf.seek(offset);
            raf.readFully(buf);
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }

    private static final class Section {
        int virtualSize;
        int virtualAddress;
        int sizeOfRawData;
        int pointerToRawData;
    }
}
