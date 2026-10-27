package com.wbm.app;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Intenta averiguar el tipo de sistema de archivos de la carpeta elegida (FAT32, exFAT, NTFS…)
 * para avisar si hace falta dividir los WBFS en partes de 4 GB. Android no da una API directa
 * para esto, así que se usa la mejor señal disponible: ubicar el volumen de almacenamiento real
 * detrás del Uri elegido (API 24+) y, si se puede acceder a su ruta (API 30+), buscarla en
 * /proc/mounts. Si no se puede averiguar, se dice con honestidad que no se sabe.
 */
final class StorageCheck {
    private StorageCheck() {}

    enum FsType { FAT32, EXFAT, NTFS, OTHER, UNKNOWN }

    static final class Info {
        final boolean removable;
        final String description;
        final FsType fsType;

        Info(boolean removable, String description, FsType fsType) {
            this.removable = removable;
            this.description = description;
            this.fsType = fsType;
        }

        boolean needsSplit() {
            return fsType == FsType.FAT32 || fsType == FsType.UNKNOWN;
        }

        String statusText() {
            String fs;
            switch (fsType) {
                case FAT32: fs = "FAT32"; break;
                case EXFAT: fs = "exFAT"; break;
                case NTFS: fs = "NTFS"; break;
                case OTHER: fs = "otro sistema de archivos"; break;
                default: fs = "sistema de archivos no identificado"; break;
            }
            String where = removable ? "Unidad extraíble" : "Almacenamiento";
            String desc = description != null && !description.isEmpty() ? " (" + description + ")" : "";
            if (fsType == FsType.UNKNOWN) {
                return where + desc + ": no se pudo determinar el sistema de archivos; por seguridad se "
                        + "dividirán los WBFS en partes de 4 GB.";
            }
            if (needsSplit()) {
                return where + desc + " — " + fs + ": los archivos de más de 4 GB no caben, se dividirán "
                        + "automáticamente.";
            }
            return where + desc + " — " + fs + ": admite archivos grandes, no hace falta dividir.";
        }
    }

    static Info check(Context ctx, Uri treeUri) {
        boolean removable = false;
        String description = null;
        FsType fsType = FsType.UNKNOWN;
        try {
            StorageManager sm = ctx.getSystemService(StorageManager.class);
            if (sm != null && Build.VERSION.SDK_INT >= 24) {
                StorageVolume vol = sm.getStorageVolume(treeUri);
                if (vol != null) {
                    removable = vol.isRemovable();
                    try {
                        description = vol.getDescription(ctx);
                    } catch (RuntimeException ignored) {
                        // algunos fabricantes fallan aquí sin motivo aparente
                    }
                    if (Build.VERSION.SDK_INT >= 30) {
                        try {
                            File dir = vol.getDirectory();
                            if (dir != null) fsType = fsTypeFromMounts(dir.getAbsolutePath());
                        } catch (RuntimeException ignored) {
                            // directorio no disponible en este fabricante/versión
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // si algo falla, simplemente queda como "desconocido" y se avisa igual
        }
        return new Info(removable, description, fsType);
    }

    private static FsType fsTypeFromMounts(String path) {
        String bestMount = null;
        String bestType = null;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length < 3) continue;
                String mountPoint = parts[1];
                if (path.equals(mountPoint) || path.startsWith(mountPoint + "/")) {
                    if (bestMount == null || mountPoint.length() > bestMount.length()) {
                        bestMount = mountPoint;
                        bestType = parts[2];
                    }
                }
            }
        } catch (Exception e) {
            return FsType.UNKNOWN;
        }
        if (bestType == null) return FsType.UNKNOWN;
        switch (bestType.toLowerCase(java.util.Locale.ROOT)) {
            case "vfat":
            case "fat32":
            case "msdos":
                return FsType.FAT32;
            case "exfat":
                return FsType.EXFAT;
            case "ntfs":
            case "ntfs3":
            case "fuseblk": // a veces NTFS/exFAT vía FUSE se reporta así; se trata como "otro"
                return FsType.OTHER;
            default:
                return FsType.OTHER;
        }
    }
}
