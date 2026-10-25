package com.wbm.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/**
 * Convierte una imagen ISO de Wii en un archivo WBFS sin pérdida:
 * los bloques que contienen solo ceros no se guardan, el resto se copia tal cual.
 * Al volver a ISO se obtiene exactamente el mismo contenido.
 */
public final class WbfsWriter {
    private WbfsWriter() {}

    public static final class Result {
        public final DiscInfo disc;
        public final long isoSize;
        public final long wbfsSize;
        public final int blockShift;
        public final int usedBlocks;
        public final int totalBlocks;

        Result(DiscInfo disc, long isoSize, long wbfsSize, int blockShift, int usedBlocks, int totalBlocks) {
            this.disc = disc;
            this.isoSize = isoSize;
            this.wbfsSize = wbfsSize;
            this.blockShift = blockShift;
            this.usedBlocks = usedBlocks;
            this.totalBlocks = totalBlocks;
        }
    }

    /**
     * @param blockShift log2 del tamaño de bloque WBFS, o 0 para elegirlo automáticamente
     */
    public static Result convert(FileChannel iso, SplitSink sink, int blockShift, Progress progress)
            throws IOException {
        long isoSize = iso.size();
        if (isoSize < WbfsFormat.DISC_HEADER_COPY) {
            throw new UnsupportedDiscException("El archivo es demasiado pequeño para ser un disco de Wii.");
        }
        byte[] header = new byte[WbfsFormat.DISC_HEADER_COPY];
        readFullyAt(iso, 0, ByteBuffer.wrap(header));
        DiscInfo disc = DiscInfo.parse(header);
        if (disc.gamecube && !disc.wii) {
            throw new UnsupportedDiscException("Es un disco de GameCube; WBFS solo admite discos de Wii.");
        }
        if (!disc.wii) {
            throw new UnsupportedDiscException(
                    "No parece una imagen ISO de Wii (falta la firma del disco). "
                            + "Si el archivo está comprimido (CISO, WIA, RVZ...) conviértelo antes a ISO.");
        }

        int shift = blockShift == 0 ? WbfsFormat.chooseShift(isoSize) : blockShift;
        if (!WbfsFormat.shiftFits(shift)) {
            throw new IllegalArgumentException("Tamaño de bloque no válido: 2^" + shift);
        }
        int bs = 1 << shift;
        int entries = WbfsFormat.tableEntries(shift);
        long nBlocks = (isoSize + bs - 1) / bs;
        if (nBlocks > entries || nBlocks + 1 > 0xFFFF) {
            throw new UnsupportedDiscException(
                    "La imagen es más grande que un disco Wii de doble capa o que el tamaño de bloque elegido.");
        }

        char[] wlba = new char[entries];
        byte[] buf = new byte[bs];
        ByteBuffer bb = ByteBuffer.wrap(buf);

        sink.write(0, new byte[bs], 0, bs); // bloque 0 provisional; se reescribe al final

        int next = 1;
        for (long i = 0; i < nBlocks; i++) {
            long pos = i * bs;
            int want = (int) Math.min(bs, isoSize - pos);
            bb.clear();
            bb.limit(want);
            readFullyAt(iso, pos, bb);
            if (want < bs) Arrays.fill(buf, want, bs, (byte) 0);
            if (!isZero(buf)) {
                wlba[(int) i] = (char) next;
                sink.write((long) next * bs, buf, 0, bs);
                next++;
            }
            if (progress != null && !progress.update(pos + want, isoSize)) throw new CancelledException();
        }

        long wbfsSize = (long) next * bs;
        ByteBuffer h = ByteBuffer.allocate(bs).order(ByteOrder.BIG_ENDIAN);
        h.putInt(0, WbfsFormat.MAGIC);
        h.putInt(4, (int) (wbfsSize / WbfsFormat.HD_SEC_SIZE));
        h.put(8, (byte) WbfsFormat.HD_SEC_SHIFT);
        h.put(9, (byte) shift);
        h.put(12, (byte) 1); // disc_table[0]: hay un disco
        h.position(WbfsFormat.HD_SEC_SIZE);
        h.put(header);
        for (int i = 0; i < entries; i++) h.putShort((short) wlba[i]);
        sink.write(0, h.array(), 0, bs);

        return new Result(disc, isoSize, wbfsSize, shift, next - 1, (int) nBlocks);
    }

    static void readFullyAt(FileChannel ch, long pos, ByteBuffer bb) throws IOException {
        long p = pos;
        while (bb.hasRemaining()) {
            int r = ch.read(bb, p);
            if (r < 0) throw new IOException("Fin de archivo inesperado al leer la ISO");
            p += r;
        }
    }

    static boolean isZero(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b);
        int longs = b.length / 8;
        for (int i = 0; i < longs; i++) {
            if (bb.getLong() != 0L) return false;
        }
        for (int i = longs * 8; i < b.length; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }
}
