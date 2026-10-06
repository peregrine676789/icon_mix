package iconmix.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * 小端读写工具。
 *
 * <p>ICO / DIB / PE 三种格式的容器字段一律小端，而 {@code DataOutputStream} 的
 * {@code writeShort/writeInt} 是<b>大端</b>，混用会造成「文件大小、帧数、偏移全对，
 * 但 Type 读成 256」这类极隐蔽的 bug（详见需求说明书 T-4）。
 * 因此本项目的所有二进制写出统一走这里。
 */
public final class LittleEndian {

    private LittleEndian() {
    }

    public static void putShort(byte[] buf, int off, int value) {
        buf[off] = (byte) (value & 0xFF);
        buf[off + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    public static void putInt(byte[] buf, int off, int value) {
        buf[off] = (byte) (value & 0xFF);
        buf[off + 1] = (byte) ((value >>> 8) & 0xFF);
        buf[off + 2] = (byte) ((value >>> 16) & 0xFF);
        buf[off + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    public static int getShort(byte[] buf, int off) {
        return (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8);
    }

    public static int getInt(byte[] buf, int off) {
        return (buf[off] & 0xFF)
                | ((buf[off + 1] & 0xFF) << 8)
                | ((buf[off + 2] & 0xFF) << 16)
                | ((buf[off + 3] & 0xFF) << 24);
    }

    public static byte[] shorts(int... values) {
        byte[] out = new byte[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            putShort(out, i * 2, values[i]);
        }
        return out;
    }

    /**
     * 只读游标，所有读取都做边界校验，越界即抛 {@link IOException} 便于逐文件跳过。
     */
    public static final class Reader {
        private final byte[] data;
        private int pos;

        public Reader(byte[] data) {
            this(data, 0);
        }

        public Reader(byte[] data, int pos) {
            this.data = data;
            this.pos = pos;
        }

        public byte[] array() {
            return data;
        }

        public int position() {
            return pos;
        }

        public void position(int newPos) throws IOException {
            check(newPos, 0);
            this.pos = newPos;
        }

        public void skip(int n) throws IOException {
            check(pos, n);
            pos += n;
        }

        public int u8() throws IOException {
            check(pos, 1);
            return data[pos++] & 0xFF;
        }

        public int u16() throws IOException {
            check(pos, 2);
            int v = LittleEndian.getShort(data, pos);
            pos += 2;
            return v;
        }

        public int u32() throws IOException {
            check(pos, 4);
            int v = LittleEndian.getInt(data, pos);
            pos += 4;
            return v;
        }

        public byte[] bytes(int n) throws IOException {
            check(pos, n);
            byte[] out = Arrays.copyOfRange(data, pos, pos + n);
            pos += n;
            return out;
        }

        public void check(int off, int len) throws IOException {
            if (off < 0 || len < 0 || off + len > data.length) {
                throw new IOException("越界读取: offset=" + off + " len=" + len + " size=" + data.length);
            }
        }
    }

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Writer u8(int v) {
            out.write(v & 0xFF);
            return this;
        }

        public Writer u16(int v) {
            out.write(v & 0xFF);
            out.write((v >>> 8) & 0xFF);
            return this;
        }

        public Writer u32(int v) {
            out.write(v & 0xFF);
            out.write((v >>> 8) & 0xFF);
            out.write((v >>> 16) & 0xFF);
            out.write((v >>> 24) & 0xFF);
            return this;
        }

        /** 大端写入：PNG 等格式用（注意与 ICO/DIB 的小端区分，见 T-4 与 T-11）。 */
        public Writer be32(int v) {
            out.write((v >>> 24) & 0xFF);
            out.write((v >>> 16) & 0xFF);
            out.write((v >>> 8) & 0xFF);
            out.write(v & 0xFF);
            return this;
        }

        public Writer raw(byte[] b) {
            out.write(b, 0, b.length);
            return this;
        }

        public Writer raw(byte[] b, int off, int len) {
            out.write(b, off, len);
            return this;
        }

        public int size() {
            return out.size();
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
