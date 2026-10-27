package com.wbm.core.rvz;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Reconstruye un bloque de 64 clústeres (2 MiB en el disco) del árbol de hashes de un disco de
 * Wii a partir de los datos de usuario sin cifrar, tal como lo describe la wiki técnica del
 * formato de disco de Wii:
 *
 *   clúster (0x8000 B) = H0[31] (0x26C) + relleno (0x14) + H1[8] (0xA0) + relleno (0x20)
 *                        + H2[8] (0xA0) + relleno (0x20) + datos de usuario (0x7C00, en 31 bloques de 0x400)
 *
 * H0[j] = SHA-1 del bloque de datos j (0x400 bytes) del clúster.
 * H1[c] = SHA-1 de la tabla H0 completa (0x26C bytes) del clúster c dentro de su subgrupo de 8.
 * H2[s] = SHA-1 de la tabla H1 completa (0xA0 bytes) del subgrupo s dentro del grupo de 64.
 *
 * Las "excepciones" permiten sustituir hashes concretos que no coinciden con los recalculados
 * (algunos discos originales los traen así, como medida de protección); se aplican después de
 * calcular los hashes y antes de cifrar.
 */
final class WiiHashTree {
    static final int CLUSTER_SIZE = 0x8000;
    static final int CLUSTER_DATA_SIZE = 0x7C00;
    static final int CLUSTERS_PER_GROUP = 64;
    static final int CLUSTERS_PER_SUBGROUP = 8;
    static final int H0_SIZE = 0x26C;
    static final int H1_OFFSET = 0x280, H1_SIZE = 0xA0;
    static final int H2_OFFSET = 0x340, H2_SIZE = 0xA0;
    static final int HASH_BLOCK_SIZE = 0x400;
    static final int SHA1_LEN = 20;

    private WiiHashTree() {}

    static final class Exception1 {
        final int offset;
        final byte[] hash;

        Exception1(int offset, byte[] hash) {
            this.offset = offset;
            this.hash = hash;
        }
    }

    /**
     * @param plainDataIn   actualClusters * 0x7C00 bytes de datos de usuario sin cifrar (si hay
     *                      menos de 64 clústeres, p. ej. el último bloque de una partición, se
     *                      completa con ceros para poder calcular igualmente H1/H2)
     * @param actualClusters cuántos de los 64 clústeres son datos reales (1-64)
     * @param exceptions    lista de sustituciones de hash para este bloque (puede ser vacía)
     * @return actualClusters * 0x8000 bytes: cada clúster con su hash y datos, listo para cifrar
     */
    static byte[] buildBlock(byte[] plainDataIn, int actualClusters, Exception1[] exceptions)
            throws NoSuchAlgorithmException {
        if (actualClusters < 1 || actualClusters > CLUSTERS_PER_GROUP) {
            throw new IllegalArgumentException("actualClusters fuera de rango: " + actualClusters);
        }
        int expected = actualClusters * CLUSTER_DATA_SIZE;
        if (plainDataIn.length != expected) {
            throw new IllegalArgumentException("Se esperaban " + expected + " bytes de datos, llegaron "
                    + plainDataIn.length);
        }
        byte[] plainData = plainDataIn;
        if (actualClusters != CLUSTERS_PER_GROUP) {
            plainData = new byte[CLUSTERS_PER_GROUP * CLUSTER_DATA_SIZE];
            System.arraycopy(plainDataIn, 0, plainData, 0, plainDataIn.length);
        }
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] block = new byte[CLUSTERS_PER_GROUP * CLUSTER_SIZE];

