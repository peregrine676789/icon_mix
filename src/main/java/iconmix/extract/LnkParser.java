package iconmix.extract;

import iconmix.util.LittleEndian;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * .lnk（Shell Link）解析。
 *
 * <h3>结构（[MS-SHLLINK]）</h3>
 * <pre>
 * ShellLinkHeader (76 字节)
 *   0  HeaderSize(4)=0x4C   4  LinkCLSID(16)      20 LinkFlags(4)
 *   24 FileAttributes(4)    28 CreationTime(8)    36 AccessTime(8)
 *   44 WriteTime(8)         52 FileSize(4)        56 IconIndex(4)
 *   60 ShowCommand(4)       64 HotKey(2)          66 Reserved(2)
 * LinkTargetIDList : IDListSize(4) + ItemIDList(IDListSize)   （LinkFlags.bit0）
 * LinkInfo         : LinkInfoSize(4) + ...                    （LinkFlags.bit1）
 * StringData       : NameString / RelativePath / WorkingDir / Arguments / IconLocation
 * ExtraData
 * </pre>
 *
 * <h3>实现中踩到并已规避的坑</h3>
 * <ol>
 *   <li>早期版本在偏移 60 读 IDListSize（应为 76），后续字段全部错位。</li>
 *   <li>实测 {@code chrome.lnk} / {@code kimi.lnk} 的 IDList 区带 <b>零填充</b>，
 *       偏移 76 处的 IDListSize 会读成 0 或离谱的大值；此时<b>不能中断解析</b>，
 *       否则连 {@code IconLocation} 都拿不到（而这些快捷方式的目标其实完全有效）。</li>
 *   <li>{@code IconLocation} 才是取图标最可靠的来源：目标失效时它往往仍指向有效文件。</li>
 *   <li>{@code LocalBasePath} 常常只是目录，必须拼上 {@code CommonPathSuffix}。</li>
 * </ol>
 *
 * <p>因此本解析器对「LinkInfo 起点」做<b>候选校验</b>：把严格偏移与自描述遍历得到的
 * 位置都试一遍，最后用「候选文件是否真实存在」挑最可信的一组。
 */
public final class LnkParser {

    private static final byte[] CLSID = {
            0x01, 0x14, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00,
            (byte) 0xC0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46
    };

    private static final int HEADER_SIZE = 76;

    /** 形如 C:\... 或 \\server\share\... 的路径。 */
    private static final Pattern PATH_LIKE = Pattern.compile(
            "(?:[A-Za-z]:\\\\|\\\\\\\\)[^\\x00-\\x1f\"<>|*?]{1,400}");

    private LnkParser() {
    }

    /** 解析结果。 */
    public static final class Link {
        /** IconLocation 原始值（可能带 ,序号 后缀）。 */
        public String iconLocation;
        /** 目标本体（IDList 推断，或 LocalBasePath）。 */
        public String target;
        /** 目标推断之前、IDList 直接给出的路径。 */
        public String idListTarget;
        /** LinkInfo 的 LocalBasePath + CommonPathSuffix。 */
        public String localBasePath;
        public String relativePath;
        public String workingDir;
        public int iconIndex = -1;
        public String error;
        /** 全文件扫描得到的路径候选（结构字段失准时兜底）。 */
        public List<String> scannedPaths = new ArrayList<String>();

        /**
         * 按优先级给出候选图标来源（FR-4.3.3.1）。
         *
         * <p>顺序：结构解析出的字段 → 全文件扫描的路径。
         * 调用方逐个尝试「文件存在且能取出图标」，任一成功即用。
         *
         * <p>之所以必须带扫描兜底：实测 {@code Krita.lnk} 等新版快捷方式的
         * IDList/LinkInfo 布局与 [MS-SHLLINK] 存在偏差，硬解字段会拼出
         * {@code C:\Program Files\Krita (x64).C:\Program Files\Krita (x64)\shellex\krita.ico}
         * 这类畸形串；而文件里其实躺着完整正确的路径。
         */
        public List<String> iconCandidates() {
            Set<String> out = new LinkedHashSet<String>();
            addAll(out, iconLocation);
            add(out, target);
            add(out, idListTarget);
            add(out, localBasePath);
            add(out, relativePath);
            add(out, workingDir);
            // 结构字段给不出可用文件时，扫描结果顶上
            for (String p : scannedPaths) {
                add(out, p);
            }
            return new ArrayList<String>(out);
        }

