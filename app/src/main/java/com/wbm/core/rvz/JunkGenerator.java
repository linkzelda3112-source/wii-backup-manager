package com.wbm.core.rvz;

/**
 * Generador de los datos de "basura" (padding de fabricación) que RVZ no guarda tal cual, sino
 * que reconstruye con este algoritmo (documentado en la especificación WIA/RVZ de Dolphin):
 * un generador de Fibonacci retardado con una semilla de 17 palabras de 32 bits (68 bytes) y
 * un búfer circular de 521 palabras.
 *
 *   V(i) = (V(i-17) << 23) ^ (V(i-16) >>> 9) ^ V(i-1)      para i >= 17
 *
 * Cada palabra de 32 bits produce 4 bytes de salida, tomados como (>>24, >>18, >>8, >>0).
 */
final class JunkGenerator {
    private static final int SIZE = 521;
    private static final int LAG = 17;

    private final int[] buf = new int[SIZE];
    private long index = LAG;
    private final byte[] wordBytes = new byte[4];
    private int wordBytesLeft = 0;

    JunkGenerator(byte[] seed68) {
        if (seed68.length != 68) throw new IllegalArgumentException("La semilla debe medir 68 bytes");
        for (int k = 0; k < LAG; k++) {
            buf[k] = ((seed68[k * 4] & 0xFF) << 24) | ((seed68[k * 4 + 1] & 0xFF) << 16)
                    | ((seed68[k * 4 + 2] & 0xFF) << 8) | (seed68[k * 4 + 3] & 0xFF);
        }
    }

    private int nextWord() {
        int a = buf[(int) ((index - LAG) % SIZE)];
        int b = buf[(int) ((index - LAG + 1) % SIZE)];
        int c = buf[(int) ((index - 1) % SIZE)];
        int v = (a << 23) ^ (b >>> 9) ^ c;
        buf[(int) (index % SIZE)] = v;
        index++;
        return v;
    }

    private byte nextByte() {
        if (wordBytesLeft == 0) {
            int w = nextWord();
            wordBytes[0] = (byte) (w >>> 24);
            wordBytes[1] = (byte) (w >>> 18);
            wordBytes[2] = (byte) (w >>> 8);
            wordBytes[3] = (byte) w;
            wordBytesLeft = 4;
        }
        byte b = wordBytes[4 - wordBytesLeft];
        wordBytesLeft--;
        return b;
    }

    /** Descarta n bytes de la secuencia (ajuste de alineación al límite de sector). */
    void skip(long n) {
        for (long i = 0; i < n; i++) nextByte();
    }

    /** Genera exactamente len bytes a partir de la posición actual del generador. */
    void fill(byte[] out, int off, int len) {
        for (int i = 0; i < len; i++) out[off + i] = nextByte();
    }
}
