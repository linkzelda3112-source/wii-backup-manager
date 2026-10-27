package com.wbm.core.rvz;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reconstruye el flujo "empaquetado" de RVZ: una secuencia de fragmentos que son o bien datos
 * literales, o bien una instrucción para generar datos de relleno (ver {@link JunkGenerator}).
 * Cada fragmento empieza con un entero de 32 bits (big-endian): si el bit más alto está
 * apagado, los siguientes "tamaño" bytes son literales; si está encendido, le siguen 68 bytes
 * de semilla y luego se generan "tamaño" bytes de relleno.
 */
final class RvzUnpacker {
    private RvzUnpacker() {}

    /**
     * Consume TODO el arreglo packed (cada fragmento declara su propio tamaño; se repite hasta
     * agotar la entrada) y devuelve el contenido reconstruido, de tamaño variable.
     */
    static byte[] unpack(byte[] packed) throws RvzUnpackException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(packed.length * 2 + 64);
        ByteBuffer in = ByteBuffer.wrap(packed).order(ByteOrder.BIG_ENDIAN);
        long pos = 0;
        byte[] tmp = new byte[8192];
        while (in.remaining() > 0) {
            if (in.remaining() < 4) {
                throw new RvzUnpackException("Flujo RVZ empaquetado truncado (cabecera de fragmento)");
            }
            int sizeWord = in.getInt();
            boolean generated = (sizeWord & 0x80000000) != 0;
            int size = sizeWord & 0x7FFFFFFF;
            if (size < 0) throw new RvzUnpackException("Fragmento RVZ con tamaño inválido: " + size);
            if (!generated) {
                if (in.remaining() < size) throw new RvzUnpackException("Flujo RVZ empaquetado truncado (literal)");
                int left = size;
                while (left > 0) {
                    int n = Math.min(left, tmp.length);
                    in.get(tmp, 0, n);
                    out.write(tmp, 0, n);
                    left -= n;
                }
            } else {
                if (in.remaining() < 68) throw new RvzUnpackException("Flujo RVZ empaquetado truncado (semilla)");
                byte[] seed = new byte[68];
                in.get(seed);
                JunkGenerator gen = new JunkGenerator(seed);
                long align = pos % 0x8000L;
                if (align != 0) gen.skip(align);
                int left = size;
                while (left > 0) {
                    int n = Math.min(left, tmp.length);
                    gen.fill(tmp, 0, n);
                    out.write(tmp, 0, n);
                    left -= n;
                }
            }
            pos += size;
        }
        return out.toByteArray();
    }
}