        private void add(Set<String> out, String p) {
            for (String c : expand(p)) {
                // 先把「A.B\c.exe」这类粘连串拆开——IDList 与 LinkInfo 的内容被零填充
                // 粘在一起时就会出现这种串（实测 完美世界竞技平台.lnk）
                for (String piece : splitConcatenated(c)) {
                    boolean drive = piece.length() > 3 && Character.isLetter(piece.charAt(0))
                            && piece.charAt(1) == ':';
                    boolean unc = piece.startsWith("\\\\") && piece.length() > 4;
                    if (drive || unc) {
                        out.add(piece);
                    }
                }
            }
        }

        private void addAll(Set<String> out, String p) {
            add(out, p);
        }

        @Override
        public String toString() {
            return "target=" + target + ", idList=" + idListTarget + ", icon=" + iconLocation
                    + ", localBase=" + localBasePath + ", relative=" + relativePath
                    + ", workingDir=" + workingDir + (error == null ? "" : ", err=" + error);
        }
    }

    /** 兼容旧调用：只取目标路径。 */
    public static String resolveTarget(byte[] data) {
        Link link = parse(data);
        if (link.target != null) {
            return link.target;
        }
        if (link.idListTarget != null) {
            return link.idListTarget;
        }
        if (link.localBasePath != null) {
            return link.localBasePath;
        }
        if (link.relativePath != null) {
            return link.relativePath;
        }
        List<String> fromIcon = expand(link.iconLocation);
        return fromIcon.isEmpty() ? null : fromIcon.get(0);
    }

    /** 完整解析。 */
    public static Link parse(byte[] data) {
        Link link = new Link();
        try {
            parseStrict(data, link);
        } catch (Exception e) {
            link.error = e.getMessage();
        }
        // 全文件路径扫描：既作为字段完全失败时的兜底，也作为「字段拼出畸形串」时的候选补充
        collectScannedPaths(data, link);
        if (link.iconLocation == null && link.idListTarget == null && link.localBasePath == null
                && link.relativePath == null && link.scannedPaths.isEmpty()) {
            heuristic(data, link);
        }
        return link;
    }

