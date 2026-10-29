package com.wbm.core.rvz;

import io.airlift.compress.zstd.ZstdDecompressor;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.tukaani.xz.LZMA2InputStream;
import org.tukaani.xz.LZMAInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Descompresión de un grupo, según el tipo indicado en la cabecera del disco RVZ/WIA. */
final class GroupDecompressor {
    private GroupDecompressor() {}

    static final int NONE = 0;
    static final int PURGE = 1;
    static final int BZIP2 = 2;
    static final int LZMA = 3;
    static final int LZMA2 = 4;
    static final int ZSTD = 5;

    /**
     * @param compression   uno de los valores NONE..ZSTD
     * @param comprDataLen  bytes usados de comprData (para LZMA/LZMA2)
     * @param comprData     7 bytes de "datos del compresor" de la cabecera del disco
     */
    /**
     * @param maxOutputLen  cota superior del tamaño descomprimido (hace falta para la API de
     *                      zstd; los demás formatos se autodelimitan y la ignoran)
     */
    static byte[] decompress(int compression, byte[] src, int maxOutputLen, int comprDataLen, byte[] comprData)
            throws RvzUnpackException {
        try {
            switch (compression) {
                case NONE:
                    return src;
                case ZSTD: {
                    // Java puro (aircompressor), no JNI: ver la nota en build.gradle sobre por qué
                    // no se usa zstd-jni aquí.
                    byte[] out = new byte[maxOutputLen];
                    int written = new ZstdDecompressor().decompress(src, 0, src.length, out, 0, out.length);
                    return written == out.length ? out : java.util.Arrays.copyOf(out, written);
                }
                case BZIP2:
                    return readAll(new BZip2CompressorInputStream(new ByteArrayInputStream(src)));
                case LZMA: {
                    byte propsByte = comprDataLen > 0 ? comprData[0] : 0;
                    int dictSize = decodeLzmaDictSize(comprDataLen, comprData);
                    LZMAInputStream in = new LZMAInputStream(new ByteArrayInputStream(src), -1,
                            propsByte, dictSize);
                    return readAll(in);
                }
                case LZMA2: {
                    int dictSize = comprDataLen > 0 ? decodeLzma2DictSize(comprData[0] & 0xFF) : (1 << 20);
                    LZMA2InputStream in = new LZMA2InputStream(new ByteArrayInputStream(src), dictSize);
                    return readAll(in);
                }
                case PURGE:
                    throw new RvzUnpackException("Compresión PURGE solo existe en WIA, no en RVZ");
                default:
                    throw new RvzUnpackException("Tipo de compresión RVZ desconocido: " + compression);
            }
        } catch (RvzUnpackException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new RvzUnpackException("Error al descomprimir un grupo (tipo " + compression + ")", e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(65536);
            byte[] buf = new byte[65536];
            int n;
            while ((n = s.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** LZMA1 (7z-style): bytes 1..4 de comprData son el tamaño de diccionario en little-endian. */
    private static int decodeLzmaDictSize(int len, byte[] d) {
        if (len < 5) return 1 << 20;
        return (d[1] & 0xFF) | ((d[2] & 0xFF) << 8) | ((d[3] & 0xFF) << 16) | ((d[4] & 0xFF) << 24);
    }

    /** LZMA2: un byte que codifica el tamaño de diccionario (igual que en el contenedor .xz). */
    private static int decodeLzma2DictSize(int propsByte) {
        if (propsByte > 40) throw new IllegalArgumentException("Propiedad LZMA2 fuera de rango");
        if (propsByte == 40) return 0xFFFFFFFF;
        int size = (2 | (propsByte & 1)) << (propsByte / 2 + 11);
        return size;
    }
}
