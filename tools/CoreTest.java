import com.wbm.core.*;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Pruebas del núcleo ISO <-> WBFS. Uso: java CoreTest <directorio de trabajo> */
public class CoreTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  OK   " : "  FALLO ") + what);
        if (!ok) failures++;
    }

    static byte[] makeIso(long size, String id, String title, long seed) {
        byte[] iso = new byte[(int) size];
        Random r = new Random(seed);
        int MB = 1 << 20;
        // cabecera + tabla de particiones simuladas: datos aleatorios en los primeros 0x50000
        byte[] chunk = new byte[0x50000];
        r.nextBytes(chunk);
        System.arraycopy(chunk, 0, iso, 0, chunk.length);
        // hueco de ceros 1MB..4MB (ya son ceros), datos de 10MB a 12MB
        byte[] data = new byte[2 * MB];
        r.nextBytes(data);
        System.arraycopy(data, 0, iso, 10 * MB, data.length);
        // datos dispersos
        for (int k = 0; k < 20; k++) {
            int pos = 14 * MB + r.nextInt(8 * MB);
            for (int j = 0; j < 1000 && pos + j < iso.length; j++) iso[pos + j] = (byte) (1 + r.nextInt(255));
        }
        // último sector de 32 KB con datos
        byte[] tail = new byte[0x8000];
        r.nextBytes(tail);
        System.arraycopy(tail, 0, iso, iso.length - tail.length, tail.length);
        // cabecera real de disco
        byte[] idb = id.getBytes();
        System.arraycopy(idb, 0, iso, 0, idb.length);
        byte[] tb = title.getBytes();
        java.util.Arrays.fill(iso, 0x20, 0x60, (byte) 0);
        System.arraycopy(tb, 0, iso, 0x20, tb.length);
        ByteBuffer.wrap(iso).order(ByteOrder.BIG_ENDIAN).putInt(0x18, DiscInfo.WII_MAGIC).putInt(0x1C, 0);
        return iso;
    }

    static String sha(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(md.digest(b));
    }

    static String shaFile(Path p) throws Exception {
        return sha(Files.readAllBytes(p));
    }

    static FileChannel rw(Path p) throws IOException {
        return FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING);
    }

    static SplitSink sinkFor(Path base, String ext0, String extN, long split, List<Path> created) {
        return new SplitSink(i -> {
            Path p = i == 0 ? base.resolveSibling(base.getFileName() + ext0)
                    : base.resolveSibling(base.getFileName() + extN + i);
            created.add(p);
            return rw(p);
        }, split);
    }

    static SplitSource sourceOf(List<Path> parts) throws IOException {
        List<FileChannel> chs = new ArrayList<>();
        for (Path p : parts) chs.add(FileChannel.open(p, StandardOpenOption.READ));
        return new SplitSource(chs);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        Files.createDirectories(dir);
        long size = 32L << 20;
        byte[] isoBytes = makeIso(size, "RSPE01", "Juego de prueba", 42);
        Path isoPath = dir.resolve("test.iso");
        Files.write(isoPath, isoBytes);
        String isoSha = sha(isoBytes);

        System.out.println("1) ISO -> WBFS -> ISO sin dividir");
        Path wbfs = dir.resolve("test");
        List<Path> parts = new ArrayList<>();
        WbfsWriter.Result res;
        try (FileChannel in = FileChannel.open(isoPath, StandardOpenOption.READ);
             SplitSink sink = sinkFor(wbfs, ".wbfs", ".wbf", 0, parts)) {
            res = WbfsWriter.convert(in, sink, 0, null);
        }
        Path wbfsFile = parts.get(0);
        long wlen = Files.size(wbfsFile);
        System.out.println("     bloque 2^" + res.blockShift + ", usados " + res.usedBlocks + "/" + res.totalBlocks
                + ", wbfs " + wlen + " bytes (iso " + size + ")");
        check(parts.size() == 1, "una sola parte");
        check(res.blockShift == 18, "bloque de 256 KiB elegido para una ISO pequeña");
        check(wlen == res.wbfsSize, "el tamaño del archivo coincide con el informado");
        check(wlen < size, "el WBFS es más pequeño que la ISO");
        check(res.disc.id.equals("RSPE01") && res.disc.title.equals("Juego de prueba"), "ID y título leídos");
        ByteBuffer hb = ByteBuffer.wrap(Files.readAllBytes(wbfsFile)).order(ByteOrder.BIG_ENDIAN);
        check(hb.getInt(0) == 0x57424653, "firma WBFS");
        check((hb.getInt(4) & 0xFFFFFFFFL) * 512 == wlen, "n_hd_sec * 512 == tamaño del archivo");
        check(hb.get(8) == 9 && hb.get(9) == 18 && hb.get(12) == 1, "sector 512, bloque 2^18, disco 0 presente");

        Path out1 = dir.resolve("roundtrip1.iso");
        try (SplitSource s = sourceOf(parts); FileChannel o = rw(out1)) {
            WbfsReader r = WbfsReader.open(s);
            check(r.disc.id.equals("RSPE01"), "lector: ID del disco");
            r.toIso(o, false, null);
        }
        check(Files.size(out1) == size, "ISO reconstruida con el mismo tamaño");
        check(shaFile(out1).equals(isoSha), "ISO reconstruida idéntica (SHA-256)");

        System.out.println("2) Dividido en partes de 3.000.001 bytes");
        Path wbfs2 = dir.resolve("split");
        List<Path> parts2 = new ArrayList<>();
        try (FileChannel in = FileChannel.open(isoPath, StandardOpenOption.READ);
             SplitSink sink = sinkFor(wbfs2, ".wbfs", ".wbf", 3_000_001L, parts2)) {
            WbfsWriter.convert(in, sink, 0, null);
        }
        check(parts2.size() > 2, "se crearon varias partes (" + parts2.size() + ")");
        long sum = 0;
        boolean sizesOk = true;
        for (int i = 0; i < parts2.size(); i++) {
            long l = Files.size(parts2.get(i));
            sum += l;
            if (i < parts2.size() - 1 && l != 3_000_001L) sizesOk = false;
        }
        check(sizesOk, "todas las partes salvo la última miden exactamente el tamaño de división");
        check(sum == wlen, "la suma de las partes es igual al archivo sin dividir");
        Path out2 = dir.resolve("roundtrip2.iso");
        try (SplitSource s = sourceOf(parts2); FileChannel o = rw(out2)) {
            WbfsReader.open(s).toIso(o, false, null);
        }
        check(shaFile(out2).equals(isoSha), "ISO desde partes idéntica (SHA-256)");
        byte[] a = Files.readAllBytes(wbfsFile);
        byte[] cat = new byte[(int) sum];
        int off = 0;
        for (Path p : parts2) {
            byte[] b = Files.readAllBytes(p);
            System.arraycopy(b, 0, cat, off, b.length);
            off += b.length;
        }
        check(java.util.Arrays.equals(a, cat), "las partes concatenadas son idénticas al WBFS sin dividir");

        System.out.println("3) ISO cuyo tamaño no es múltiplo del bloque");
        byte[] odd = makeIso((32L << 20) + 12345, "RSPP01", "Impar", 7);
        // el relleno final de makeIso deja datos en el último sector
        Path isoOdd = dir.resolve("odd.iso");
        Files.write(isoOdd, odd);
        List<Path> parts3 = new ArrayList<>();
        try (FileChannel in = FileChannel.open(isoOdd, StandardOpenOption.READ);
             SplitSink sink = sinkFor(dir.resolve("odd"), ".wbfs", ".wbf", 0, parts3)) {
            WbfsWriter.convert(in, sink, 0, null);
        }
        Path out3 = dir.resolve("roundtrip3.iso");
        try (SplitSource s = sourceOf(parts3); FileChannel o = rw(out3)) {
            WbfsReader.open(s).toIso(o, false, null);
        }
        byte[] back = Files.readAllBytes(out3);
        boolean prefix = true;
        for (int i = 0; i < odd.length; i++) if (back[i] != odd[i]) { prefix = false; break; }
        boolean tailZero = true;
        for (int i = odd.length; i < back.length; i++) if (back[i] != 0) { tailZero = false; break; }
        check(prefix && tailZero && back.length % (1 << 18) == 0, "contenido igual y relleno final de ceros hasta el bloque");

        System.out.println("4) Validaciones y errores");
        byte[] gc = new byte[1 << 20];
        System.arraycopy("GALE01".getBytes(), 0, gc, 0, 6);
        ByteBuffer.wrap(gc).order(ByteOrder.BIG_ENDIAN).putInt(0x1C, DiscInfo.GC_MAGIC);
        Path gcp = dir.resolve("gc.iso");
        Files.write(gcp, gc);
        try (FileChannel in = FileChannel.open(gcp, StandardOpenOption.READ);
             SplitSink sink = sinkFor(dir.resolve("gc"), ".wbfs", ".wbf", 0, new ArrayList<>())) {
            WbfsWriter.convert(in, sink, 0, null);
            check(false, "GameCube debe rechazarse");
        } catch (UnsupportedDiscException e) {
            check(e.getMessage().contains("GameCube"), "GameCube rechazado: " + e.getMessage());
        }
        byte[] junk = new byte[1 << 20];
        new Random(3).nextBytes(junk);
        Path jp = dir.resolve("junk.iso");
        Files.write(jp, junk);
        try (FileChannel in = FileChannel.open(jp, StandardOpenOption.READ);
             SplitSink sink = sinkFor(dir.resolve("junk"), ".wbfs", ".wbf", 0, new ArrayList<>())) {
            WbfsWriter.convert(in, sink, 0, null);
            check(false, "archivo sin firma debe rechazarse");
        } catch (UnsupportedDiscException e) {
            check(true, "archivo sin firma Wii rechazado");
        }
        try (SplitSource s = sourceOf(List.of(jp))) {
            WbfsReader.open(s);
            check(false, "un archivo que no es WBFS debe rechazarse");
        } catch (IOException e) {
            check(e.getMessage().contains("firma"), "lector rechaza archivo que no es WBFS");
        }
        // truncado: falta la última parte del WBFS dividido
        List<Path> missing = new ArrayList<>(parts2.subList(0, parts2.size() - 1));
        try (SplitSource s = sourceOf(missing)) {
            WbfsReader.open(s);
            check(false, "WBFS con partes que faltan debe rechazarse");
        } catch (IOException e) {
            check(e.getMessage().contains("incompleto"), "detecta partes que faltan: " + e.getMessage());
        }
        // cancelación
        try (FileChannel in = FileChannel.open(isoPath, StandardOpenOption.READ);
             SplitSink sink = sinkFor(dir.resolve("cancel"), ".wbfs", ".wbf", 0, new ArrayList<>())) {
            WbfsWriter.convert(in, sink, 0, (d, t) -> d < t / 2);
            check(false, "debe cancelarse");
        } catch (CancelledException e) {
            check(true, "cancelación de ISO -> WBFS");
        }
        try (SplitSource s = sourceOf(parts); FileChannel o = rw(dir.resolve("cancel.iso"))) {
            WbfsReader.open(s).toIso(o, false, (d, t) -> false);
            check(false, "debe cancelarse");
        } catch (CancelledException e) {
            check(true, "cancelación de WBFS -> ISO");
        }
        check(WbfsFormat.padTarget(1L << 30) == 4_699_979_776L, "relleno estándar de una capa = 4.699.979.776");
        check(WbfsFormat.padTarget(5_000_000_000L) == 8_511_160_320L, "relleno estándar de doble capa = 8.511.160.320");
        check(WbfsFormat.chooseShift(WbfsFormat.SL_ISO_SIZE) == 18, "disco de una capa -> bloques de 256 KiB");
        check(WbfsFormat.chooseShift(WbfsFormat.DL_ISO_SIZE) == 18, "disco de doble capa -> bloques de 256 KiB");

        System.out.println(failures == 0 ? "\nTODAS LAS PRUEBAS PASARON" : "\nFALLARON " + failures + " PRUEBAS");
        System.exit(failures == 0 ? 0 : 1);
    }
}
