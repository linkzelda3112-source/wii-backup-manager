package com.wbm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.DocumentsContract;

import com.wbm.core.CancelledException;
import com.wbm.core.Progress;
import com.wbm.core.SplitSink;
import com.wbm.core.SplitSource;
import com.wbm.core.WbfsFormat;
import com.wbm.core.WbfsReader;
import com.wbm.core.WbfsWriter;
import com.wbm.core.rvz.RvzExtractor;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Servicio en primer plano que convierte en cola: las ISO pasan a WBFS y los WBFS pasan a ISO.
 * El estado se comparte con la pantalla mediante campos estáticos (misma aplicación y proceso).
 */
public class ConversionService extends Service {
    private static final String CHANNEL_ID = "conversion";
    private static final int NOTIF_ID = 1;
    private static final int NOTIF_DONE_ID = 2;

    static final class Job {
        final Game game;
        final Uri tree;
        final boolean loaderLayout;
        final boolean split;
        final boolean pad;

        Job(Game game, Uri tree, boolean loaderLayout, boolean split, boolean pad) {
            this.game = game;
            this.tree = tree;
            this.loaderLayout = loaderLayout;
            this.split = split;
            this.pad = pad;
        }
    }

    private static final List<Job> QUEUE = new ArrayList<>();
    private static boolean workerActive; // protegido por QUEUE

    static volatile boolean running;
    static volatile boolean cancelRequested;
    static volatile String current = "";
    static volatile long done;
    static volatile long total;
    static volatile int jobIndex;
    static volatile int jobCount;
    static final List<String> results = new CopyOnWriteArrayList<>();

    private volatile int lastStartId;
    private long lastNotify;

    static void enqueue(List<Job> jobs) {
        synchronized (QUEUE) {
            if (!workerActive) {
                jobIndex = 0;
                jobCount = 0;
                results.clear();
                cancelRequested = false;
            }
            QUEUE.addAll(jobs);
            jobCount += jobs.size();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        createChannel();
        Notification n = buildNotification("Preparando…", true);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
        synchronized (QUEUE) {
            if (workerActive) return START_NOT_STICKY;
            workerActive = true;
            running = true;
        }
        new Thread(this::runLoop, "wbm-worker").start();
        return START_NOT_STICKY;
    }

    private void runLoop() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wbm:convert");
        wl.acquire(6L * 60 * 60 * 1000);
        try {
            while (true) {
                Job job;
                synchronized (QUEUE) {
                    if (QUEUE.isEmpty()) break;
                    job = QUEUE.remove(0);
                }
                jobIndex++;
                try {
                    process(job);
                } catch (Throwable e) {
                    // process() ya atrapa todo lo que puede pasar al convertir un juego; esto es
                    // un segundo seguro por si acaso, para que ningún error, sea cual sea, tire
                    // abajo toda la app: mejor seguir con el siguiente juego de la cola.
                    results.add("ERROR  " + job.game.displayTitle() + " [" + job.game.disc.id + "]: "
                            + friendly(e));
                }
                if (cancelRequested) {
                    synchronized (QUEUE) {
                        QUEUE.clear();
                    }
                    break;
                }
            }
        } finally {
            if (wl.isHeld()) wl.release();
            synchronized (QUEUE) {
                workerActive = false;
                running = false;
            }
            current = "";
            stopForeground(STOP_FOREGROUND_REMOVE);
            postSummary();
            stopSelf(lastStartId);
        }
    }

    private void process(Job job) {
        Game g = job.game;
        String name = g.displayTitle() + " [" + g.disc.id + "]";
        current = name;
        done = 0;
        total = 0;
        updateNotification();
        try {
            switch (g.kind) {
                case WBFS: wbfsToIso(job); break;
                case RVZ: rvzToWbfs(job); break;
                default: isoToWbfs(job); break;
            }
        } catch (CancelledException e) {
            results.add("CANCELADO  " + name);
        } catch (Throwable e) {
            // Throwable (no solo Exception/RuntimeException) a propósito: un error inesperado aquí
            // (p. ej. un UnsatisfiedLinkError de una librería nativa, o un OutOfMemoryError con un
            // disco muy grande) es un Error, no una Exception, y un Error sin atrapar mata la app
            // entera en vez de mostrarse como un error en el registro. Mejor mostrar el error y
            // seguir con el siguiente juego de la cola que cerrar toda la aplicación.
            results.add("ERROR  " + name + " (" + g.kindLabel() + "): " + friendly(e));
        }
    }

