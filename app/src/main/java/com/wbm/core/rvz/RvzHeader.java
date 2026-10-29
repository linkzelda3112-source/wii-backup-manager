package com.wbm.core.rvz;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Cabecera de un contenedor WIA o RVZ (misma disposición, salvo el tamaño de las entradas de
 * grupo: 8 bytes en WIA, 12 en RVZ). Especificación: docs/WiaAndRvz.md del proyecto Dolphin.
 *
 * El archivo tiene dos cabeceras seguidas: WIAFileHeader (0x48 bytes fijos) y, justo después,
 * WIADisc, cuyo tamaño REAL en el archivo lo indica el propio campo "disc_size" de
 * WIAFileHeader (no es necesariamente igual a sizeof(WIADisc) = 0xDC: un archivo más viejo puede
 * guardar una versión más corta de WIADisc, y uno más nuevo una más larga con campos que no
 * conocemos). Si se asume siempre un tamaño fijo, un archivo con un disc_size distinto hace que
 * se lean como campos de la cabecera bytes que en realidad son de otra sección del archivo.
 */
final class RvzHeader {
    /** sizeof(WIADisc) con todos los campos que conoce este lector. */
    private static final int WIA_DISC_SIZE = 0xDC;

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
        // --- WIAFileHeader: 0x48 bytes fijos, siempre en la misma posición. ---
        byte[] head1 = new byte[0x48];
        IoUtil.readFully(in, 0, head1);

        boolean rvz = head1[0] == 'R' && head1[1] == 'V' && head1[2] == 'Z' && head1[3] == 1;
        boolean wia = head1[0] == 'W' && head1[1] == 'I' && head1[2] == 'A' && head1[3] == 1;
        if (!rvz && !wia) {
            throw new RvzUnpackException("No es un archivo RVZ ni WIA (firma incorrecta).");
        }

        ByteBuffer b1 = ByteBuffer.wrap(head1).order(ByteOrder.BIG_ENDIAN);
        RvzHeader h = new RvzHeader();
        h.isWia = wia;
        int discSize = b1.getInt(0x0C); // tamaño real de WIADisc en ESTE archivo
        h.isoFileSize = b1.getLong(0x24);
        b1.position(0x10);
        b1.get(h.discHash);
        if (h.isoFileSize <= 0) {
            throw new RvzUnpackException("Tamaño de ISO inválido en la cabecera del archivo.");
        }
        if (discSize <= 0 || discSize > 1 << 20) {
            throw new RvzUnpackException("Tamaño de cabecera de disco (disc_size) fuera de rango en el "
                    + "archivo: " + discSize + " bytes.");
        }

        // --- WIADisc: empieza siempre en el byte 0x48, pero mide "discSize" bytes en el archivo,
        // no necesariamente WIA_DISC_SIZE. Se lee tal cual y se rellena con ceros si es más corto
        // (igual que hacen los lectores oficiales), o se recorta si es más largo (versión futura
        // con campos al final que este lector no conoce todavía). Así los campos que sí conocemos
        // (número y posición de las tablas de grupos/raw_data/particiones) se leen siempre del
        // lugar correcto del archivo, sea cual sea la versión que lo escribió.
        byte[] disc = new byte[WIA_DISC_SIZE];
        IoUtil.readFully(in, 0x48, disc, 0, Math.min(discSize, disc.length));
        ByteBuffer b = ByteBuffer.wrap(disc).order(ByteOrder.BIG_ENDIAN);

        h.discType = b.getInt(0x00);
        h.compression = b.getInt(0x04);
        // 0x08: compression_level (s32) — no se usa.
        h.chunkSize = b.getInt(0x0C);
        b.position(0x10);
        b.get(h.discHeadPreview); // 0x10..0x90

        h.nPart = b.getInt(0x90);
        h.partTableEntrySize = b.getInt(0x94);
        h.partTableOff = b.getLong(0x98);
        // 0xA0..0xB4: hash de la tabla de particiones — no se usa.

        h.nRawData = b.getInt(0xB4);
        h.rawDataTableOff = b.getLong(0xB8);
        // 0xC0: tamaño en bytes de cada entrada de la tabla raw_data — siempre 24, no se usa.

        h.nGroups = b.getInt(0xC4);
        h.groupTableOff = b.getLong(0xC8);
        h.groupEntrySize = h.isWia ? 8 : 12;
        // 0xD0: tamaño en bytes de cada entrada de grupo — ya lo sabemos por isWia, no se usa.

        h.comprDataLen = disc[0xD4] & 0xFF;
        b.position(0xD5);
        b.get(h.comprData); // 0xD5..0xDC

        if (h.discType != 1 && h.discType != 2) {
            throw new RvzUnpackException("Tipo de disco desconocido en el archivo (" + h.discType + ").");
        }
        return h;
    }

    boolean isWiiDisc() {
        return discType == 2;
    }
}