        // 1) copiar los datos de usuario y calcular H0 de cada clúster
        for (int c = 0; c < CLUSTERS_PER_GROUP; c++) {
            int dataSrc = c * CLUSTER_DATA_SIZE;
            int clusterBase = c * CLUSTER_SIZE;
            System.arraycopy(plainData, dataSrc, block, clusterBase + HASH_BLOCK_SIZE, CLUSTER_DATA_SIZE);
            for (int j = 0; j < 31; j++) {
                sha1.reset();
                sha1.update(plainData, dataSrc + j * HASH_BLOCK_SIZE, HASH_BLOCK_SIZE);
                byte[] h = sha1.digest();
                System.arraycopy(h, 0, block, clusterBase + j * SHA1_LEN, SHA1_LEN);
            }
        }

        // 2) H1: hash de la tabla H0 de cada clúster, copiado en todos los clústeres de su subgrupo
        for (int sub = 0; sub < CLUSTERS_PER_GROUP / CLUSTERS_PER_SUBGROUP; sub++) {
            byte[] h1Table = new byte[H1_SIZE];
            for (int k = 0; k < CLUSTERS_PER_SUBGROUP; k++) {
                int c = sub * CLUSTERS_PER_SUBGROUP + k;
                sha1.reset();
                sha1.update(block, c * CLUSTER_SIZE, H0_SIZE);
                byte[] h = sha1.digest();
                System.arraycopy(h, 0, h1Table, k * SHA1_LEN, SHA1_LEN);
            }
            for (int k = 0; k < CLUSTERS_PER_SUBGROUP; k++) {
                int c = sub * CLUSTERS_PER_SUBGROUP + k;
                System.arraycopy(h1Table, 0, block, c * CLUSTER_SIZE + H1_OFFSET, H1_SIZE);
            }
        }

        // 3) H2: hash de la tabla H1 de cada subgrupo, copiado en todos los 64 clústeres del grupo
        byte[] h2Table = new byte[H2_SIZE];
        for (int sub = 0; sub < CLUSTERS_PER_GROUP / CLUSTERS_PER_SUBGROUP; sub++) {
            int c = sub * CLUSTERS_PER_SUBGROUP; // cualquier clúster del subgrupo sirve, H1 es igual en los 8
            sha1.reset();
            sha1.update(block, c * CLUSTER_SIZE + H1_OFFSET, H1_SIZE);
            byte[] h = sha1.digest();
            System.arraycopy(h, 0, h2Table, sub * SHA1_LEN, SHA1_LEN);
        }
        for (int c = 0; c < CLUSTERS_PER_GROUP; c++) {
            System.arraycopy(h2Table, 0, block, c * CLUSTER_SIZE + H2_OFFSET, H2_SIZE);
        }

        // 4) aplicar las excepciones (sustituciones puntuales de 20 bytes)
        if (exceptions != null) {
            for (Exception1 ex : exceptions) {
                int compacted = ex.offset & 0xFFFF;
                int clusterIdx = compacted / HASH_BLOCK_SIZE;
                int within = compacted % HASH_BLOCK_SIZE;
                if (clusterIdx >= CLUSTERS_PER_GROUP) {
                    throw new IllegalArgumentException("Excepción fuera de rango: " + ex.offset);
                }
                int real = clusterIdx * CLUSTER_SIZE + within;
                if (within + SHA1_LEN > HASH_BLOCK_SIZE || real + SHA1_LEN > block.length) {
                    throw new IllegalArgumentException("Excepción con desplazamiento inválido: " + ex.offset);
                }
                System.arraycopy(ex.hash, 0, block, real, SHA1_LEN);
            }
        }

        if (actualClusters == CLUSTERS_PER_GROUP) return block;
        byte[] trimmed = new byte[actualClusters * CLUSTER_SIZE];
        System.arraycopy(block, 0, trimmed, 0, trimmed.length);
        return trimmed;
    }

    /** H2 de cualquier clúster del bloque (igual en los 64); usado por el llamador para construir H3. */
    static byte[] h2Of(byte[] block) {
        byte[] out = new byte[H2_SIZE];
        System.arraycopy(block, H2_OFFSET, out, 0, H2_SIZE);
        return out;
    }
}
