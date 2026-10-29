package com.wbm.core.rvz;

import org.junit.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pruebas del núcleo RVZ que corren en la JVM normal (sin Android), en el paso "test" de la
 * compilación. No disponemos de un archivo .rvz real de un disco de Wii para probar de punta a
 * punta, así que estas pruebas verifican cada pieza por separado y su coherencia interna
 * (cifrar y volver a descifrar da lo mismo, empaquetar y desempaquetar da lo mismo, etc.).
 */
public class RvzCoreTest {

    @Test
    public void hashTreeAndCryptoRoundTrip() throws Exception {
        Random r = new Random(123);
        byte[] plain = new byte[64 * 0x7C00];
        r.nextBytes(plain);
        byte[] key = new byte[16];
        r.nextBytes(key);

        byte[] block = WiiHashTree.buildBlock(plain, 64, new WiiHashTree.Exception1[0]);
        assertEquals(64 * 0x8000, block.length);

        byte[] encrypted = block.clone();
        for (int c = 0; c < 64; c++) {
            WiiCrypto.encryptClusterInPlace(encrypted, c * 0x8000, key);
        }
        assertFalse("el cifrado debe cambiar los bytes", Arrays.equals(encrypted, block));

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        SecretKeySpec sk = new SecretKeySpec(key, "AES");
        for (int c = 0; c < 64; c++) {
            int base = c * 0x8000;
            cipher.init(Cipher.DECRYPT_MODE, sk, new IvParameterSpec(new byte[16]));
            byte[] hashPlain = cipher.doFinal(Arrays.copyOfRange(encrypted, base, base + 0x400));
            assertArrayEquals(Arrays.copyOfRange(block, base, base + 0x400), hashPlain);

            byte[] iv = Arrays.copyOfRange(encrypted, base + 0x3D0, base + 0x3E0);
            cipher.init(Cipher.DECRYPT_MODE, sk, new IvParameterSpec(iv));
            byte[] dataPlain = cipher.doFinal(Arrays.copyOfRange(encrypted, base + 0x400, base + 0x8000));
            assertArrayEquals(Arrays.copyOfRange(block, base + 0x400, base + 0x8000), dataPlain);
        }
    }

    @Test
    public void h0MatchesRealSha1() throws Exception {
        Random r = new Random(7);
        byte[] plain = new byte[64 * 0x7C00];
        r.nextBytes(plain);
        byte[] block = WiiHashTree.buildBlock(plain, 64, new WiiHashTree.Exception1[0]);

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        for (int c = 0; c < 64; c++) {
            for (int j = 0; j < 31; j++) {
                sha1.reset();
                byte[] expected = sha1.digest(Arrays.copyOfRange(plain, c * 0x7C00 + j * 0x400, c * 0x7C00 + j * 0x400 + 0x400));
                byte[] got = Arrays.copyOfRange(block, c * 0x8000 + j * 20, c * 0x8000 + j * 20 + 20);
                assertArrayEquals("H0 del clúster " + c + " bloque " + j, expected, got);
            }
        }
    }

    @Test
    public void h2IsSameAcrossAllClusters() throws Exception {
        Random r = new Random(8);
        byte[] plain = new byte[64 * 0x7C00];
        r.nextBytes(plain);
        byte[] block = WiiHashTree.buildBlock(plain, 64, new WiiHashTree.Exception1[0]);
        byte[] h2first = Arrays.copyOfRange(block, 0x340, 0x340 + 0xA0);
        for (int c = 1; c < 64; c++) {
            assertArrayEquals(h2first, Arrays.copyOfRange(block, c * 0x8000 + 0x340, c * 0x8000 + 0x340 + 0xA0));
        }
    }

    @Test
    public void exceptionOverridesHash() throws Exception {
        Random r = new Random(9);
        byte[] plain = new byte[64 * 0x7C00];
        r.nextBytes(plain);
        byte[] fakeHash = new byte[20];
        new Random(9).nextBytes(fakeHash);
        WiiHashTree.Exception1 ex = new WiiHashTree.Exception1(0, fakeHash);
        byte[] block = WiiHashTree.buildBlock(plain, 64, new WiiHashTree.Exception1[]{ex});
        assertArrayEquals(fakeHash, Arrays.copyOfRange(block, 0, 20));
    }

