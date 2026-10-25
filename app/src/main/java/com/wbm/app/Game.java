package com.wbm.app;

import android.net.Uri;

import com.wbm.core.DiscInfo;

import java.util.List;

/** Un juego encontrado en la biblioteca: una ISO o un WBFS (posiblemente dividido en partes). */
final class Game {
    final boolean wbfs;
    final String fileName;
    final List<Uri> parts;
    final long totalSize;
    final String parentDocId;
    final DiscInfo disc;

    Game(boolean wbfs, String fileName, List<Uri> parts, long totalSize, String parentDocId, DiscInfo disc) {
        this.wbfs = wbfs;
        this.fileName = fileName;
        this.parts = parts;
        this.totalSize = totalSize;
        this.parentDocId = parentDocId;
        this.disc = disc;
    }

    String displayTitle() {
        return disc.title.isEmpty() ? "(sin título)" : disc.title;
    }

    String label() {
        return disc.id + "   " + displayTitle() + "\n"
                + (wbfs ? "WBFS" : "ISO") + (parts.size() > 1 ? " (" + parts.size() + " partes)" : "")
                + "  ·  " + Fmt.size(totalSize) + "  ·  " + disc.region();
    }

    String folderName() {
        return disc.safeTitle() + " [" + disc.id + "]";
    }
}
