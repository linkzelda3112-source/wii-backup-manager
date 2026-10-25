package com.wbm.core;

/**
 * Constantes y cálculos del formato WBFS (archivo con un solo disco).
 *
 * Disposición del archivo:
 *   bloque 0 (1 << blockShift bytes):
 *     +0x000  magic "WBFS"           (4 bytes)
 *     +0x004  n_hd_sec               (u32 BE, tamaño total / tamaño de sector)
 *     +0x008  hd_sec_sz_s            (u8, log2 del sector; 9 = 512 bytes)
 *     +0x009  wbfs_sec_sz_s          (u8, log2 del bloque WBFS)
 *     +0x00C  disc_table[]           (1 byte por disco; el disco 0 vale 1)
 *     +sector info del disco 0:      0x100 bytes copia de la cabecera del disco
 *                                    + tabla wlba de u16 BE (bloque lógico -> bloque físico, 0 = vacío)
 *   bloques 1..N: datos del disco, en el orden en que se escribieron.
 * La tabla wlba siempre tiene sitio para un disco de doble capa (143432*2 sectores Wii de 32 KiB).
 */
public final class WbfsFormat {
    private WbfsFormat() {}

    public static final int MAGIC = 0x57424653; // 'W' 'B' 'F' 'S'
    public static final int HD_SEC_SHIFT = 9;
    public static final int HD_SEC_SIZE = 1 << HD_SEC_SHIFT;
    public static final int WII_SEC_SHIFT = 15;
    public static final int WII_SEC_SIZE = 1 << WII_SEC_SHIFT;
    public static final int DISC_HEADER_COPY = 0x100;
    public static final long WII_SECTORS_PER_DISC = 143432L * 2;

    /** Tamaño estándar de una imagen ISO de Wii de una capa (4.699.979.776 bytes). */
    public static final long SL_ISO_SIZE = 143432L * WII_SEC_SIZE;
    /** Tamaño estándar de una imagen ISO de Wii de doble capa (8.511.160.320 bytes). */
    public static final long DL_ISO_SIZE = 259740L * WII_SEC_SIZE;

    /** Tamaño máximo de parte para FAT32: 4 GiB menos 32 KiB. */
    public static final long FAT32_SPLIT_SIZE = 0xFFFF8000L;

    public static int tableEntries(int blockShift) {
        return (int) (WII_SECTORS_PER_DISC >> (blockShift - WII_SEC_SHIFT));
    }

    public static int align(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    /** Tamaño ocupado por la información de un disco (cabecera + tabla), alineado al sector. */
    public static int discInfoSize(int blockShift, int hdSecSize) {
        return align(DISC_HEADER_COPY + tableEntries(blockShift) * 2, hdSecSize);
    }

    /** true si cabecera + info del disco caben en el bloque 0 con ese tamaño de bloque. */
    public static boolean shiftFits(int blockShift) {
        return blockShift >= WII_SEC_SHIFT && blockShift <= 28
                && HD_SEC_SIZE + discInfoSize(blockShift, HD_SEC_SIZE) <= (1L << blockShift);
    }

    /** Elige el menor tamaño de bloque válido para una imagen de ese tamaño. */
    public static int chooseShift(long isoSize) {
        for (int s = WII_SEC_SHIFT; s <= 28; s++) {
            if (!shiftFits(s)) continue;
            long blocks = (isoSize + (1L << s) - 1) >> s;
            if (blocks <= tableEntries(s) && blocks + 1 <= 0xFFFF) return s;
        }
        throw new IllegalArgumentException("Imagen demasiado grande para WBFS");
    }

    /** Tamaño al que se rellena una ISO reconstruida para que coincida con un volcado estándar. */
    public static long padTarget(long logicalSize) {
        if (logicalSize <= SL_ISO_SIZE) return SL_ISO_SIZE;
        if (logicalSize <= DL_ISO_SIZE) return DL_ISO_SIZE;
        return logicalSize;
    }
}