    private Progress progress() {
        return (d, t) -> {
            done = d;
            total = t;
            long now = SystemClock.elapsedRealtime();
            if (now - lastNotify > 1000) {
                lastNotify = now;
                updateNotification();
            }
            return !cancelRequested;
        };
    }

    private void isoToWbfs(Job job) throws IOException {
        final ContentResolver cr = getContentResolver();
        final Game g = job.game;
        try (FileChannel in = Saf.openRead(cr, g.parts.get(0))) {
            WbfsWriter.Result r = writeWbfsFromChannel(job, in, progress());
            results.add("OK  " + g.displayTitle() + " [" + g.disc.id + "]: ISO " + Fmt.size(r.isoSize)
                    + " → WBFS " + Fmt.size(r.wbfsSize));
        }
    }

    /** RVZ/WIA: primero se reconstruye una ISO temporal en la memoria interna, luego se convierte igual que una ISO. */
    private void rvzToWbfs(Job job) throws IOException {
        final ContentResolver cr = getContentResolver();
        final Game g = job.game;
        File staging = new File(getCacheDir(), "rvz_staging_" + System.nanoTime() + ".iso");
        try {
            RvzExtractor.Result extractResult;
            try (FileChannel rvzIn = Saf.openRead(cr, g.parts.get(0));
                 FileChannel stageOut = FileChannel.open(staging.toPath(), StandardOpenOption.CREATE,
                         StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING)) {
                extractResult = RvzExtractor.extract(rvzIn, stageOut, phaseProgress(0, 55));
            }
            if (!extractResult.hashMatches) {
                results.add("AVISO  " + g.displayTitle() + " [" + g.disc.id + "]: la ISO reconstruida desde el "
                        + "RVZ no coincide con el hash original guardado en el archivo; revisa el juego antes de "
                        + "confiar en el resultado.");
            }
            try (FileChannel in = FileChannel.open(staging.toPath(), StandardOpenOption.READ)) {
                WbfsWriter.Result r = writeWbfsFromChannel(job, in, phaseProgress(55, 100));
                String tag = extractResult.hashMatches ? "OK" : "OK (sin verificar)";
                results.add(tag + "  " + g.displayTitle() + " [" + g.disc.id + "]: RVZ " + Fmt.size(g.totalSize)
                        + " → WBFS " + Fmt.size(r.wbfsSize));
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            staging.delete();
        }
    }

    private WbfsWriter.Result writeWbfsFromChannel(Job job, FileChannel in, Progress prog) throws IOException {
        final ContentResolver cr = getContentResolver();
        final Game g = job.game;
        final Uri tree = job.tree;
        final String rootId = DocumentsContract.getTreeDocumentId(tree);

        final String parentId;
        final String baseName;
        if (job.loaderLayout) {
            Saf.Entry wbfsDir = Saf.ensureDir(cr, tree, rootId, "wbfs");
            parentId = Saf.ensureDir(cr, tree, wbfsDir.docId, g.folderName()).docId;
            baseName = g.disc.id;
        } else {
            parentId = rootId;
            baseName = g.folderName();
        }
        if (Saf.findChild(cr, tree, parentId, baseName + ".wbfs") != null) {
            throw new IOException("Ya existe «" + baseName + ".wbfs» en el destino; no se sobrescribe.");
        }

        final List<Uri> created = new ArrayList<>();
        SplitSink.Opener opener = i -> {
            String n = i == 0 ? baseName + ".wbfs" : baseName + ".wbf" + i;
            Saf.Entry e = Saf.createFile(cr, tree, parentId, n);
            created.add(e.uri);
            return Saf.openWrite(cr, e.uri);
        };

        try (SplitSink sink = new SplitSink(opener, job.split ? WbfsFormat.FAT32_SPLIT_SIZE : 0)) {
            return WbfsWriter.convert(in, sink, 0, prog);
        } catch (IOException | RuntimeException e) {
            for (Uri u : created) Saf.deleteQuiet(cr, u);
            throw e;
        }
    }

    /** Envuelve el progreso real para que se muestre dentro del tramo [fromPct, toPct] de la barra. */
    private Progress phaseProgress(int fromPct, int toPct) {
        Progress inner = progress();
        return (d, t) -> {
            long scaledTotal = 1000;
            long scaledDone = t > 0 ? (fromPct * 10L) + (d * (toPct - fromPct) * 10L) / t : fromPct * 10L;
            return inner.update(scaledDone, scaledTotal);
        };
    }

    private void wbfsToIso(Job job) throws IOException {
        final ContentResolver cr = getContentResolver();
        final Game g = job.game;
        final Uri tree = job.tree;
        final String rootId = DocumentsContract.getTreeDocumentId(tree);

        final String parentId;
        final String fileName;
        if (job.loaderLayout) {
            Saf.Entry gamesDir = Saf.ensureDir(cr, tree, rootId, "games");
            parentId = Saf.ensureDir(cr, tree, gamesDir.docId, g.folderName()).docId;
            fileName = "game.iso";
        } else {
            parentId = rootId;
            fileName = g.folderName() + ".iso";
        }
        if (Saf.findChild(cr, tree, parentId, fileName) != null) {
            throw new IOException("Ya existe «" + fileName + "» en el destino; no se sobrescribe.");
        }

        List<FileChannel> channels = new ArrayList<>();
        SplitSource src = null;
        try {
            for (Uri u : g.parts) channels.add(Saf.openRead(cr, u));
            src = new SplitSource(channels);
        } catch (IOException | RuntimeException e) {
            for (FileChannel c : channels) {
                try {
                    c.close();
                } catch (IOException ignored) {
                    // se cierra lo que se pueda
                }
            }
            throw e;
        }

        Uri createdUri = null;
        try (SplitSource source = src) {
            WbfsReader reader = WbfsReader.open(source);
            Saf.Entry out = Saf.createFile(cr, tree, parentId, fileName);
            createdUri = out.uri;
            long written;
            try (FileChannel ch = Saf.openWrite(cr, out.uri)) {
                written = reader.toIso(ch, job.pad, progress());
            }
            results.add("OK  " + g.displayTitle() + " [" + g.disc.id + "]: WBFS " + Fmt.size(g.totalSize)
                    + " → ISO " + Fmt.size(written));
        } catch (IOException | RuntimeException e) {
            if (createdUri != null) Saf.deleteQuiet(cr, createdUri);
            throw e;
        }
    }

    private static String friendly(Throwable e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("ENOSPC") || m.toLowerCase().contains("no space")) {
            return "no hay espacio suficiente en el destino.";
        }
        if (m.contains("EFBIG") || m.toLowerCase().contains("file too large")) {
            return "el sistema de archivos del destino no admite archivos tan grandes (FAT32 llega a 4 GB). "
                    + "Activa «Dividir WBFS en partes de 4 GB» o elige un destino exFAT/NTFS/interno.";
        }
        // Para errores inesperados (como un ArrayIndexOutOfBoundsException, o un Error que antes
        // cerraba la app entera) se agrega el tipo exacto y el punto del código donde ocurrió: sin
        // eso el mensaje por sí solo (p. ej. "length=35859; index=84017420") no dice nada útil para
        // diagnosticar el problema.
        if (e instanceof RuntimeException || e instanceof Error) {
            StackTraceElement[] st = e.getStackTrace();
            String where = "";
            for (StackTraceElement el : st) {
                if (el.getClassName().startsWith("com.wbm.")) {
                    where = " — en " + el.getClassName().replace("com.wbm.", "") + "." + el.getMethodName()
                            + ":" + el.getLineNumber();
                    break;
                }
            }
            return e.getClass().getSimpleName() + ": " + m + where;
        }
        return m;
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Conversión de juegos",
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
    }

    private PendingIntent openAppIntent() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification(String text, boolean indeterminate) {
        Intent cancel = new Intent(this, CancelReceiver.class);
        PendingIntent cancelPi = PendingIntent.getBroadcast(this, 1, cancel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action action = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Cancelar", cancelPi).build();
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Wii Backup Manager")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent())
                .addAction(action);
        long t = total;
        if (!indeterminate && t > 0) {
            b.setProgress(1000, (int) (done * 1000 / t), false);
        } else {
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    private void updateNotification() {
        String text = "(" + jobIndex + "/" + jobCount + ") " + current;
        if (total > 0) text += " — " + (int) (done * 100 / total) + " %";
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification(text, false));
    }

    private void postSummary() {
        int ok = 0;
        int bad = 0;
        for (String r : results) {
            if (r.startsWith("OK")) ok++; else bad++;
        }
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Wii Backup Manager")
                .setContentText(ok + " convertido(s)" + (bad > 0 ? ", " + bad + " con incidencias" : ""))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(NOTIF_DONE_ID, n);
    }

    /** Botón «Cancelar» de la notificación. */
    public static class CancelReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            cancelRequested = true;
        }
    }
}
