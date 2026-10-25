package com.wbm.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/** Lee un archivo WBFS (con un disco) y lo reconstruye como ISO. */
public final class WbfsReader {
    public final DiscInfo disc;
    public final int blockShift;
    public final byte[] discHeader;

    private final SplitSource src;
    private final char[] wlba;
    private final int blockSize;

    private WbfsReader(SplitSource src, DiscInfo disc, byte[] discHeader, int blockShift, char[] wlba) {
        this.src = src;
        this.disc = disc;
        this.discHeader = discHeader;
        this.blockShift = blockShift;
        this.blockSize = 1 << blockShift;
        this.wlba = wlba;
    }

    public static WbfsReader open(SplitSource src) throws IOException {
        if (src.length() < WbfsFormat.HD_SEC_SIZE) {
            throw new IOException("El archivo es demasiado pequeño para ser WBFS.");
        }
        byte[] first = new byte[WbfsFormat.HD_SEC_SIZE];
        src.readFully(0, first, 0, first.length);
        ByteBuffer h = ByteBuffer.wrap(first).order(ByteOrder.BIG_ENDIAN);
        if (h.getInt(0) != WbfsFormat.MAGIC) {
            throw new IOException("No es un archivo WBFS (firma incorrecta).");
        }
        int hdShift = first[8] & 0xFF;
        int shift = first[9] & 0xFF;
        if (hdShift < 9 || hdShift > 16 || shift < WbfsFormat.WII_SEC_SHIFT || shift > 28 || shift < hdShift) {
            throw new IOException("Cabecera WBFS no válida (tamaños de sector " + hdShift + "/" + shift + ").");
        }
        int hdSize = 1 << hdShift;

        byte[] table = first;
        if (hdSize != first.length) {
            table = new byte[hdSize];
            src.readFully(0, table, 0, hdSize);
        }
        int discIdx = -1;
        for (int i = 12; i < hdSize; i++) {
            if (table[i] != 0) {
                discIdx = i - 12;
                break;
            }
        }
        if (discIdx < 0) throw new IOException("El archivo WBFS no contiene ningún disco.");

        int entries = WbfsFormat.tableEntries(shift);
        int infoSize = WbfsFormat.discInfoSize(shift, hdSize);
        long infoPos = (long) hdSize + (long) discIdx * infoSize;
        int need = WbfsFormat.DISC_HEADER_COPY + entries * 2;
        byte[] info = new byte[need];
        src.readFully(infoPos, info, 0, need);

        byte[] header = new byte[WbfsFormat.DISC_HEADER_COPY];
        System.arraycopy(info, 0, header, 0, header.length);
        DiscInfo disc = DiscInfo.parse(header);
        if (!disc.wii) throw new IOException("El disco guardado en el WBFS no tiene firma de Wii.");

        ByteBuffer t = ByteBuffer.wrap(info).order(ByteOrder.BIG_ENDIAN);
        char[] wlba = new char[entries];
        long bs = 1L << shift;
        for (int i = 0; i < entries; i++) {
            char v = (char) (t.getShort(WbfsFormat.DISC_HEADER_COPY + i * 2) & 0xFFFF);
            wlba[i] = v;
            if (v != 0 && (long) v * bs >= src.length()) {
                throw new IOException("El archivo WBFS está incompleto o truncado "
                        + "(¿faltan las partes .wbf1, .wbf2...?).");
            }
        }
        return new WbfsReader(src, disc, header, shift, wlba);
    }

    /** Bytes lógicos hasta el final del último bloque con datos. */
    public long logicalSize() {
        int last = -1;
        for (int i = wlba.length - 1; i >= 0; i--) {
            if (wlba[i] != 0) {
                last = i;
                break;
            }
        }
        return (long) (last + 1) * blockSize;
    }

    public int usedBlocks() {
        int n = 0;
        for (char c : wlba) if (c != 0) n++;
        return n;
    }

    /**
     * Reconstruye la ISO en out (escritura secuencial desde la posición 0).
     *
     * @param padStandard rellena con ceros hasta el tamaño estándar de una ISO de Wii
     * @return bytes escritos
     */
    public long toIso(FileChannel out, boolean padStandard, Progress progress) throws IOException {
        long logical = logicalSize();
        long target = padStandard ? WbfsFormat.padTarget(logical) : logical;
        byte[] buf = new byte[blockSize];
        byte[] zero = new byte[blockSize];
        long written = 0;
        int blocks = (int) (logical >> blockShift);
        for (int i = 0; i < blocks; i++) {
            byte[] data = zero;
            if (wlba[i] != 0) {
                long pos = (long) wlba[i] * blockSize;
                int n = src.readAt(pos, buf, 0, blockSize);
                if (n < blockSize) java.util.Arrays.fill(buf, n, blockSize, (byte) 0);
                data = buf;
            }
            writeFully(out, data, blockSize);
            written += blockSize;
            if (progress != null && !progress.update(written, target)) throw new CancelledException();
        }
        while (written < target) {
            int n = (int) Math.min(blockSize, target - written);
            writeFully(out, zero, n);
            written += n;
            if (progress != null && !progress.update(written, target)) throw new CancelledException();
        }
        return written;
    }

    /** Comprueba que todo el contenido se puede leer; no escribe nada. */
    public void checkReadable(Progress progress) throws IOException {
        byte[] buf = new byte[blockSize];
        int total = usedBlocks();
        int done = 0;
        for (char c : wlba) {
            if (c == 0) continue;
            long pos = (long) c * blockSize;
            if (src.readAt(pos, buf, 0, 1) != 1) throw new IOException("Bloque ilegible en " + pos);
            done++;
            if (progress != null && !progress.update(done, total)) throw new CancelledException();
        }
    }

    private static void writeFully(FileChannel out, byte[] data, int len) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data, 0, len);
        while (bb.hasRemaining()) out.write(bb);
    }
}
