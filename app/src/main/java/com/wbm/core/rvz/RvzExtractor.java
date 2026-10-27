package com.wbm.core.rvz;

import com.wbm.core.CancelledException;
import com.wbm.core.Progress;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reconstruye, a partir de un archivo RVZ (o WIA) de un disco de Wii o GameCube, una imagen ISO
 * byte a byte idéntica a la original. Ver docs/WiaAndRvz.md del proyecto Dolphin para el formato
 * del contenedor, y la wiki técnica del disco de Wii para el árbol de hashes y el cifrado.
 *
 * El resultado se autoverifica: al terminar se calcula el SHA-1 de toda la ISO reconstruida y se
 * compara con el que guarda la cabecera del RVZ/WIA (que es el SHA-1 de la ISO original).
 */
public final class RvzExtractor {
    private RvzExtractor() {}

    public static final class Result {
        public final boolean hashMatches;
        public final long isoSize;
        public final int exceptionsApplied;

        Result(boolean hashMatches, long isoSize, int exceptionsApplied) {
            this.hashMatches = hashMatches;
            this.isoSize = isoSize;
            this.exceptionsApplied = exceptionsApplied;
        }
    }

    private static final class GroupEntry {
        long dataOff; // ya multiplicado por 4
        int dataSize; // con el bit de "comprimido" ya separado
        boolean compressed;
        int packedSize; // 0 = no empaquetado (solo RVZ; en WIA siempre 0)
    }

    private static final class RawDataEntry {
        long off, size;
        int groupIndex, nGroups;
    }

    private static final class PartEntry {
        byte[] key = new byte[16];
        long[] firstSector = new long[2];
        long[] nSectors = new long[2];
        int[] groupIndex = new int[2];
        int[] nGroups = new int[2];
    }

    public static Result extract(FileChannel in, FileChannel out, Progress progress) throws IOException {
        RvzHeader h = RvzHeader.parse(in);

        GroupEntry[] groups = readGroups(in, h);
        RawDataEntry[] rawData = readRawData(in, h);
        PartEntry[] parts = h.isWiiDisc() ? readParts(in, h) : new PartEntry[0];

        long totalWork = h.isoFileSize;
        long[] done = {0};
        int[] exceptionsApplied = {0};

        for (RawDataEntry rd : rawData) {
            writeRawDataEntry(in, out, h, groups, rd, progress, done, totalWork);
        }

        if (h.isWiiDisc() && parts.length > 0) {
            PartitionLocation[] locations = readPartitionTable(out, h, parts.length);
            for (int i = 0; i < parts.length && i < locations.length; i++) {
                exceptionsApplied[0] += writePartition(in, out, h, groups, parts[i], locations[i], progress, done, totalWork);
            }
        }

        boolean matches = verifyHash(out, h);
        return new Result(matches, h.isoFileSize, exceptionsApplied[0]);
    }

    // ---- lectura de las tablas del contenedor ----

