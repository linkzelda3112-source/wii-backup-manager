package com.wbm.core.rvz;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

final class IoUtil {
    private IoUtil() {}

    static void readFully(FileChannel ch, long pos, byte[] buf) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(buf);
        while (bb.hasRemaining()) {
            int n = ch.read(bb, pos + bb.position());
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
