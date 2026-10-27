package com.wbm.core.rvz;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Cifra un clúster de 0x8000 bytes de un disco de Wii, tal como lo requiere el formato real del
 * disco: el bloque de hash (0x400 bytes) se cifra con AES-128-CBC e IV en cero; los datos de
 * usuario (0x7C00 bytes) se cifran con el mismo algoritmo, pero con el IV tomado de los bytes
 * 0x3D0-0x3DF del bloque de hash YA CIFRADO.
 *
 * La clave de la partición (part_key) la entrega el propio contenedor RVZ/WIA ya descifrada;
 * esta clase no necesita ni usa la clave común de la Wii.
 */
final class WiiCrypto {
    private WiiCrypto() {}

    private static final byte[] ZERO_IV = new byte[16];

    static void encryptClusterInPlace(byte[] block, int clusterOffset, byte[] partKey) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        SecretKeySpec key = new SecretKeySpec(partKey, "AES");

        cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(ZERO_IV));
        byte[] hashPlain = Arrays.copyOfRange(block, clusterOffset, clusterOffset + WiiHashTree.HASH_BLOCK_SIZE);
        byte[] hashCipher = cipher.doFinal(hashPlain);
        System.arraycopy(hashCipher, 0, block, clusterOffset, WiiHashTree.HASH_BLOCK_SIZE);

        byte[] iv = Arrays.copyOfRange(hashCipher, 0x3D0, 0x3E0);
        cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
        byte[] dataPlain = Arrays.copyOfRange(block, clusterOffset + WiiHashTree.HASH_BLOCK_SIZE,
                clusterOffset + WiiHashTree.CLUSTER_SIZE);
        byte[] dataCipher = cipher.doFinal(dataPlain);
        System.arraycopy(dataCipher, 0, block, clusterOffset + WiiHashTree.HASH_BLOCK_SIZE, dataCipher.length);
    }
}