    /** 扫描全文件，收集所有像路径的字符串（含「粘连串」拆分）。 */
    private static void collectScannedPaths(byte[] data, Link link) {
        List<String> raw = pathsInRegion(data, 0, data.length);
        Set<String> unique = new LinkedHashSet<String>();
        for (String p : raw) {
            String cleaned = p.trim();
            while (cleaned.length() > 3 && cleaned.endsWith("\\")) {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }
            if (cleaned.length() <= 3) {
                continue;
            }
            String lower = cleaned.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".lnk")) {
                continue;
            }
            // 只保留「看起来是文件」的（带扩展名）或真实存在的目录
            if (hasKnownExtension(lower) || new File(cleaned).exists()) {
                unique.add(cleaned);
            }
        }
        // 存在的文件优先，其次路径更长的（更完整）
        List<String> sorted = new ArrayList<String>(unique);
        Collections.sort(sorted, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                boolean ea = new File(a).isFile();
                boolean eb = new File(b).isFile();
                if (ea != eb) {
                    return ea ? -1 : 1;
                }
                return b.length() - a.length();
            }
        });
        link.scannedPaths = sorted;
    }

    private static boolean hasKnownExtension(String lower) {
        return lower.endsWith(".exe") || lower.endsWith(".dll") || lower.endsWith(".ico")
                || lower.endsWith(".com") || lower.endsWith(".bat") || lower.endsWith(".cmd")
                || lower.endsWith(".msc") || lower.endsWith(".cpl");
    }

    private static void parseStrict(byte[] data, Link link) throws IOException {
        if (data.length < HEADER_SIZE) {
            throw new IOException("lnk 过小（" + data.length + " 字节）");
        }
        int headerSize = LittleEndian.getInt(data, 0);
        if (headerSize != 0x4C) {
            throw new IOException("HeaderSize 非 0x4C: " + headerSize);
        }
        for (int i = 0; i < 16; i++) {
            if (data[4 + i] != CLSID[i]) {
                throw new IOException("CLSID 不匹配");
            }
        }
        int linkFlags = LittleEndian.getInt(data, 20);
        link.iconIndex = LittleEndian.getInt(data, 56);

        boolean hasIdList = (linkFlags & 0x1) != 0;
        boolean hasLinkInfo = (linkFlags & 0x2) != 0;

        // ---- IDList ----
        int afterIdList = HEADER_SIZE;
        if (hasIdList) {
            int declared = LittleEndian.getInt(data, HEADER_SIZE);
            int start = HEADER_SIZE + 4;
            int end = start + declared;
            boolean trustworthy = declared >= 4 && declared % 2 == 0 && end <= data.length;
            if (trustworthy) {
                afterIdList = end;
                link.idListTarget = lastPathInIdList(data, start, end);
            } else {
                // IDListSize 不可信（零填充 / 越界）：只从中提取路径，位置交给候选校验
                int scanEnd = Math.min(data.length, start + 1500);
                link.idListTarget = lastPathInIdList(data, start, scanEnd);
            }
        }

        // ---- 结构链：收集候选起点 ----
        List<Integer> starts = new ArrayList<Integer>();
        if (hasLinkInfo || !hasIdList) {
            starts.add(afterIdList);
        }
        if (hasLinkInfo) {
            int scan = scanForLinkInfo(data, hasIdList ? HEADER_SIZE + 4 : HEADER_SIZE);
            if (scan >= 0 && !starts.contains(scan)) {
                starts.add(scan);
            }
            // 兜底：从头部之后逐 2 字节找第一个能形成合法链的位置
            if (scan < 0) {
                for (int p = HEADER_SIZE; p + 28 <= data.length; p += 2) {
                    if (chainValid(data, p, linkFlags)) {
                        starts.add(p);
                        break;
                    }
                }
            }
        }

        List<K> candidates = new ArrayList<K>();
        for (int i = 0; i < starts.size(); i++) {
            parseChain(data, starts.get(i), linkFlags, candidates, i == 0);
        }
        choosePreferred(link, candidates);

        // ---- 目标推断 ----
        if (link.target == null) {
            link.target = link.localBasePath != null ? link.localBasePath : link.idListTarget;
        }
        link.target = completeTarget(link);
    }

    // ------------------------------------------------------------------ 候选链

    /** 一组候选字段。 */
    private static final class K {
        String iconLocation;
        String relativePath;
        String workingDir;
        String localBasePath;
        boolean strict;
    }

    /** 从给定位置按 LinkFlags 走一遍 LinkInfo + StringData。 */
    private static void parseChain(byte[] data, int pos, int linkFlags, List<K> out, boolean strict) {
        try {
            K k = new K();
            k.strict = strict;
            int p = pos;
            if ((linkFlags & 0x2) != 0) {
                int consumed = parseLinkInfoAt(data, p, k);
                if (consumed <= 0) {
                    return;
                }
                p += consumed;
            }
            if ((linkFlags & 0x4) != 0) {
                p = skipCounted(data, p);
            }
            if ((linkFlags & 0x8) != 0) {
                Object[] r = readCounted(data, p);
                k.relativePath = (String) r[0];
                p = (Integer) r[1];
            }
            if ((linkFlags & 0x10) != 0) {
                Object[] r = readCounted(data, p);
                k.workingDir = (String) r[0];
                p = (Integer) r[1];
            }
            if ((linkFlags & 0x20) != 0) {
                p = skipCounted(data, p);
            }
            if ((linkFlags & 0x40) != 0) {
                Object[] r = readCounted(data, p);
                k.iconLocation = (String) r[0];
            }
            out.add(k);
        } catch (RuntimeException ignored) {
            // 候选位置不可信，丢弃
        }
    }

    /** 在给定位置解析 LinkInfo；成功返回总长，失败返回 -1。 */
    private static int parseLinkInfoAt(byte[] data, int liStart, K k) {
        if (liStart + 28 > data.length) {
            return -1;
        }
        int liSize = LittleEndian.getInt(data, liStart);
        if (liSize < 28 || liStart + liSize > data.length) {
            return -1;
        }
        int liHeaderSize = LittleEndian.getInt(data, liStart + 4);
        if (liHeaderSize < 28) {
            return -1;
        }
        int liFlags = LittleEndian.getInt(data, liStart + 8);
        int localBaseOffset = LittleEndian.getInt(data, liStart + 16);
        int cnrlOffset = LittleEndian.getInt(data, liStart + 20);
        int commonSuffixOffset = LittleEndian.getInt(data, liStart + 24);

        String suffix = "";
        if (commonSuffixOffset > 0 && commonSuffixOffset < liSize
                && liStart + commonSuffixOffset < data.length) {
            suffix = cstr(data, liStart + commonSuffixOffset, liStart + liSize, Charset.defaultCharset());
        }

        if ((liFlags & 0x1) != 0 && localBaseOffset > 0 && localBaseOffset < liSize
                && liStart + localBaseOffset < data.length) {
            String base = cstr(data, liStart + localBaseOffset, liStart + liSize, Charset.defaultCharset());
            if (!base.isEmpty()) {
                k.localBasePath = base + suffix;
            }
        } else if (cnrlOffset > 0 && cnrlOffset + 12 < liSize
                && liStart + cnrlOffset + 12 < data.length) {
            int netNameOffset = LittleEndian.getInt(data, liStart + cnrlOffset + 8);
            if (netNameOffset > 0 && cnrlOffset + netNameOffset < liSize
                    && liStart + cnrlOffset + netNameOffset < data.length) {
                String net = cstr(data, liStart + cnrlOffset + netNameOffset, liStart + liSize,
                        Charset.defaultCharset());
                if (!net.isEmpty()) {
                    k.localBasePath = net + suffix;
                }
            }
        }
        return liSize;
    }

    /** 挑最可信的一组：优先「IconLocation / LocalBasePath 指向真实存在的文件」。 */
    private static void choosePreferred(Link link, List<K> candidates) {
        K best = null;
        int bestScore = Integer.MIN_VALUE;
        for (K k : candidates) {
            int score = 0;
            if (fileExists(k.iconLocation)) {
                score += 100;
            }
            if (fileExists(k.localBasePath)) {
                score += 60;
            }
            if (k.iconLocation != null) {
                score += 12;
            }
            if (k.localBasePath != null) {
                score += 8;
            }
            if (k.relativePath != null) {
                score += 4;
            }
            if (k.workingDir != null) {
                score += 2;
            }
            if (k.strict) {
                score += 1;
            }
            if (score > bestScore) {
                bestScore = score;
                best = k;
            }
        }
        if (best == null) {
            return;
        }
        // 有内容的一律采纳；但「严格链」已有的值不被探测结果覆盖
        if (best.iconLocation != null) {
            link.iconLocation = best.iconLocation;
        }
        if (link.relativePath == null) {
            link.relativePath = best.relativePath;
        }
        if (link.workingDir == null) {
            link.workingDir = best.workingDir;
        }
        if (link.localBasePath == null) {
            link.localBasePath = best.localBasePath;
        }
    }

    private static boolean fileExists(String p) {
        return p != null && p.length() > 3 && new File(p).isFile();
    }

    /** 判断某处能否形成合法的 LinkInfo + 计数字符串链（尽量走到文件末尾）。 */
    private static boolean chainValid(byte[] data, int pos, int linkFlags) {
        try {
            int p = pos;
            if ((linkFlags & 0x2) != 0) {
                if (p + 28 > data.length) {
                    return false;
                }
                int liSize = LittleEndian.getInt(data, p);
                if (liSize < 28 || p + liSize > data.length) {
                    return false;
                }
                if (LittleEndian.getInt(data, p + 4) < 28) {
                    return false;
                }
                p += liSize;
            }
            if ((linkFlags & 0x4) != 0) {
                p = skipCounted(data, p);
            }
            if ((linkFlags & 0x8) != 0) {
                p = skipCounted(data, p);
            }
            if ((linkFlags & 0x10) != 0) {
                p = skipCounted(data, p);
            }
            if ((linkFlags & 0x20) != 0) {
                p = skipCounted(data, p);
            }
            if ((linkFlags & 0x40) != 0) {
                p = skipCounted(data, p);
            }
            return p <= data.length;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 在头部之后按 2 字节步进找第一个像 LinkInfo 开头的位置。 */
    private static int scanForLinkInfo(byte[] data, int from) {
        for (int p = from; p + 28 <= data.length; p += 2) {
            int size = LittleEndian.getInt(data, p);
            if (size < 28 || p + size > data.length) {
                continue;
            }
            int headerSize = LittleEndian.getInt(data, p + 4);
            if (headerSize < 28 || headerSize > size) {
                continue;
            }
            int flags = LittleEndian.getInt(data, p + 8);
            // Flags 只用了低 2 位
            if ((flags & ~0x3) != 0) {
                continue;
            }
            return p;
        }
        return -1;
    }

    private static int skipCounted(byte[] data, int p) {
        Object[] r = readCounted(data, p);
        return (Integer) r[1];
    }

    /** 读计数字符串，返回 [内容, 新位置, 字符数]。 */
    private static Object[] readCounted(byte[] data, int p) {
        if (p + 2 > data.length) {
            throw new IllegalArgumentException("计数字符串越界");
        }
        int chars = LittleEndian.getShort(data, p);
        int end = p + 2 + chars * 2;
        if (chars < 0 || end > data.length) {
            throw new IllegalArgumentException("计数字符串越界");
        }
        StringBuilder sb = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            sb.append((char) (LittleEndian.getShort(data, p + 2 + i * 2) & 0xFFFF));
        }
        return new Object[]{sb.toString(), Integer.valueOf(end), Integer.valueOf(chars)};
    }

    // ------------------------------------------------------------------ 路径提取

    /** 找 ItemIDList 里最后一个完整可用的文件系统路径。 */
    private static String lastPathInIdList(byte[] data, int start, int end) {
        String best = null;
        for (String candidate : pathsInRegion(data, start, end)) {
            if (candidate.toLowerCase(Locale.ROOT).endsWith(".lnk")) {
                continue;
            }
            if (best == null || candidate.length() >= best.length()) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 把粘连的路径串拆成多个候选。
     *
     * <p>零填充会把 IDList 与 LinkInfo 的内容粘成一个串，例如
     * {@code D:\perfectWorld\perfectworldarena.D:\perfectWorld\perfectworldarena\完美世界竞技平台.exe`}。
     * 这里按「内嵌的盘符标记」（{@code X:\} 且前面既不是 {@code \} 也不是 {@code :}）切开：
     * 切出后半段整串，以及前半段（可能是目录）。
     *
     * @return 拆分后的候选列表（原串本身合法时也会保留）
     */
    static List<String> splitConcatenated(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null || s.length() < 4) {
            return out;
        }
        // 去掉尾部杂散字符（如实现里出现过的反引号）
        String cleaned = s;
        while (cleaned.length() > 3) {
            char last = cleaned.charAt(cleaned.length() - 1);
            if (last == '`' || last == '\'' || last == '"' || last == ' ') {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            } else {
                break;
            }
        }
        if (cleaned.length() < 4) {
            return out;
        }

        int cut = -1;
        for (int i = 3; i + 1 < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == '\\' || c == '/') {
                continue;
            }
            if (!Character.isLetter(c)) {
                continue;
            }
            if (i + 2 >= cleaned.length()) {
                continue;
            }
            // X:\ 形式，且前面不是路径分隔符或盘符冒号
            if (cleaned.charAt(i + 1) == ':' && (cleaned.charAt(i + 2) == '\\' || cleaned.charAt(i + 2) == '/')) {
                char prev = cleaned.charAt(i - 1);
                if (prev != '\\' && prev != '/') {
                    cut = i;
                    break;
                }
            }
        }

        if (cut < 0) {
            out.add(cleaned);
            return out;
        }
        String tail = cleaned.substring(cut);
        String head = cleaned.substring(0, cut);
        out.add(tail);
        // 前缀（去掉紧贴的点号）作为低优先级候选
        while (head.length() > 3 && (head.endsWith(".") || head.endsWith(" ") || head.endsWith("\\"))) {
            head = head.substring(0, head.length() - 1);
        }
        if (head.length() > 3) {
            out.add(head);
        }
        return out;
    }

    /**
     * 在给定区间里扫出所有像路径的字符串（UTF-16LE 与 ANSI 两种编码）。
     *
     * <p>UTF-16LE 处理要注意：中文的 UTF-16 高位字节不为 0，早期实现用 {@code hi != 0}
     * 判定为「非路径字符」直接丢弃，导致中文路径被啃成 {@code ??????}（实测
     * 完美世界竞技平台.lnk 因此取不到图标）。这里只按控制字符与 NUL 分段。
     */
    private static List<String> pathsInRegion(byte[] data, int from, int to) {
        List<String> out = new ArrayList<String>();
        int start = Math.max(0, from);
        int end = Math.min(to, data.length);

        // UTF-16LE：按 NUL / 控制字符分段
        StringBuilder sb = new StringBuilder();
        for (int i = start; i + 1 < end; i += 2) {
            char c = (char) ((data[i] & 0xFF) | ((data[i + 1] & 0xFF) << 8));
            if (c == 0 || c < 0x20) {
                flush(out, sb);
            } else {
                sb.append(c);
            }
        }
        flush(out, sb);

        // ANSI：按 NUL 分段
        int i = start;
        while (i < end) {
            int j = i;
            while (j < end && data[j] != 0) {
                j++;
            }
            if (j - i > 3) {
                extractPath(out, new String(data, i, j - i, Charset.defaultCharset()));
            }
            i = j + 1;
        }
        return out;
    }

    private static void flush(List<String> out, StringBuilder sb) {
        if (sb.length() > 3) {
            extractPath(out, sb.toString());
        }
        sb.setLength(0);
    }

    private static void extractPath(List<String> out, String s) {
        if (s == null || s.length() < 4) {
            return;
        }
        Matcher m = PATH_LIKE.matcher(s);
        while (m.find()) {
            String p = m.group().trim();
            while (p.length() > 3 && (p.endsWith("\\") || p.endsWith(" "))) {
                p = p.substring(0, p.length() - 1);
            }
            if (p.length() > 3) {
                out.add(p);
            }
        }
    }

    /** 用 RelativePath / WorkingDir 补全目标；优先选真实存在的候选。 */
    private static String completeTarget(Link link) {
        List<String> candidates = new ArrayList<String>();
        if (link.localBasePath != null) {
            candidates.add(link.localBasePath);
        }
        if (link.target != null) {
            candidates.add(link.target);
        }
        if (link.workingDir != null && link.relativePath != null) {
            candidates.add(join(link.workingDir, link.relativePath));
        }
        if (link.relativePath != null) {
            candidates.add(link.relativePath);
        }
        if (link.workingDir != null) {
            candidates.add(link.workingDir);
        }
        for (String c : candidates) {
            if (c != null && new File(c).isFile()) {
                return c;
            }
        }
        for (String c : candidates) {
            if (c != null && new File(c).isDirectory()) {
                return c;
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static String join(String dir, String rel) {
        if (dir == null) {
            return rel;
        }
        if (rel == null) {
            return dir;
        }
        if (rel.length() > 1 && rel.charAt(1) == ':' || rel.startsWith("\\\\")) {
            return rel;
        }
        String d = dir.endsWith("\\") ? dir.substring(0, dir.length() - 1) : dir;
        String r = rel.startsWith("\\") ? rel.substring(1) : rel;
        return d + "\\" + r;
    }

    /** 全文兜底：结构解析没拿到任何字段时，直接在文件里找路径。 */
    static void heuristic(byte[] data, Link link) {
        for (String p : pathsInRegion(data, 0, data.length)) {
            String lower = p.toLowerCase(Locale.ROOT);
            if (link.iconLocation == null && (lower.endsWith(".ico") || lower.endsWith(".exe")
                    || lower.endsWith(".dll"))) {
                link.iconLocation = p;
            } else if (link.idListTarget == null && lower.endsWith(".exe")) {
                link.idListTarget = p;
            }
        }
    }

    /**
     * 展开 IconLocation：去掉 {@code ,序号} 后缀、展开环境变量、统一分隔符。
     *
     * @return 候选文件路径列表
     */
    public static List<String> expand(String iconLocation) {
        List<String> out = new ArrayList<String>();
        if (iconLocation == null || iconLocation.trim().isEmpty()) {
            return out;
        }
        String s = iconLocation.trim();
        int comma = s.lastIndexOf(',');
        if (comma > 0) {
            String tail = s.substring(comma + 1).trim();
            boolean numeric = !tail.isEmpty();
            for (int i = 0; i < tail.length(); i++) {
                char c = tail.charAt(i);
                if (!Character.isDigit(c) && c != '-' && c != '+') {
                    numeric = false;
                    break;
                }
            }
            if (numeric) {
                s = s.substring(0, comma);
            }
        }
        s = expandEnv(s).replace('/', '\\');
        if (s.length() > 3) {
            out.add(s);
        }
        return out;
    }

    private static String expandEnv(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%') {
                int end = s.indexOf('%', i + 1);
                if (end > i + 1) {
                    String name = s.substring(i + 1, end);
                    String value = System.getenv(name);
                    if (value != null) {
                        out.append(value);
                        i = end + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String cstr(byte[] data, int off, int limit, Charset cs) {
        int end = off;
        int max = Math.min(limit, data.length);
        while (end < max && data[end] != 0) {
            end++;
        }
        return new String(data, off, end - off, cs);
    }
}
