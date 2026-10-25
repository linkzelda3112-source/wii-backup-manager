package com.wbm.app;

import java.util.Locale;

final class Fmt {
    private Fmt() {}

    static String size(long bytes) {
        if (bytes >= (1L << 30)) return String.format(Locale.getDefault(), "%.2f GB", bytes / (double) (1L << 30));
        if (bytes >= (1L << 20)) return String.format(Locale.getDefault(), "%.1f MB", bytes / (double) (1L << 20));
        return bytes + " B";
    }
}