    private static GroupEntry[] readGroups(FileChannel in, RvzHeader h) throws IOException {
        GroupEntry[] arr = new GroupEntry[h.nGroups];
        byte[] raw = new byte[h.nGroups * h.groupEntrySize];
        IoUtil.readFully(in, h.groupTableOff, raw);
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < h.nGroups; i++) {
            GroupEntry g = new GroupEntry();
            long off4 = b.getInt() & 0xFFFFFFFFL;
            int sizeField = b.getInt();
            g.dataOff = off4 * 4;
            if (h.isWia) {
                g.compressed = true; // en WIA no hay bit de "sin comprimir" por grupo
                g.dataSize = sizeField;
                g.packedSize = 0;
            } else {
                g.compressed = (sizeField & 0x80000000) != 0;
                g.dataSize = sizeField & 0x7FFFFFFF;
                g.packedSize = b.getInt();
            }
            arr[i] = g;
        }
        return arr;
    }

    private static RawDataEntry[] readRawData(FileChannel in, RvzHeader h) throws IOException {
        RawDataEntry[] arr = new RawDataEntry[h.nRawData];
        byte[] raw = new byte[h.nRawData * 24];
        IoUtil.readFully(in, h.rawDataTableOff, raw);
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < h.nRawData; i++) {
            RawDataEntry r = new RawDataEntry();
            r.off = b.getLong();
            r.size = b.getLong();
            r.groupIndex = b.getInt();
            r.nGroups = b.getInt();
            arr[i] = r;
        }
        return arr;
    }

    private static PartEntry[] readParts(FileChannel in, RvzHeader h) throws IOException {
        PartEntry[] arr = new PartEntry[h.nPart];
        byte[] raw = new byte[h.nPart * h.partTableEntrySize];
        IoUtil.readFully(in, h.partTableOff, raw);
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < h.nPart; i++) {
            int base = i * h.partTableEntrySize;
            PartEntry p = new PartEntry();
            System.arraycopy(raw, base, p.key, 0, 16);
            for (int s = 0; s < 2; s++) {
                int o = base + 16 + s * 16;
                p.firstSector[s] = b.getInt(o) & 0xFFFFFFFFL;
                p.nSectors[s] = b.getInt(o + 4) & 0xFFFFFFFFL;
                p.groupIndex[s] = b.getInt(o + 8);
                p.nGroups[s] = b.getInt(o + 12);
            }
            arr[i] = p;
        }
        return arr;
    }

    // ---- decodificación de un grupo ----

    /** Descomprime (y desempaqueta si hace falta) un grupo; "allZeroLen" se usa si el grupo está vacío. */
    private static byte[] decodeGroup(FileChannel in, RvzHeader h, GroupEntry g, int allZeroLen) throws IOException {
        if (g.dataOff == 0 && g.dataSize == 0) {
            return new byte[allZeroLen];
        }
        byte[] stored = new byte[g.dataSize];
        IoUtil.readFully(in, g.dataOff, stored);
        byte[] stream;
        if (g.compressed) {
            int bound = h.chunkSize + (1 << 20);
            stream = GroupDecompressor.decompress(h.compression, stored, bound, h.comprDataLen, h.comprData);
        } else {
            stream = stored;
        }
        if (g.packedSize != 0) {
            return RvzUnpacker.unpack(stream);
        }
        return stream;
    }

    // ---- región "raw" (fuera de cualquier partición) ----

    private static void writeRawDataEntry(FileChannel in, FileChannel out, RvzHeader h, GroupEntry[] groups,
            RawDataEntry rd, Progress progress, long[] done, long totalWork) throws IOException {
        long remaining = rd.size;
        long pos = rd.off;
        for (int i = 0; i < rd.nGroups && remaining > 0; i++) {
            GroupEntry g = groups[rd.groupIndex + i];
            int want = (int) Math.min(h.chunkSize, remaining);
            byte[] data = decodeGroup(in, h, g, want);
            if (data.length != want) {
                throw new RvzUnpackException("Grupo de datos planos con tamaño inesperado: " + data.length
                        + " (se esperaban " + want + ")");
            }
            IoUtil.writeFully(out, pos, data, 0, want);
            pos += want;
            remaining -= want;
            done[0] += want;
            reportProgress(progress, done[0], totalWork);
        }
    }

    // ---- tabla de particiones real del disco (0x40000), ya escrita como datos "raw" ----

    private static final class PartitionLocation {
        long absoluteOffset;
    }

    private static PartitionLocation[] readPartitionTable(FileChannel out, RvzHeader h, int expectedCount) throws IOException {
        byte[] head = new byte[0x20];
        IoUtil.readFully(out, 0x40000, head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN);
        List<PartitionLocation> list = new ArrayList<>();
        for (int g = 0; g < 4; g++) {
            int count = b.getInt(g * 8);
            long tableOff = (b.getInt(g * 8 + 4) & 0xFFFFFFFFL) * 4;
            if (count <= 0) continue;
            byte[] sub = new byte[count * 8];
            IoUtil.readFully(out, tableOff, sub);
            ByteBuffer sb = ByteBuffer.wrap(sub).order(ByteOrder.BIG_ENDIAN);
            for (int i = 0; i < count; i++) {
                PartitionLocation loc = new PartitionLocation();
                loc.absoluteOffset = (sb.getInt(i * 8) & 0xFFFFFFFFL) * 4;
                list.add(loc);
            }
        }
        if (list.size() != expectedCount) {
            throw new RvzUnpackException("La tabla de particiones del disco tiene " + list.size()
                    + " entradas, pero el contenedor declara " + expectedCount + ".");
        }
        return list.toArray(new PartitionLocation[0]);
    }

    // ---- datos cifrados de una partición ----

    private static int writePartition(FileChannel in, FileChannel out, RvzHeader h, GroupEntry[] groups,
            PartEntry part, PartitionLocation loc, Progress progress, long[] done, long totalWork)
            throws IOException {
        byte[] partHead = new byte[0x2C0];
        IoUtil.readFully(out, loc.absoluteOffset, partHead);
        ByteBuffer pb = ByteBuffer.wrap(partHead).order(ByteOrder.BIG_ENDIAN);
        long dataOffsetRel = (pb.getInt(0x2B8) & 0xFFFFFFFFL) * 4;
        long h3OffsetRel = (pb.getInt(0x2B4) & 0xFFFFFFFFL) * 4;
        long dataStart = loc.absoluteOffset + dataOffsetRel;
        long h3Start = loc.absoluteOffset + h3OffsetRel;

        int sectorsPerGroup = Math.max(1, h.chunkSize / WiiHashTree.CLUSTER_SIZE);
        int blocksPerGroup = Math.max(1, h.chunkSize / 0x200000);

        List<byte[]> h2PerBlock = new ArrayList<>();
        int exceptionsApplied = 0;

        for (int seg = 0; seg < 2; seg++) {
            long nSectorsTotal = part.nSectors[seg];
            if (nSectorsTotal <= 0) continue;
            long firstSector = part.firstSector[seg];
            int nGroups = part.nGroups[seg];
            long sectorsConsumed = 0;

            for (int gi = 0; gi < nGroups && sectorsConsumed < nSectorsTotal; gi++) {
                GroupEntry g = groups[part.groupIndex[seg] + gi];
                long sectorsThisGroup = Math.min(sectorsPerGroup, nSectorsTotal - sectorsConsumed);
                int plainLen = (int) (sectorsThisGroup * WiiHashTree.CLUSTER_DATA_SIZE);
                int listHeaderGuess = blocksPerGroup * 2; // cota mínima, las listas pueden traer excepciones
                byte[] decoded = decodeGroup(in, h, g, plainLen + listHeaderGuess);

                int offset = 0;
                WiiHashTree.Exception1[][] lists = new WiiHashTree.Exception1[blocksPerGroup][];
                for (int li = 0; li < blocksPerGroup; li++) {
                    if (offset + 2 > decoded.length) throw new RvzUnpackException("Lista de excepciones truncada");
                    int count = ((decoded[offset] & 0xFF) << 8) | (decoded[offset + 1] & 0xFF);
                    offset += 2;
                    WiiHashTree.Exception1[] list = new WiiHashTree.Exception1[count];
                    for (int k = 0; k < count; k++) {
                        int exOff = ((decoded[offset] & 0xFF) << 8) | (decoded[offset + 1] & 0xFF);
                        offset += 2;
                        byte[] hash = Arrays.copyOfRange(decoded, offset, offset + 20);
                        offset += 20;
                        list[k] = new WiiHashTree.Exception1(exOff, hash);
                    }
                    lists[li] = list;
                    exceptionsApplied += count;
                }
                int plainAvailable = decoded.length - offset;
                if (plainAvailable != plainLen) {
                    throw new RvzUnpackException("Grupo de partición con " + plainAvailable
                            + " bytes de datos planos; se esperaban " + plainLen);
                }

                long sectorCursor = sectorsConsumed;
                int dataOffset = offset;
                for (int bi = 0; bi < blocksPerGroup; bi++) {
                    long remainInGroup = sectorsThisGroup - bi * (long) WiiHashTree.CLUSTERS_PER_GROUP;
                    if (remainInGroup <= 0) break;
                    int blockSectors = (int) Math.min(WiiHashTree.CLUSTERS_PER_GROUP, remainInGroup);
                    byte[] blockPlain = Arrays.copyOfRange(decoded,
                            dataOffset + bi * WiiHashTree.CLUSTERS_PER_GROUP * WiiHashTree.CLUSTER_DATA_SIZE,
                            dataOffset + bi * WiiHashTree.CLUSTERS_PER_GROUP * WiiHashTree.CLUSTER_DATA_SIZE
                                    + blockSectors * WiiHashTree.CLUSTER_DATA_SIZE);
                    byte[] block;
                    try {
                        block = WiiHashTree.buildBlock(blockPlain, blockSectors, lists[bi]);
                    } catch (NoSuchAlgorithmException e) {
                        throw new RvzUnpackException("SHA-1 no disponible", e);
                    }
                    try {
                        for (int c = 0; c < blockSectors; c++) {
                            WiiCrypto.encryptClusterInPlace(block, c * WiiHashTree.CLUSTER_SIZE, part.key);
                        }
                    } catch (GeneralSecurityException e) {
                        throw new RvzUnpackException("Error al cifrar un clúster del disco", e);
                    }
                    long absSector = firstSector + sectorCursor;
                    IoUtil.writeFully(out, dataStart + absSector * WiiHashTree.CLUSTER_SIZE, block, 0, block.length);
                    h2PerBlock.add(WiiHashTree.h2Of(block));
                    sectorCursor += blockSectors;

                    done[0] += (long) blockSectors * WiiHashTree.CLUSTER_DATA_SIZE;
                    reportProgress(progress, done[0], totalWork);
                }
                sectorsConsumed += sectorsThisGroup;
            }
        }

        writeH3Table(out, h3Start, h2PerBlock);
        return exceptionsApplied;
    }

    private static void writeH3Table(FileChannel out, long h3Start, List<byte[]> h2PerBlock) throws IOException {
        byte[] table = new byte[0x18000];
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            for (int i = 0; i < h2PerBlock.size() && i * 20 + 20 <= table.length; i++) {
                sha1.reset();
                byte[] h = sha1.digest(h2PerBlock.get(i));
                System.arraycopy(h, 0, table, i * 20, 20);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RvzUnpackException("SHA-1 no disponible", e);
        }
        IoUtil.writeFully(out, h3Start, table, 0, table.length);
    }

    private static void reportProgress(Progress progress, long done, long total) throws IOException {
        if (progress != null && !progress.update(done, total)) throw new CancelledException();
    }

    private static boolean verifyHash(FileChannel out, RvzHeader h) throws IOException {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 20];
            long pos = 0;
            long total = h.isoFileSize;
            while (pos < total) {
                int want = (int) Math.min(buf.length, total - pos);
                byte[] chunk = want == buf.length ? buf : new byte[want];
                IoUtil.readFully(out, pos, chunk);
                sha1.update(chunk);
                pos += want;
            }
            byte[] digest = sha1.digest();
            return Arrays.equals(digest, h.discHash);
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
    }
}
