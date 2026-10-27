package com.wbm.app;

/** Construye la URL de la carátula de un juego en GameTDB. */
final class CoverArt {
    private CoverArt() {}

    /** Carátula 3D (la que usa Wii Backup Manager en el escritorio). */
    static String coverUrl(Game g) {
        return "https://art.gametdb.com/wii/cover3D/" + g.coverRegionCode() + "/" + g.disc.id + ".png";
    }

    /** Carátula plana, como alternativa si la 3D no existe para ese juego. */
    static String flatCoverUrl(Game g) {
        return "https://art.gametdb.com/wii/cover/" + g.coverRegionCode() + "/" + g.disc.id + ".png";
    }
}
