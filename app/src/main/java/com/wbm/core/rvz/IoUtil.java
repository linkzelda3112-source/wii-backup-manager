package com.wbm.core.rvz;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

final class IoUtil {
    private IoUtil() {}

    static void readFully(FileChannel ch, long pos, byte[] buf) throws IOException {
        readFully(ch, pos, buf, 0, buf.length);
    }

    /** Lee exactamente {@code len} bytes hacia buf[off..off+len); el resto de buf queda sin tocar. */
    static void readFully(FileChannel ch, long pos, byte[] buf, int off, int len) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(buf, off, len);
        int start = bb.position();
        while (bb.hasRemaining()) {
            int n = ch.read(bb, pos + (bb.position() - start));
            if (n < 0) throw new RvzUnpackException("Fin de archivo inesperado al leer el RVZ/WIA (pos " + pos + ")");
        }
    }

    static void writeFully(FileChannel ch, long filePos, byte[] buf, int off, int len) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(buf, off, len);
        long pos = filePos;
        while (bb.hasRemaining()) {
            pos += ch.write(bb, pos);
        }
    }
}