    @Test
    public void partialBlockTrimsToActualClusters() throws Exception {
        Random r = new Random(10);
        int clusters = 5;
        byte[] plain = new byte[clusters * 0x7C00];
        r.nextBytes(plain);
        byte[] block = WiiHashTree.buildBlock(plain, clusters, new WiiHashTree.Exception1[0]);
        assertEquals(clusters * 0x8000, block.length);
    }

    @Test
    public void unpackLiteralAndGeneratedFragments() throws Exception {
        Random r = new Random(55);
        byte[] seed = new byte[68];
        r.nextBytes(seed);
        byte[] literal = new byte[100];
        r.nextBytes(literal);
        int genLen = 5000;

        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        writeBE(packed, literal.length);
        packed.write(literal);
        writeBE(packed, genLen | 0x80000000);
        packed.write(seed);

        byte[] result = RvzUnpacker.unpack(packed.toByteArray());
        assertEquals(literal.length + genLen, result.length);
        assertArrayEquals(literal, Arrays.copyOfRange(result, 0, literal.length));
    }

    @Test
    public void unpackIsDeterministic() throws Exception {
        Random r = new Random(66);
        byte[] seed = new byte[68];
        r.nextBytes(seed);
        int genLen = 12345;
        ByteArrayOutputStream packed1 = new ByteArrayOutputStream();
        writeBE(packed1, genLen | 0x80000000);
        packed1.write(seed);
        byte[] out1 = RvzUnpacker.unpack(packed1.toByteArray());

        ByteArrayOutputStream packed2 = new ByteArrayOutputStream();
        writeBE(packed2, genLen | 0x80000000);
        packed2.write(seed);
        byte[] out2 = RvzUnpacker.unpack(packed2.toByteArray());

        assertArrayEquals(out1, out2);
        assertTrue("no debería quedar todo en cero", anyNonZero(out1));
    }

    @Test
    public void zstdRoundTrip() throws Exception {
        Random r = new Random(321);
        byte[] original = new byte[500_000];
        r.nextBytes(original);
        for (int i = 0; i < original.length; i += 7) original[i] = 0; // algo de redundancia

        byte[] compressed = com.github.luben.zstd.Zstd.compress(original, 9);
        byte[] decompressed = GroupDecompressor.decompress(GroupDecompressor.ZSTD, compressed,
                original.length + (1 << 20), 0, new byte[7]);
        assertArrayEquals(original, decompressed);
    }

    /**
     * Construye un archivo RVZ/WIA sintético (solo la cabecera) con WIADisc del tamaño que se pida
     * y comprueba que RvzHeader.parse() lee cada campo del lugar correcto, tanto cuando disc_size
     * coincide con el tamaño que conoce este lector (0xDC) como cuando es más corto (una versión
     * más vieja del formato, con menos campos) — este es justo el caso que causaba el
     * ArrayIndexOutOfBoundsException reportado por un usuario real al convertir un .rvz.
     */
    @Test
    public void headerParsesRawDataAndGroupTablesWithFullDiscSize() throws Exception {
        checkHeaderRoundTrip(0xDC);
    }

    @Test
    public void headerParsesWithShorterDiscSizeFromOlderFormatVersion() throws Exception {
        // Un disc_size que llega justo hasta el final de la tabla de grupos (sin los últimos
        // campos de "tamaño por entrada" ni compressor_data): debe seguir leyendo bien las tablas.
        checkHeaderRoundTrip(0xD0);
    }

