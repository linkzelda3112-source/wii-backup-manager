package com.wbm.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pantalla principal: destino, biblioteca de juegos con carátulas, opciones, conversión y pestaña de donar. */
public class MainActivity extends Activity {
    private static final int REQ_DEST = 1;
    private static final int REQ_NOTIF = 2;
    private static final int REQ_ADD_FILES = 3;
    private static final int REQ_SCAN_TREE = 4;
    private static final String PREFS = "wbm";
    private static final String KEY_TREE = "tree"; // guarda el destino elegido (USB/SD/carpeta)
    private static final String BBVA_ACCOUNT = "012 180 01538385555 8";

    private SharedPreferences prefs;
    /** Carpeta, USB (OTG) o SD donde se guardan los juegos convertidos. Se mantiene elegida entre sesiones. */
    private Uri destination;
    private final List<Game> games = new ArrayList<>();
    private final Set<Integer> selected = new HashSet<>();
    private GameAdapter adapter;

    private LinearLayout libraryContainer;
    private LinearLayout donateContainer;
    private Button tabLibraryBtn;
    private Button tabDonateBtn;

    private TextView destinationText;
    private TextView storageStatusText;
    private TextView emptyText;
    private GridView gridView;
    private CheckBox optLayout;
    private CheckBox optSplit;
    private CheckBox optPad;
    private Button convertBtn;
    private Button cancelBtn;
    private Button removeBtn;
    private TextView progressText;
    private ProgressBar progressBar;
    private TextView logText;

