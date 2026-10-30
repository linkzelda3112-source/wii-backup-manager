package com.wbm.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Avisa si hay una versión más nueva publicada como Release en GitHub que la instalada, leyendo
 * directamente la API pública de GitHub (no hace falta ningún servidor propio). Se usa tanto al
 * abrir la app (en silencio, solo si hay algo nuevo) como desde un botón en Ajustes (mostrando
 * también el resultado cuando ya está al día o cuando no se pudo consultar).
 */
final class UpdateChecker {
    private static final String REPO = "linkzelda3112-source/wii-backup-manager";
    private static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String FALLBACK_URL = "https://github.com/" + REPO + "/releases/latest";

    private UpdateChecker() {}

    /** @param manual si viene de un botón: entonces también avisa cuando ya está al día o si falla. */
    static void checkAndNotify(MainActivity activity, boolean manual) {
        new Thread(() -> {
            String current = currentVersionName(activity);
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                // La API de GitHub exige un User-Agent; sin uno, responde 403.
                conn.setRequestProperty("User-Agent", "wii-backup-manager-app");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                int code = conn.getResponseCode();
                if (code != 200) {
                    if (manual) {
                        notifyPlain(activity, "No se pudo consultar GitHub (código " + code + ").");
                    }
                    return;
                }
                String body = readAll(conn.getInputStream());
                JSONObject json = new JSONObject(body);
                String tag = json.optString("tag_name", "");
                String htmlUrl = json.optString("html_url", FALLBACK_URL);
                if (tag.isEmpty()) {
                    if (manual) notifyPlain(activity, "La respuesta de GitHub no traía ninguna versión.");
                    return;
                }
                String latest = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
                if (isNewer(latest, current)) {
                    notifyNewVersion(activity, latest, current, htmlUrl);
                } else if (manual) {
                    notifyPlain(activity, "Ya tienes la última versión (" + current + ").");
                }
            } catch (Exception e) {
                if (manual) {
                    notifyPlain(activity, "No se pudo revisar actualizaciones: " + e.getMessage());
                }
            }
        }, "wbm-update-check").start();
    }

    private static void notifyNewVersion(MainActivity activity, String latest, String current, String htmlUrl) {
        runOnUi(activity, () -> new AlertDialog.Builder(activity)
                .setTitle("🎃 Nueva actualización disponible")
                .setMessage("Hay una nueva versión: " + latest + " (tienes instalada la " + current + ").\n\n"
                        + "¿Quieres abrir la página para descargarla?")
                .setPositiveButton("Actualizar", (d, w) ->
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl))))
                .setNegativeButton("Ahora no", null)
                .show());
    }

    private static void notifyPlain(MainActivity activity, String message) {
        runOnUi(activity, () -> new AlertDialog.Builder(activity)
                .setTitle("Buscar actualizaciones")
                .setMessage(message)
                .setPositiveButton("Ok", null)
                .show());
    }

    private static void runOnUi(MainActivity activity, Runnable r) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!activity.isFinishing()) r.run();
        });
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String currentVersionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    /** Compara "a.b.c" numéricamente (sin asumir que ambas tengan el mismo número de partes). */
    private static boolean isNewer(String remote, String local) {
        String[] r = remote.split("\\.");
        String[] l = local.split("\\.");
        int n = Math.max(r.length, l.length);
        for (int i = 0; i < n; i++) {
            int rv = i < r.length ? parseIntSafe(r[i]) : 0;
            int lv = i < l.length ? parseIntSafe(l[i]) : 0;
            if (rv != lv) return rv > lv;
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }
}
