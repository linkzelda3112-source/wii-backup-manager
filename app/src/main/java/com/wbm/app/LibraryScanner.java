package com.wbm.app;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;

import com.wbm.core.DiscInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Recorre la carpeta elegida y reúne las ISO, WBFS y RVZ de Wii que encuentra. */
final class LibraryScanner {
    private LibraryScanner() {}

    private static final int MAX_DEPTH = 5;

    /** skipped[0] recibe cuántos .iso/.wbfs/.rvz se ignoraron por no ser discos de Wii legibles. */
    static List<Game> scan(ContentResolver cr, Uri tree, int[] skipped) {
        List<Game> out = new ArrayList<>();
        walk(cr, tree, DocumentsContract.getTreeDocumentId(tree), 0, out, skipped);
        Collections.sort(out, (a, b) -> {
            int c = a.disc.title.compareToIgnoreCase(b.disc.title);
            return c != 0 ? c : a.disc.id.compareTo(b.disc.id);
        });
        return out;
    }

    private static void walk(ContentResolver cr, Uri tree, String dirId, int depth, List<Game> out, int[] skipped) {
        List<Saf.Entry> entries = Saf.list(cr, tree, dirId);
        for (Saf.Entry e : entries) {
            if (e.dir) {
                if (depth < MAX_DEPTH) walk(cr, tree, e.docId, depth + 1, out, skipped);
                continue;
            }
            String lower = e.name.toLowerCase(Locale.ROOT);
            Game.Kind kind;
            if (lower.endsWith(".wbfs")) kind = Game.Kind.WBFS;
            else if (lower.endsWith(".iso")) kind = Game.Kind.ISO;
            else if (lower.endsWith(".rvz") || lower.endsWith(".wia")) kind = Game.Kind.RVZ;
            else continue;
            try {
                DiscInfo d = Saf.readDiscInfo(cr, e.uri, kind);
                if (!d.wii) {
                    skipped[0]++;
                    continue;
                }
                List<Uri> parts = new ArrayList<>();
                parts.add(e.uri);
                long total = Math.max(e.size, 0);
                if (kind == Game.Kind.WBFS) {
                    String base = e.name.substring(0, e.name.length() - ".wbfs".length());
                    for (int i = 1; i < 100; i++) {
                        Saf.Entry p = find(entries, base + ".wbf" + i);
                        if (p == null) break;
                        parts.add(p.uri);
                        total += Math.max(p.size, 0);
                    }
                }
                out.add(new Game(kind, e.name, parts, total, dirId, d));
            } catch (IOException | RuntimeException ex) {
                skipped[0]++;
            }
        }
    }

    private static Saf.Entry find(List<Saf.Entry> entries, String name) {
        for (Saf.Entry e : entries) {
            if (!e.dir && e.name.equalsIgnoreCase(name)) return e;
        }
        return null;
    }
}