    private StorageCheck.Info storageInfo;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean wasRunning;
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
                destination = u;
                onDestinationChosen();
            }
        }
        rebuildAdapter();
        updateDestinationText();
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

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabLibraryBtn = button("Biblioteca", v -> showTab(true));
        tabDonateBtn = button("Donar", v -> showTab(false));
        tabs.addView(tabLibraryBtn, weight());
        tabs.addView(tabDonateBtn, weight());
        root.addView(tabs);

        libraryContainer = buildLibraryTab();
        donateContainer = buildDonateTab();
        root.addView(libraryContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(donateContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        showTab(true);
    }

    private void showTab(boolean library) {
        libraryContainer.setVisibility(library ? View.VISIBLE : View.GONE);
        donateContainer.setVisibility(library ? View.GONE : View.VISIBLE);
        tabLibraryBtn.setEnabled(!library);
        tabDonateBtn.setEnabled(library);
    }

    private LinearLayout buildLibraryTab() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // --- Destino (USB OTG / SD / carpeta): queda elegido y se recuerda entre sesiones, como en
        // Wii Backup Manager. Siempre visible arriba, independiente de los juegos agregados.
        LinearLayout destBox = new LinearLayout(this);
        destBox.setOrientation(LinearLayout.VERTICAL);
        destBox.setPadding(dp(8), dp(8), dp(8), dp(8));
        destBox.setBackgroundColor(Color.argb(28, 33, 150, 243));

        destinationText = new TextView(this);
        destinationText.setTextSize(14);
        destBox.addView(destinationText);

        storageStatusText = new TextView(this);
        storageStatusText.setTextSize(12);
        storageStatusText.setPadding(0, dp(2), 0, dp(4));
        destBox.addView(storageStatusText);

        destBox.addView(button("Elegir destino (USB OTG / SD / carpeta)", v -> chooseDestination()));
        col.addView(destBox);

        // --- Juegos a convertir: se agregan uno por uno (ISO/RVZ/WBFS) o escaneando una carpeta entera.
        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setPadding(0, dp(8), 0, dp(4));
        addRow.addView(button("Agregar juego (ISO/RVZ)", v -> addGameFiles()), weight());
        addRow.addView(button("Escanear carpeta", v -> scanFolder()), weight());
        col.addView(addRow);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("Marcar todo", v -> selectAll()), weight());
        removeBtn = button("Quitar de la lista", v -> removeSelected());
        row.addView(removeBtn, weight());
        col.addView(row);

        FrameLayout listWrap = new FrameLayout(this);
        gridView = new GridView(this);
        gridView.setNumColumns(3);
        gridView.setVerticalSpacing(dp(4));
        gridView.setHorizontalSpacing(dp(4));
        emptyText = new TextView(this);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(dp(16), dp(16), dp(16), dp(16));
        listWrap.addView(gridView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listWrap.addView(emptyText, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(listWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        optLayout = new CheckBox(this);
        optLayout.setText("Crear carpetas para cargador USB: wbfs/Título [ID]/ID.wbfs y games/Título [ID]/game.iso");
        optLayout.setChecked(true);
        optSplit = new CheckBox(this);
        optSplit.setText("Dividir WBFS en partes de 4 GB (necesario en FAT32)");
        optPad = new CheckBox(this);
        optPad.setText("Al crear ISO, rellenar hasta el tamaño estándar de Wii");
        optPad.setChecked(true);
        col.addView(optLayout);
        col.addView(optSplit);
        col.addView(optPad);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        convertBtn = button("Convertir seleccionados", v -> startConversion());
        cancelBtn = button("Cancelar", v -> ConversionService.cancelRequested = true);
        cancelBtn.setEnabled(false);
        actions.addView(convertBtn, weight());
        actions.addView(cancelBtn, weight());
        col.addView(actions);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        col.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        progressText = new TextView(this);
        progressText.setTextSize(13);
        col.addView(progressText);

        ScrollView logScroll = new ScrollView(this);
        logText = new TextView(this);
        logText.setTextSize(12);
        logScroll.addView(logText);
        col.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(90)));

        return col;
    }

    private LinearLayout buildDonateTab() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(8), dp(24), dp(8), dp(8));

        TextView msg = new TextView(this);
        msg.setText("Si esta aplicación te sirvió, puedes donar lo que quieras. ¡Gracias!");
        msg.setTextSize(16);
        msg.setPadding(0, 0, 0, dp(16));
        col.addView(msg);

        TextView bank = new TextView(this);
        bank.setText("Banco: BBVA");
        bank.setTextSize(15);
        col.addView(bank);

        TextView account = new TextView(this);
        account.setText("Cuenta: " + BBVA_ACCOUNT);
        account.setTextSize(18);
        account.setPadding(0, dp(4), 0, dp(16));
        col.addView(account);

        Button copyBtn = button("Copiar número de cuenta", v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Cuenta BBVA", BBVA_ACCOUNT));
            Toast.makeText(this, "Número de cuenta copiado", Toast.LENGTH_SHORT).show();
        });
        col.addView(copyBtn);

        return col;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void updateDestinationText() {
        if (destination == null) {
            destinationText.setText("Destino: (sin elegir) — toca «Elegir destino» y marca tu USB OTG, SD o carpeta.");
            storageStatusText.setText("");
        } else {
            String seg = destination.getLastPathSegment();
            destinationText.setText("✓ Destino: " + (seg == null ? destination.toString() : Uri.decode(seg)));
            storageStatusText.setText(storageInfo != null ? storageInfo.statusText() : "");
        }
        updateEmptyText();
    }

    private void updateEmptyText() {
        if (games.isEmpty()) {
            emptyText.setText("Aún no agregaste juegos. Toca «Agregar juego (ISO/RVZ)» para elegir archivos, o "
                    + "«Escanear carpeta» para sumar todos los que haya en una carpeta de una vez.");
            emptyText.setVisibility(View.VISIBLE);
        } else {
            emptyText.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------- destino (USB / SD / carpeta)

    private void chooseDestination() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_DEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_DEST) {
            Uri u = data.getData();
            if (u == null) return;
            getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            prefs.edit().putString(KEY_TREE, u.toString()).apply();
            destination = u;
            onDestinationChosen();
        } else if (requestCode == REQ_ADD_FILES) {
            handleAddedFiles(data);
        } else if (requestCode == REQ_SCAN_TREE) {
            Uri u = data.getData();
            if (u != null) handleScanTree(u);
        }
    }

    private void onDestinationChosen() {
        storageInfo = StorageCheck.check(this, destination);
        optSplit.setChecked(storageInfo.needsSplit());
        updateDestinationText();
    }

    // ---------------------------------------------------------------- agregar juegos

    private void addGameFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(Intent.createChooser(i, "Elegir ISO o RVZ"), REQ_ADD_FILES);
    }

    private void handleAddedFiles(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData cd = data.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        new Thread(() -> {
            List<Game> added = new ArrayList<>();
            int[] skipped = new int[1];
            for (Uri u : uris) {
                try {
                    getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // algunos proveedores no lo permiten; igual se intenta leer con el permiso temporal
                }
                Game g = LibraryScanner.fromSingleDocument(getContentResolver(), u, skipped);
                if (g != null) added.add(g);
            }
            runOnUiThread(() -> {
                addGamesToList(added);
                if (skipped[0] > 0) {
                    Toast.makeText(this, skipped[0] + " archivo(s) ignorado(s): no son discos de Wii legibles.",
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "wbm-add-files").start();
    }

    private void scanFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i, REQ_SCAN_TREE);
    }

    private void handleScanTree(Uri treeUri) {
        Toast.makeText(this, "Buscando juegos…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int[] skipped = new int[1];
            List<Game> found = LibraryScanner.scan(getContentResolver(), treeUri, skipped);
            runOnUiThread(() -> {
                addGamesToList(found);
                if (skipped[0] > 0) {
                    Toast.makeText(this, skipped[0] + " archivo(s) ignorado(s): no son discos de Wii legibles.",
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "wbm-scan").start();
    }

    /** Agrega a la lista evitando duplicados (mismo archivo ya presente). */
    private void addGamesToList(List<Game> found) {
        if (found.isEmpty()) return;
        Set<Uri> already = new LinkedHashSet<>();
        for (Game g : games) already.add(g.parts.get(0));
        int added = 0;
        for (Game g : found) {
            if (already.add(g.parts.get(0))) {
                games.add(g);
                added++;
            }
        }
        rebuildAdapter();
        updateDestinationText();
        if (added > 0) {
            Toast.makeText(this, added + " juego(s) agregado(s).", Toast.LENGTH_SHORT).show();
        }
    }

    private void removeSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "Marca al menos un juego para quitarlo de la lista.", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Integer> idx = new ArrayList<>(selected);
        idx.sort((a, b) -> b - a);
        for (int pos : idx) games.remove(pos);
        selected.clear();
        rebuildAdapter();
        updateDestinationText();
        updateConvertLabel();
    }

    private void rebuildAdapter() {
        int cardWidth = gridView.getWidth() > 0 ? gridView.getWidth() / 3 : dp(100);
        adapter = new GameAdapter(this, games, selected, cardWidth, this::updateConvertLabel,
                pos -> showInfo(games.get(pos)));
        gridView.setAdapter(adapter);
        updateConvertLabel();
    }

    private void selectAll() {
        if (games.isEmpty()) return;
        boolean all = selected.size() == games.size();
        selected.clear();
        if (!all) {
            for (int i = 0; i < games.size(); i++) selected.add(i);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        updateConvertLabel();
    }

    private void updateConvertLabel() {
        convertBtn.setText(selected.isEmpty() ? "Convertir seleccionados"
                : "Convertir " + selected.size() + " juego(s)");
    }

    private void showInfo(Game g) {
        String msg = "ID: " + g.disc.id
                + "\nTítulo: " + g.displayTitle()
                + "\nRegión: " + g.disc.region()
                + "\nFormato: " + g.kindLabel()
                + (g.parts.size() > 1 ? " (" + g.parts.size() + " partes)" : "")
                + "\nTamaño: " + Fmt.size(g.totalSize)
                + "\nArchivo: " + g.fileName;
        new AlertDialog.Builder(this).setTitle("Información").setMessage(msg)
                .setPositiveButton("Cerrar", null).show();
    }

    // ---------------------------------------------------------------- conversión

    private void startConversion() {
        if (destination == null) {
            Toast.makeText(this, "Primero elige el destino (USB OTG, SD o carpeta).", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ConversionService.running) {
            Toast.makeText(this, "Ya hay una conversión en curso.", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<ConversionService.Job> jobs = new ArrayList<>();
        int toWbfs = 0;
        int toIso = 0;
        for (int pos : selected) {
            Game g = games.get(pos);
            jobs.add(new ConversionService.Job(g, destination, optLayout.isChecked(), optSplit.isChecked(),
                    optPad.isChecked()));
            if (g.kind == Game.Kind.WBFS) toIso++; else toWbfs++;
        }
        if (jobs.isEmpty()) {
            Toast.makeText(this, "Marca al menos un juego (toca su carátula).", Toast.LENGTH_SHORT).show();
            return;
        }
        String msg = (toWbfs > 0 ? toWbfs + " → WBFS (ISO/RVZ)\n" : "") + (toIso > 0 ? toIso + " WBFS → ISO\n" : "")
                + "\nSe guardan en el destino elegido. Los archivos originales no se borran. "
                + "La conversión sigue en segundo plano.";
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
        selected.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        updateConvertLabel();
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
        wasRunning = running;
    }
}
