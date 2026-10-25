package com.wbm.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Datos básicos leídos de los primeros 0x100 bytes de un disco Wii. */
public final class DiscInfo {
    public static final int WII_MAGIC = 0x5D1C9EA3;
    public static final int GC_MAGIC = 0xC2339F3D;

    public final String id;
    public final String title;
    public final boolean wii;
    public final boolean gamecube;

    private DiscInfo(String id, String title, boolean wii, boolean gamecube) {
        this.id = id;
        this.title = title;
        this.wii = wii;
        this.gamecube = gamecube;
    }

    public static DiscInfo parse(byte[] h) {
        if (h.length < 0x60) throw new IllegalArgumentException("Cabecera demasiado corta");
        ByteBuffer b = ByteBuffer.wrap(h);
        boolean wii = b.getInt(0x18) == WII_MAGIC;
        boolean gc = b.getInt(0x1C) == GC_MAGIC;
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            int c = h[i] & 0xFF;
            id.append(c >= 0x20 && c < 0x7F ? (char) c : '?');
        }
        int end = 0x20;
        while (end < 0x60 && h[end] != 0) end++;
        String title = new String(h, 0x20, end - 0x20, StandardCharsets.ISO_8859_1).trim();
        return new DiscInfo(id.toString(), title, wii, gc);
    }

    public String region() {
        if (id.length() < 4) return "?";
        switch (id.charAt(3)) {
            case 'E': return "NTSC-U (EE. UU.)";
            case 'J': return "NTSC-J (Japón)";
            case 'K': return "NTSC-K (Corea)";
            case 'W': return "NTSC-T (Taiwán)";
            case 'P': case 'X': case 'Y': case 'Z':
            case 'L': case 'M': return "PAL";
            case 'D': return "PAL (Alemania)";
            case 'F': return "PAL (Francia)";
            case 'I': return "PAL (Italia)";
            case 'S': return "PAL (España)";
            case 'U': return "PAL (Australia)";
            case 'H': return "PAL (Países Bajos)";
            case 'R': return "Rusia";
            default: return "Otra (" + id.charAt(3) + ")";
        }
    }

    /** Nombre seguro para usar como carpeta/archivo. */
    public String safeTitle() {
        String t = title.isEmpty() ? id : title;
        t = t.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return t.isEmpty() ? id : t;
    }
}