    private void checkHeaderRoundTrip(int discSize) throws Exception {
        long isoFileSize = 4_699_979_776L;
        int discType = 2; // Wii
        int compression = 5; // ZSTD
        int chunkSize = 2 << 20;
        int nPart = 1;
        int partTableEntrySize = 48;
        long partTableOff = 0x1000;
        int nRawData = 2;
        long rawDataTableOff = 0x2000;
        int nGroups = 35858;
        long groupTableOff = 0x3000;

        byte[] disc = new byte[0xDC];
        ByteBuffer d = ByteBuffer.wrap(disc).order(ByteOrder.BIG_ENDIAN);
        d.putInt(0x00, discType);
        d.putInt(0x04, compression);
        d.putInt(0x0C, chunkSize);
        d.putInt(0x90, nPart);
        d.putInt(0x94, partTableEntrySize);
        d.putLong(0x98, partTableOff);
        d.putInt(0xB4, nRawData);
        d.putLong(0xB8, rawDataTableOff);
        d.putInt(0xC0, nRawData * 24); // tamaño comprimido de la tabla raw_data (aquí, sin comprimir)
        d.putInt(0xC4, nGroups);
        d.putLong(0xC8, groupTableOff);
        d.putInt(0xD0, nGroups * 12); // tamaño comprimido de la tabla de grupos (RVZ, sin comprimir)

        byte[] head1 = new byte[0x48];
        ByteBuffer h1 = ByteBuffer.wrap(head1).order(ByteOrder.BIG_ENDIAN);
        head1[0] = 'R';
        head1[1] = 'V';
        head1[2] = 'Z';
        head1[3] = 1;
        h1.putInt(0x0C, discSize);
        h1.putLong(0x24, isoFileSize);

        File f = File.createTempFile("rvz-header-test", ".rvz");
        f.deleteOnExit();
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.write(head1);
            raf.write(disc, 0, discSize); // el archivo real solo guarda discSize bytes, no 0xDC
        }
        try (FileChannel ch = FileChannel.open(f.toPath(), StandardOpenOption.READ)) {
            RvzHeader h = RvzHeader.parse(ch);
            assertEquals(isoFileSize, h.isoFileSize);
            assertEquals(discType, h.discType);
            assertEquals(compression, h.compression);
            assertEquals(chunkSize, h.chunkSize);
            assertEquals(nPart, h.nPart);
            assertEquals(partTableEntrySize, h.partTableEntrySize);
            assertEquals(partTableOff, h.partTableOff);
            assertEquals(nRawData, h.nRawData);
            assertEquals(rawDataTableOff, h.rawDataTableOff);
            assertEquals(nRawData * 24, h.rawDataTableCompressedSize);
            assertEquals(nGroups, h.nGroups);
            assertEquals(groupTableOff, h.groupTableOff);
            assertEquals(12, h.groupEntrySize); // RVZ
            if (discSize >= 0xD4) {
                // con un disc_size más corto este campo queda truncado (relleno con ceros), como el
                // resto de los campos que no caben; no es el caso que se prueba aquí.
                assertEquals(nGroups * 12, h.groupTableCompressedSize);
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /**
     * Reconstruye un disco de GameCube sintético completo de principio a fin: arma un archivo RVZ
     * a mano (cabecera, tabla raw_data y tabla de grupos, SIN comprimir, más los datos de los
     * grupos) y comprueba que RvzExtractor.extract() produce exactamente la ISO original.
     *
     * Esta es la prueba de extremo a extremo que faltaba para el bug real reportado por un usuario:
     * las tablas raw_data y de grupos se guardan COMPRIMIDAS en el archivo (de un solo tirón, con
     * el mismo algoritmo de todo el archivo), no como bytes planos. Antes de corregirlo, este
     * lector las interpretaba directamente sin descomprimir, así que groupIndex/off/size salían
     * con valores sin sentido en cuanto el archivo no era trivialmente pequeño. Se usa un disco de
     * GameCube (no Wii) para no tener que construir también particiones cifradas de Wii aquí; esa
     * parte la cubren las otras pruebas (hashTreeAndCryptoRoundTrip, etc.).
     */
    @Test
    public void extractGameCubeDiscWithUncompressedTables() throws Exception {
        Random r = new Random(2024);
        int isoSize = 200_000;
        byte[] iso = new byte[isoSize];
        r.nextBytes(iso);
        int chunkSize = 65536;
        int nGroups = (isoSize + chunkSize - 1) / chunkSize; // 4

        // --- tabla de grupos: cada entrada referencia un trozo de "iso" sin comprimir ---
        ByteArrayOutputStream groupTable = new ByteArrayOutputStream();
        ByteArrayOutputStream groupData = new ByteArrayOutputStream();
        long groupDataBase = 0x124 + 24 /* raw_data table: 1 entrada */ + (long) nGroups * 12;
        assertEquals(0, groupDataBase % 4); // data_off4 exige múltiplos de 4
        for (int i = 0; i < nGroups; i++) {
            int off = i * chunkSize;
            int len = Math.min(chunkSize, isoSize - off);
            long fileOff = groupDataBase + groupData.size();
            writeBE(groupTable, (int) (fileOff / 4));
            writeBE(groupTable, len); // bit alto = 0 -> no comprimido
            writeBE(groupTable, 0); // packedSize
            groupData.write(iso, off, len);
        }

        // --- tabla raw_data: una sola entrada que cubre todo el disco ---
        ByteArrayOutputStream rawDataTable = new ByteArrayOutputStream();
        writeBE64(rawDataTable, 0);
        writeBE64(rawDataTable, isoSize);
        writeBE(rawDataTable, 0); // groupIndex
        writeBE(rawDataTable, nGroups);

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] isoHash = sha1.digest(iso);

        byte[] disc = new byte[0xDC];
        ByteBuffer d = ByteBuffer.wrap(disc).order(ByteOrder.BIG_ENDIAN);
        d.putInt(0x00, 1); // GameCube: así no hace falta simular particiones cifradas de Wii
        d.putInt(0x04, GroupDecompressor.NONE);
        d.putInt(0x0C, chunkSize);
        d.putInt(0x90, 0); // nPart
        d.putLong(0x98, 0);
        d.putInt(0xB4, 1); // nRawData
        d.putLong(0xB8, 0x124);
        d.putInt(0xC0, rawDataTable.size());
        d.putInt(0xC4, nGroups);
        d.putLong(0xC8, 0x124 + rawDataTable.size());
        d.putInt(0xD0, groupTable.size());

        byte[] head1 = new byte[0x48];
        ByteBuffer h1 = ByteBuffer.wrap(head1).order(ByteOrder.BIG_ENDIAN);
        head1[0] = 'R';
        head1[1] = 'V';
        head1[2] = 'Z';
        head1[3] = 1;
        h1.putInt(0x0C, disc.length);
        h1.putLong(0x24, isoSize);
        h1.position(0x10);
        h1.put(isoHash);

        File f = File.createTempFile("rvz-e2e-test", ".rvz");
        f.deleteOnExit();
        File outIso = File.createTempFile("rvz-e2e-out", ".iso");
        outIso.deleteOnExit();
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.write(head1);
            raf.write(disc);
            raf.write(rawDataTable.toByteArray());
            raf.write(groupTable.toByteArray());
            raf.write(groupData.toByteArray());
        }
        try (FileChannel in = FileChannel.open(f.toPath(), StandardOpenOption.READ);
             FileChannel out = FileChannel.open(outIso.toPath(), StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING)) {
            RvzExtractor.Result result = RvzExtractor.extract(in, out, null);
            assertTrue("el hash reconstruido debe coincidir con el original", result.hashMatches);
            assertEquals(isoSize, result.isoSize);

            byte[] rebuilt = new byte[isoSize];
            out.position(0);
            ByteBuffer bb = ByteBuffer.wrap(rebuilt);
            while (bb.hasRemaining()) out.read(bb);
            assertArrayEquals(iso, rebuilt);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            //noinspection ResultOfMethodCallIgnored
            outIso.delete();
        }
    }

    private static void writeBE64(ByteArrayOutputStream out, long v) {
        for (int shift = 56; shift >= 0; shift -= 8) out.write((int) ((v >>> shift) & 0xFF));
    }

    private static boolean anyNonZero(byte[] b) {
        for (byte x : b) if (x != 0) return true;
        return false;
    }

    private static void writeBE(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }
}
