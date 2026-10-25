package com.wbm.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Pantalla principal: biblioteca de juegos, opciones y conversión por lotes. */
public class MainActivity extends Activity {
    private static final int REQ_TREE = 1;
    private static final int REQ_NOTIF = 2;
    private static final String PREFS = "wbm";
    private static final String KEY_TREE = "tree";

    private SharedPreferences prefs;
    private Uri tree;
    private final List<Game> games = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    private TextView libraryText;
    private TextView emptyText;
    private ListView listView;
    private CheckBox optLayout;
    private CheckBox optSplit;
    private CheckBox optPad;
    private Button convertBtn;
    private Button cancelBtn;
    private TextView progressText;
    private ProgressBar progressBar;
    private TextView logText;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean wasRunning;
    private boolean scanning;
    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            refreshProgress();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        String saved = prefs.getString(KEY_TREE, null);
        if (saved != null) {
            Uri u = Uri.parse(saved);
            if (hasPermission(u)) {
                tree = u;
                scan();
            }
        }
        updateLibraryText();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(poller);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poller);
        super.onPause();
    }

    private boolean hasPermission(Uri u) {
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isReadPermission() && p.isWritePermission()) return true;
        }
        return false;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        libraryText = new TextView(this);
        libraryText.setTextSize(14);
        root.addView(libraryText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("Elegir carpeta", v -> chooseFolder()), weight());
        row.addView(button("Actualizar", v -> scan()), weight());
        row.addView(button("Marcar todo", v -> selectAll()), weight());
        root.addView(row);

        FrameLayout listWrap = new FrameLayout(this);
        listView = new ListView(this);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, labels);
        listView.setAdapter(adapter);
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            showInfo(games.get(position));
            return true;
        });
        emptyText = new TextView(this);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(dp(16), dp(16), dp(16), dp(16));
        listWrap.addView(listView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listWrap.addView(emptyText, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(listWrap, lp);

        optLayout = new CheckBox(this);
        optLayout.setText("Crear carpetas para cargador USB: wbfs/Título [ID]/ID.wbfs y games/Título [ID]/game.iso");
        optLayout.setChecked(true);
        optSplit = new CheckBox(this);
        optSplit.setText("Dividir WBFS en partes de 4 GB (necesario en FAT32)");
        optPad = new CheckBox(this);
        optPad.setText("Al crear ISO, rellenar hasta el tamaño estándar de Wii");
        optPad.setChecked(true);
        root.addView(optLayout);
        root.addView(optSplit);
        root.addView(optPad);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        convertBtn = button("Convertir seleccionados", v -> startConversion());
        cancelBtn = button("Cancelar", v -> ConversionService.cancelRequested = true);
        cancelBtn.setEnabled(false);
        actions.addView(convertBtn, weight());
        actions.addView(cancelBtn, weight());
        root.addView(actions);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        progressText = new TextView(this);
        progressText.setTextSize(13);
        root.addView(progressText);

        ScrollView logScroll = new ScrollView(this);
        logText = new TextView(this);
        logText.setTextSize(12);
        logScroll.addView(logText);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(90)));

        setContentView(root);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void updateLibraryText() {
        if (tree == null) {
            libraryText.setText("Biblioteca: (sin elegir)");
            emptyText.setText("Pulsa «Elegir carpeta» y selecciona donde guardas tus juegos de Wii "
                    + "(ISO o WBFS). Se buscan también las subcarpetas.");
            emptyText.setVisibility(View.VISIBLE);
        } else {
            String seg = tree.getLastPathSegment();
            libraryText.setText("Biblioteca: " + (seg == null ? tree.toString() : Uri.decode(seg)));
            if (scanning) {
                emptyText.setText("Buscando juegos…");
                emptyText.setVisibility(View.VISIBLE);
            } else if (games.isEmpty()) {
                emptyText.setText("No se encontraron juegos de Wii (.iso / .wbfs) en esta carpeta.");
                emptyText.setVisibility(View.VISIBLE);
            } else {
                emptyText.setVisibility(View.GONE);
            }
        }
    }

    private void chooseFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            prefs.edit().putString(KEY_TREE, u.toString()).apply();
            tree = u;
            scan();
        }
    }

    private void scan() {
        if (tree == null) {
            Toast.makeText(this, "Primero elige la carpeta de tus juegos.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (scanning) return;
        scanning = true;
        games.clear();
        labels.clear();
        adapter.notifyDataSetChanged();
        updateLibraryText();
        final Uri t = tree;
        new Thread(() -> {
            int[] skipped = new int[1];
            final List<Game> found = LibraryScanner.scan(getContentResolver(), t, skipped);
            runOnUiThread(() -> {
                scanning = false;
                games.clear();
                games.addAll(found);
                labels.clear();
                for (Game g : games) labels.add(g.label());
                adapter.notifyDataSetChanged();
                listView.clearChoices();
                updateLibraryText();
                if (skipped[0] > 0) {
                    Toast.makeText(this, skipped[0] + " archivo(s) ignorado(s): no son discos de Wii legibles.",
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "wbm-scan").start();
    }

    private void selectAll() {
        boolean all = listView.getCheckedItemCount() == games.size() && !games.isEmpty();
        for (int i = 0; i < games.size(); i++) listView.setItemChecked(i, !all);
    }

    private void showInfo(Game g) {
        String msg = "ID: " + g.disc.id
                + "\nTítulo: " + g.displayTitle()
                + "\nRegión: " + g.disc.region()
                + "\nFormato: " + (g.wbfs ? "WBFS" : "ISO")
                + (g.parts.size() > 1 ? " (" + g.parts.size() + " partes)" : "")
                + "\nTamaño: " + Fmt.size(g.totalSize)
                + "\nArchivo: " + g.fileName;
        new AlertDialog.Builder(this).setTitle("Información").setMessage(msg)
                .setPositiveButton("Cerrar", null).show();
    }

    private void startConversion() {
        if (tree == null) {
            Toast.makeText(this, "Primero elige la carpeta de tus juegos.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ConversionService.running) {
            Toast.makeText(this, "Ya hay una conversión en curso.", Toast.LENGTH_SHORT).show();
            return;
        }
        SparseBooleanArray checked = listView.getCheckedItemPositions();
        final List<ConversionService.Job> jobs = new ArrayList<>();
        int toWbfs = 0;
        int toIso = 0;
        for (int i = 0; i < games.size(); i++) {
            if (!checked.get(i)) continue;
            Game g = games.get(i);
            jobs.add(new ConversionService.Job(g, tree, optLayout.isChecked(), optSplit.isChecked(),
                    optPad.isChecked()));
            if (g.wbfs) toIso++; else toWbfs++;
        }
        if (jobs.isEmpty()) {
            Toast.makeText(this, "Marca al menos un juego.", Toast.LENGTH_SHORT).show();
            return;
        }
        String msg = (toWbfs > 0 ? toWbfs + " ISO → WBFS\n" : "") + (toIso > 0 ? toIso + " WBFS → ISO\n" : "")
                + "\nLos archivos originales no se borran. La conversión sigue en segundo plano.";
        new AlertDialog.Builder(this).setTitle("Convertir " + jobs.size() + " juego(s)").setMessage(msg)
                .setPositiveButton("Empezar", (d, w) -> launch(jobs))
                .setNegativeButton("Cancelar", null).show();
    }

    private void launch(List<ConversionService.Job> jobs) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        ConversionService.enqueue(jobs);
        startForegroundService(new Intent(this, ConversionService.class));
        listView.clearChoices();
        adapter.notifyDataSetChanged();
        refreshProgress();
    }

    private void refreshProgress() {
        boolean running = ConversionService.running;
        convertBtn.setEnabled(!running);
        cancelBtn.setEnabled(running);
        if (running) {
            long t = ConversionService.total;
            long d = ConversionService.done;
            int permille = t > 0 ? (int) (d * 1000 / t) : 0;
            progressBar.setIndeterminate(t <= 0);
            progressBar.setProgress(permille);
            progressText.setText("(" + ConversionService.jobIndex + "/" + ConversionService.jobCount + ") "
                    + ConversionService.current + (t > 0 ? " — " + (permille / 10) + " %" : ""));
        } else {
            progressBar.setIndeterminate(false);
            progressBar.setProgress(0);
            progressText.setText(ConversionService.jobCount > 0 ? "Terminado." : "");
        }
        StringBuilder sb = new StringBuilder();
        for (String r : ConversionService.results) sb.append(r).append('\n');
        String log = sb.toString();
        if (!log.contentEquals(logText.getText())) logText.setText(log);
        if (wasRunning && !running) scan();
        wasRunning = running;
    }
}
