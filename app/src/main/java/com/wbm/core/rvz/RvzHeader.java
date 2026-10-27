package com.wbm.core.rvz;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Cabecera de un contenedor WIA o RVZ (misma disposición, salvo el tamaño de las entradas de
 * grupo: 8 bytes en WIA, 12 en RVZ). Especificación: docs/WiaAndRvz.md del proyecto Dolphin.
 */
final class RvzHeader {
    boolean isWia; // false = RVZ
    long isoFileSize;
    byte[] discHash = new byte[20];

    int discType; // 1 = GameCube, 2 = Wii
    int compression;
    int chunkSize;
    byte[] discHeadPreview = new byte[0x80];

    int nPart;
    int partTableEntrySize;
    long partTableOff;

    int nRawData;
    long rawDataTableOff;

    int nGroups;
    long groupTableOff;
    int groupEntrySize; // 8 (WIA) o 12 (RVZ)

    int comprDataLen;
    byte[] comprData = new byte[7];

    static RvzHeader parse(FileChannel in) throws IOException {
        byte[] head = new byte[0x124];
        IoUtil.readFully(in, 0, head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN);

        RvzHeader h = new RvzHeader();
        boolean rvz = head[0] == 'R' && head[1] == 'V' && head[2] == 'Z' && head[3] == 1;
        boolean wia = head[0] == 'W' && head[1] == 'I' && head[2] == 'A' && head[3] == 1;
        if (!rvz && !wia) {
            throw new RvzUnpackException("No es un archivo RVZ ni WIA (firma incorrecta).");
        }
        h.isWia = wia;
        h.isoFileSize = b.getLong(0x24);
        b.position(0x10);
        b.get(h.discHash);

        h.discType = b.getInt(0x48);
        h.compression = b.getInt(0x4C);
        h.chunkSize = b.getInt(0x54);
        b.position(0x58);
        b.get(h.discHeadPreview);

        h.nPart = b.getInt(0xD8);
        h.partTableEntrySize = b.getInt(0xDC);
        h.partTableOff = b.getLong(0xE0);

        h.nRawData = b.getInt(0xFC);
        h.rawDataTableOff = b.getLong(0x100);

        h.nGroups = b.getInt(0x10C);
        h.groupTableOff = b.getLong(0x110);
        h.groupEntrySize = h.isWia ? 8 : 12;

        h.comprDataLen = head[0x11C] & 0xFF;
        b.position(0x11D);
        b.get(h.comprData);

        if (h.discType != 1 && h.discType != 2) {
            throw new RvzUnpackException("Tipo de disco desconocido en el archivo (" + h.discType + ").");
        }
        if (h.isoFileSize <= 0) {
            throw new RvzUnpackException("Tamaño de ISO inválido en la cabecera del archivo.");
        }
        return h;
    }

    boolean isWiiDisc() {
        return discType == 2;
    }
}
