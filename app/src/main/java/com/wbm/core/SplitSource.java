package com.wbm.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;

/** Lectura de un archivo lógico formado por una o varias partes consecutivas (.wbfs, .wbf1, ...). */
public final class SplitSource implements Closeable {
    private final FileChannel[] parts;
    private final long[] starts;
    private final long length;

    public SplitSource(List<FileChannel> channels) throws IOException {
        parts = channels.toArray(new FileChannel[0]);
        starts = new long[parts.length];
        long total = 0;
        for (int i = 0; i < parts.length; i++) {
            starts[i] = total;
            total += parts[i].size();
        }
        length = total;
    }

    public long length() {
        return length;
    }

    /** Lee hasta len bytes; devuelve cuántos se leyeron (menos de len solo al llegar al final). */
    public int readAt(long pos, byte[] buf, int off, int len) throws IOException {
        int done = 0;
        while (done < len && pos < length) {
            int p = partFor(pos);
            long inPart = pos - starts[p];
            long avail = parts[p].size() - inPart;
            int n = (int) Math.min(len - done, avail);
            if (n <= 0) break;
            ByteBuffer bb = ByteBuffer.wrap(buf, off + done, n);
            while (bb.hasRemaining()) {
                int r = parts[p].read(bb, inPart + bb.position() - (off + done));
                if (r < 0) return done + (bb.position() - (off + done));
            }
            done += n;
            pos += n;
        }
        return done;
    }

    public void readFully(long pos, byte[] buf, int off, int len) throws IOException {
        if (readAt(pos, buf, off, len) != len) {
            throw new IOException("Fin de archivo inesperado (¿faltan partes del archivo dividido?)");
        }
    }

    private int partFor(long pos) {
        int p = parts.length - 1;
        while (p > 0 && starts[p] > pos) p--;
        return p;
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        for (FileChannel c : parts) {
            try {
                c.close();
            } catch (IOException e) {
                if (first == null) first = e;
            }
        }
        if (first != null) throw first;
    }
}
