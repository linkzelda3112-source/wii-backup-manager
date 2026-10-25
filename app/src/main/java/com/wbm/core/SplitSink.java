package com.wbm.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/** Escritura posicional sobre un archivo lógico que puede dividirse en partes de tamaño fijo. */
public final class SplitSink implements Closeable {
    public interface Opener {
        /** Abre (creando) la parte número partIndex. La parte 0 es el archivo principal. */
        FileChannel open(int partIndex) throws IOException;
    }

    private final Opener opener;
    private final long splitSize;
    private final List<FileChannel> parts = new ArrayList<>();

    /** splitSize <= 0 significa sin dividir. */
    public SplitSink(Opener opener, long splitSize) {
        this.opener = opener;
        this.splitSize = splitSize > 0 ? splitSize : Long.MAX_VALUE;
    }

    public void write(long pos, byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            int part = (int) (pos / splitSize);
            long inPart = pos % splitSize;
            int n = (int) Math.min(len, splitSize - inPart);
            FileChannel ch = part(part);
            ByteBuffer bb = ByteBuffer.wrap(b, off, n);
            long p = inPart;
            while (bb.hasRemaining()) p += ch.write(bb, p);
            pos += n;
            off += n;
            len -= n;
        }
    }

    private FileChannel part(int index) throws IOException {
        while (parts.size() <= index) parts.add(opener.open(parts.size()));
        return parts.get(index);
    }

    public int partCount() {
        return parts.size();
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
        parts.clear();
        if (first != null) throw first;
    }
}
