package com.wbm.app;

import android.net.Uri;

import com.wbm.core.DiscInfo;

import java.util.List;

/** Un juego encontrado en la biblioteca: una ISO, un WBFS (posible dividido) o un RVZ. */
final class Game {
    enum Kind { ISO, WBFS, RVZ }

    final Kind kind;
    final String fileName;
    final List<Uri> parts;
    final long totalSize;
    final String parentDocId;
    final DiscInfo disc;

    Game(Kind kind, String fileName, List<Uri> parts, long totalSize, String parentDocId, DiscInfo disc) {
        this.kind = kind;
        this.fileName = fileName;
        this.parts = parts;
        this.totalSize = totalSize;
        this.parentDocId = parentDocId;
        this.disc = disc;
    }

    String displayTitle() {
        return disc.title.isEmpty() ? "(sin título)" : disc.title;
    }

    String kindLabel() {
        switch (kind) {
            case WBFS: return "WBFS";
            case RVZ: return "RVZ";
            default: return "ISO";
        }
    }

    String label() {
        return disc.id + "   " + displayTitle() + "\n"
                + kindLabel() + (parts.size() > 1 ? " (" + parts.size() + " partes)" : "")
                + "  ·  " + Fmt.size(totalSize) + "  ·  " + disc.region();
    }

    String folderName() {
        return disc.safeTitle() + " [" + disc.id + "]";
    }

    /** Código de región de 1 letra para pedir la carátula a GameTDB (EN como alternativa genérica). */
    String coverRegionCode() {
        if (disc.id.length() < 4) return "EN";
        switch (disc.id.charAt(3)) {
            case 'E': return "US";
            case 'J': return "JA";
            case 'K': return "KO";
            case 'W': return "ZHTW";
            case 'D': return "DE";
            case 'F': return "FR";
            case 'I': return "IT";
            case 'S': return "ES";
            case 'H': return "NL";
            case 'R': return "RU";
            default: return "EN";
        }
    }
}
