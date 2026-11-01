package com.wbm.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pantalla principal: destino, biblioteca de juegos (ISO/RVZ y WBFS por separado), conversión y pestaña de donar. */
public class MainActivity extends Activity {
    private static final int REQ_DEST = 1;
    private static final int REQ_NOTIF = 2;
    private static final int REQ_ADD_FILES = 3;
    private static final int REQ_SCAN_TREE = 4;
    private static final String PREFS = "wbm";
    private static final String KEY_TREE = "tree"; // guarda el destino elegido (USB/SD/carpeta)
    private static final String BBVA_ACCOUNT = "012 180 01538385555 8";

    // --- Ambientación estilo menú de Wii: fondo celeste claro, botones blancos redondeados con
    // borde y texto azul, como los canales del menú de la consola. ---
    private static final int BG_WII = Color.parseColor("#BFE6FB");
    private static final int CARD_WHITE = Color.parseColor("#F2FBFF");
    private static final int BTN_FILL = Color.parseColor("#FFFFFF");
    private static final int BTN_BORDER = Color.parseColor("#5FB6E8");
    private static final int BTN_TEXT = Color.parseColor("#0B5A86");
    private static final int ACCENT_BLUE = Color.parseColor("#0B5A86");

    /** Una de las dos listas de juegos (ISO/RVZ por un lado, WBFS por otro), como en la app de Windows.
     *  "master" guarda TODOS los juegos agregados a esta sección; "list" es lo que se ve y se puede
     *  marcar ahora mismo (después de aplicar la búsqueda y el orden), para que buscar/ordenar nunca
     *  borre juegos de verdad, solo cambie qué se muestra. */
    private final class Section {
        final List<Game> master = new ArrayList<>();
        final List<Game> list = new ArrayList<>();
        final Set<Integer> selected = new HashSet<>();
        GameAdapter adapter;
        GridView grid;
        TextView empty;
        String emptyMsg;

        void rebuild() {
            int cardWidth = grid.getWidth() > 0 ? grid.getWidth() / 3 : dp(100);
            adapter = new GameAdapter(MainActivity.this, list, selected, cardWidth, MainActivity.this::updateConvertLabel,
                    pos -> showInfo(list.get(pos)));
            grid.setAdapter(adapter);
            empty.setText(list.isEmpty() ? emptyMsg : "");
            empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        }

        /** Recalcula "list" a partir de "master" con la búsqueda y el orden actuales. Al cambiar lo
         *  que se ve, las posiciones ya no corresponden a lo marcado antes, así que se desmarca todo
         *  (es lo normal: no se puede convertir algo que ya no se ve en la pantalla). */
        void applyFilter(String query, Comparator<Game> sort) {
            list.clear();
            String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
            for (Game g : master) {
                if (q.isEmpty() || g.displayTitle().toLowerCase(Locale.ROOT).contains(q)
                        || g.disc.id.toLowerCase(Locale.ROOT).contains(q)) {
                    list.add(g);
                }
            }
            if (sort != null) list.sort(sort);
            selected.clear();
            rebuild();
        }

        void removeSelected() {
            List<Integer> idx = new ArrayList<>(selected);
            idx.sort((a, b) -> b - a);
            for (int pos : idx) {
                Game g = list.remove(pos);
                master.remove(g);
            }
            selected.clear();
        }
    }

    private SharedPreferences prefs;
    /** Carpeta, USB (OTG) o SD donde se guardan los juegos convertidos. Se mantiene elegida entre sesiones. */
    private Uri destination;

    private final Section isoSection = new Section();
    private final Section wbfsSection = new Section();

    private LinearLayout libraryContainer;
    private LinearLayout settingsContainer;
    private LinearLayout donateContainer;
    private Button notifBtn;
    private Button fabAdd;
    private LinearLayout drawerPanel;
    private View scrim;
    private boolean drawerOpen = false;
    private int drawerWidthPx;
    private EditText searchBox;
    private Button filterBtn;
    private LinearLayout isoBlock;
    private LinearLayout wbfsBlock;

    /** Qué formatos se muestran (el botón "Todos"/"ISO-RVZ"/"WBFS" de la pantalla principal). */
    private enum FilterMode { ALL, ISO_RVZ, WBFS }
    private FilterMode filterMode = FilterMode.ALL;
    private String searchQuery = "";
    private Comparator<Game> currentSort = null;
    // Estado del botón de notificaciones: avisa tanto de actualizaciones como de errores de
    // conversión (lo que pidió el usuario), dando prioridad visual al error (rojo) sobre el
    // aviso de actualización (amarillo) porque es lo más urgente de los dos.
    private boolean updateAlertPending = false;
    private boolean errorAlertPending = false;
    private String pendingUpdateVersion = null;
    private final StringBuilder recentErrorsLog = new StringBuilder();
    private int lastResultsSeen = 0;

    private TextView destinationText;
    private TextView storageStatusText;
    private CheckBox optLayout;
    private CheckBox optSplit;
    private CheckBox optPad;
    private Button convertBtn;
    private Button cancelBtn;
    private TextView progressText;
    private ProgressBar progressBar;
    private TextView logText;

    private StorageCheck.Info storageInfo;

    private final Handler handler = new Handler(Looper.getMainLooper());
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
        isoSection.rebuild();
        wbfsSection.rebuild();
        updateDestinationText();
        // En silencio: si no hay nada nuevo, o falla la consulta (sin internet, etc.), no molesta
        // con ningún aviso. Solo avisa (con el diálogo, y marcando el botón de arriba) cuando sí hay
        // una versión más nueva publicada en GitHub.
        UpdateChecker.checkAndNotify(this, false, (latest, htmlUrl) -> {
            pendingUpdateVersion = latest;
            updateAlertPending = true;
            updateNotifBadge();
        });
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

    /** Botón compacto (el tamaño grande de Android sobraba espacio que ahora usan las carátulas):
     *  una tarjeta blanca redondeada con borde celeste, como los canales del menú de Wii. */
    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setTextSize(13);
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BTN_FILL);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), BTN_BORDER);
        b.setBackground(bg);
        b.setTextColor(BTN_TEXT);
        return b;
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(BG_WII);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        // --- Barra de arriba, como en cualquier app de Android: menú (para ir a Biblioteca/Ajustes/
        // Donar), título centrado, botón de notificaciones (avisa de actualizaciones y errores) y
        // acceso directo a Ajustes. ---
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(6));

        Button menuBtn = button("☰", v -> toggleDrawer());
        TextView title = new TextView(this);
        title.setText("Wii Backup Manager");
        title.setTextSize(15);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ACCENT_BLUE);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        notifBtn = button("🔔 Novedades", v -> onNotifClicked());
        Button gearBtn = button("⚙", v -> showTab(1));
        header.addView(menuBtn);
        header.addView(title, weight());
        header.addView(notifBtn);
        header.addView(gearBtn);
        root.addView(header);

        libraryContainer = buildLibraryTab();
        settingsContainer = buildSettingsTab();
        donateContainer = buildDonateTab();
        root.addView(libraryContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(settingsContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(donateContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rootFrame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Botón flotante "+" (solo visible en Biblioteca): abre el menú para agregar un juego o
        // escanear una carpeta entera, en vez de dos botones grandes ocupando espacio fijo.
        fabAdd = new Button(this);
        fabAdd.setText("+");
        fabAdd.setAllCaps(false);
        fabAdd.setTextSize(22);
        fabAdd.setTextColor(Color.WHITE);
        fabAdd.setMinimumHeight(0);
        fabAdd.setMinimumWidth(0);
        GradientDrawable fabBg = new GradientDrawable();
        fabBg.setShape(GradientDrawable.OVAL);
        fabBg.setColor(ACCENT_BLUE);
        fabAdd.setBackground(fabBg);
        fabAdd.setOnClickListener(v -> showAddMenu());
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(dp(56), dp(56));
        fabParams.gravity = Gravity.BOTTOM | Gravity.END;
        fabParams.setMargins(0, 0, dp(16), dp(16));
        rootFrame.addView(fabAdd, fabParams);

        // --- Panel deslizante del botón "☰": en vez de un menú emergente, se desliza desde la
        // izquierda por encima de todo (con una sombra semitransparente detrás que lo cierra al
        // tocarla), como el menú de hamburguesa de las apps de Android. ---
        drawerWidthPx = dp(230);

        scrim = new View(this);
        scrim.setBackgroundColor(Color.argb(140, 0, 0, 0));
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> closeDrawer());
        rootFrame.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawerPanel = new LinearLayout(this);
        drawerPanel.setOrientation(LinearLayout.VERTICAL);
        drawerPanel.setPadding(dp(14), dp(20), dp(14), dp(14));
        GradientDrawable drawerBg = new GradientDrawable();
        drawerBg.setColor(CARD_WHITE);
        drawerPanel.setBackground(drawerBg);
        drawerPanel.setTranslationX(-drawerWidthPx);

        TextView drawerTitle = new TextView(this);
        drawerTitle.setText("Wii Backup Manager");
        drawerTitle.setTextSize(15);
        drawerTitle.setTypeface(null, Typeface.BOLD);
        drawerTitle.setTextColor(ACCENT_BLUE);
        drawerTitle.setPadding(dp(6), 0, dp(6), dp(16));
        drawerPanel.addView(drawerTitle);
        drawerPanel.addView(drawerMenuItem("📚 Biblioteca", 0));
        drawerPanel.addView(drawerMenuItem("⚙ Ajustes", 1));
        drawerPanel.addView(drawerMenuItem("💙 Donar", 2));

        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(
                drawerWidthPx, ViewGroup.LayoutParams.MATCH_PARENT);
        drawerParams.gravity = Gravity.START;
        rootFrame.addView(drawerPanel, drawerParams);

        setContentView(rootFrame);
        showTab(0);
    }

    /** Una fila tocable del panel deslizante: va a la pestaña indicada y cierra el panel. */
    private TextView drawerMenuItem(String text, int tab) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextSize(15);
        row.setTextColor(BTN_TEXT);
        row.setPadding(dp(6), dp(14), dp(6), dp(14));
        row.setOnClickListener(v -> {
            showTab(tab);
            closeDrawer();
        });
        return row;
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) {
            closeDrawer();
        } else {
            super.onBackPressed();
        }
    }

    private void toggleDrawer() {
        if (drawerOpen) closeDrawer();
        else openDrawer();
    }

    private void openDrawer() {
        drawerOpen = true;
        scrim.setAlpha(0f);
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(200).start();
        drawerPanel.animate().translationX(0).setDuration(220).start();
    }

    private void closeDrawer() {
        drawerOpen = false;
        scrim.animate().alpha(0f).setDuration(200).withEndAction(() -> scrim.setVisibility(View.GONE)).start();
        drawerPanel.animate().translationX(-drawerWidthPx).setDuration(220).start();
    }

    /** 0 = Biblioteca, 1 = Ajustes (opciones de conversión + registro de errores), 2 = Donar. */
    private void showTab(int tab) {
        libraryContainer.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        settingsContainer.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        donateContainer.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        if (fabAdd != null) fabAdd.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
    }

    /** Menú del botón flotante "+": agregar un archivo suelto o escanear una carpeta entera. */
    private void showAddMenu() {
        new AlertDialog.Builder(this)
                .setItems(new CharSequence[]{"Agregar juego (ISO/RVZ beta/WBFS)", "Escanear carpeta"},
                        (d, which) -> { if (which == 0) addGameFiles(); else scanFolder(); })
                .show();
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(ACCENT_BLUE);
        t.setPadding(0, dp(8), 0, dp(2));
        return t;
    }

    /** Construye el bloque de una sección (ISO/RVZ o WBFS): título, cuadrícula propia y mensaje vacío. */
    private LinearLayout buildSectionBlock(Section s, String title, String emptyMsg) {
        s.emptyMsg = emptyMsg;
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.addView(sectionTitle(title));

        FrameLayout listWrap = new FrameLayout(this);
        s.grid = new GridView(this);
        s.grid.setNumColumns(3);
        s.grid.setVerticalSpacing(dp(4));
        s.grid.setHorizontalSpacing(dp(4));
        s.empty = new TextView(this);
        s.empty.setGravity(Gravity.CENTER);
        s.empty.setTextSize(12);
        s.empty.setPadding(dp(12), dp(8), dp(12), dp(8));
        listWrap.addView(s.grid, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listWrap.addView(s.empty, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        block.addView(listWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return block;
    }

    private LinearLayout buildLibraryTab() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // --- Destino (USB OTG / SD / carpeta): queda elegido y se recuerda entre sesiones, como en
        // Wii Backup Manager. Toda la tarjeta es tocable para cambiarlo, como la barra de "unidad
        // actual" de la app de Windows.
        LinearLayout destBox = new LinearLayout(this);
        destBox.setOrientation(LinearLayout.VERTICAL);
        destBox.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable destBg = new GradientDrawable();
        destBg.setColor(CARD_WHITE);
        destBg.setCornerRadius(dp(12));
        destBg.setStroke(dp(1), BTN_BORDER);
        destBox.setBackground(destBg);
        destBox.setOnClickListener(v -> chooseDestination());

        destinationText = new TextView(this);
        destinationText.setTextSize(13);
        destinationText.setTextColor(BTN_TEXT);
        destinationText.setSingleLine(true);
        destinationText.setEllipsize(TextUtils.TruncateAt.END);
        destBox.addView(destinationText);

        storageStatusText = new TextView(this);
        storageStatusText.setTextSize(11);
        storageStatusText.setTextColor(BTN_TEXT);
        storageStatusText.setPadding(0, dp(1), 0, 0);
        destBox.addView(storageStatusText);
        col.addView(destBox);

        // --- Buscar y filtrar: por nombre/ID, o por formato (todos / solo ISO-RVZ / solo WBFS).
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(0, dp(8), 0, dp(4));

        searchBox = new EditText(this);
        searchBox.setHint("Buscar juegos…");
        searchBox.setTextSize(13);
        searchBox.setTextColor(BTN_TEXT);
        searchBox.setSingleLine(true);
        searchBox.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(CARD_WHITE);
        searchBg.setCornerRadius(dp(10));
        searchBg.setStroke(dp(1), BTN_BORDER);
        searchBox.setBackground(searchBg);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                searchQuery = s.toString();
                applyFilters();
            }
        });
        searchRow.addView(searchBox, weight());

        filterBtn = button("Todos", v -> showFilterMenu());
        LinearLayout.LayoutParams filterLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        filterLp.setMargins(dp(6), 0, 0, 0);
        searchRow.addView(filterBtn, filterLp);
        col.addView(searchRow);

        // --- Orden: por nombre, tamaño o región, como los botones de encabezado de la app de Windows.
        LinearLayout sortRow = new LinearLayout(this);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        sortRow.setPadding(0, 0, 0, dp(4));
        sortRow.addView(button("A-Z", v -> setSort(Comparator.comparing(
                g -> g.displayTitle().toLowerCase(Locale.ROOT)))));
        sortRow.addView(button("Tamaño", v -> setSort(Comparator.<Game>comparingLong(g -> g.totalSize).reversed())));
        sortRow.addView(button("Región", v -> setSort(Comparator.comparing(g -> g.disc.region()))));
        col.addView(sortRow);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("Marcar todo", v -> selectAll()), weight());
        row.addView(button("Quitar de la lista", v -> removeSelected()), weight());
        col.addView(row);

        // Dos secciones separadas, como en la app de Windows: ISO/RVZ (origen) y WBFS (ya convertidos).
        // Son lo más importante de esta pestaña (para que se vean bien las carátulas), así que todo
        // lo que no hace falta ver todo el tiempo (opciones de conversión, registro de errores) se
        // movió a la pestaña «Ajustes».
        // El soporte de RVZ es beta: no se probó todavía con todos los archivos reales posibles, así
        // que se marca para que quede claro que la conversión puede fallar con algunos juegos.
        isoBlock = buildSectionBlock(isoSection, "ISO / RVZ (beta, puede fallar)  →  se convierten a WBFS",
                "Sin juegos ISO/RVZ. Toca el «+» para agregar uno o escanear una carpeta.");
        wbfsBlock = buildSectionBlock(wbfsSection, "WBFS  →  se pueden pasar a ISO", "Sin juegos WBFS.");
        col.addView(isoBlock, weightTall());
        col.addView(wbfsBlock, weightTall());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        convertBtn = button("Convertir seleccionados", v -> startConversion());
        cancelBtn = button("Cancelar", v -> ConversionService.cancelRequested = true);
        cancelBtn.setEnabled(false);
        actions.addView(convertBtn, weight());
        actions.addView(cancelBtn, weight());
        col.addView(actions);

        // Línea de progreso compacta: barra delgada + texto en una sola fila (en vez de dos filas
        // completas), para dejar el máximo espacio posible a las cuadrículas de arriba (y que se
        // vean mejor las carátulas) y para no estrechar el botón de copiar registro que va justo
        // debajo.
        LinearLayout progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressRow.addView(progressBar, new LinearLayout.LayoutParams(0, dp(6), 1f));
        progressText = new TextView(this);
        progressText.setTextSize(11);
        progressText.setSingleLine(true);
        progressText.setEllipsize(TextUtils.TruncateAt.END);
        progressText.setPadding(dp(6), 0, 0, 0);
        progressRow.addView(progressText, new LinearLayout.LayoutParams(
                dp(130), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams progressRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressRowParams.setMargins(0, dp(2), 0, dp(2));
        col.addView(progressRow, progressRowParams);

        return col;
    }

    /** Pestaña «Ajustes»: opciones de conversión y registro de errores — no hace falta verlos todo
     *  el tiempo, así que viven aparte y dejan más espacio en «Biblioteca» para las carátulas. */
    private LinearLayout buildSettingsTab() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(8), 0, 0);

        col.addView(sectionTitle("Opciones de conversión"));
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

        col.addView(sectionTitle("Actualizaciones"));
        col.addView(button("Buscar actualizaciones ahora", v -> UpdateChecker.checkAndNotify(this, true)));

        col.addView(sectionTitle("Utilidades"));
        TextView formatNote = new TextView(this);
        formatNote.setTextSize(12);
        formatNote.setTextColor(BTN_TEXT);
        formatNote.setText("Ninguna app normal de Android puede formatear una memoria ella misma (hace falta "
                + "root). Este botón solo abre los ajustes de almacenamiento del teléfono para que tú elijas tu "
                + "USB OTG o tarjeta SD ahí y le des «Formatear» — así nunca se puede tocar por accidente el "
                + "almacenamiento interno, porque la app nunca formatea nada directamente.");
        formatNote.setPadding(0, dp(2), 0, dp(4));
        col.addView(formatNote);
        col.addView(button("Formatear USB/SD a FAT32 (abre Ajustes)", v -> openStorageSettings()));

        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setOrientation(LinearLayout.HORIZONTAL);
        logHeader.setGravity(Gravity.CENTER_VERTICAL);
        logHeader.setPadding(0, dp(16), 0, 0);
        TextView logTitle = sectionTitle("Registro de conversión / errores");
        logHeader.addView(logTitle, weight());
        logHeader.addView(button("Copiar registro", v -> copyLog()));
        col.addView(logHeader);

        ScrollView logScroll = new ScrollView(this);
        logText = new TextView(this);
        logText.setTextSize(12);
        logText.setTextIsSelectable(true); // para poder marcar y copiar un error puntual a mano
        logScroll.addView(logText);
        col.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

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

    private LinearLayout.LayoutParams weightTall() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    }

    private void updateDestinationText() {
        if (destination == null) {
            destinationText.setText("Unidad actual: (sin elegir) — toca aquí para elegir tu USB OTG, SD o carpeta");
            storageStatusText.setText("");
        } else {
            String seg = destination.getLastPathSegment();
            String name = seg == null ? destination.toString() : Uri.decode(seg);
            destinationText.setText("Unidad actual: " + name + "  (toca para cambiar)");
            storageStatusText.setText(storageInfo != null ? storageInfo.statusText() : "");
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
        // No se restringe por tipo MIME: muchos administradores de archivos no marcan los .iso/.rvz
        // como "application/octet-stream" (o usan otro tipo, o ninguno), así que con esa restricción
        // aparecían bloqueados en el selector. En su lugar se deja elegir cualquier archivo y se
        // descarta por extensión al leerlo (ver LibraryScanner.fromSingleDocument).
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(Intent.createChooser(i, "Elegir ISO, RVZ o WBFS"), REQ_ADD_FILES);
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

    /** Agrega cada juego a su sección (ISO/RVZ o WBFS) evitando duplicados (mismo archivo ya presente). */
    private void addGamesToList(List<Game> found) {
        if (found.isEmpty()) return;
        Set<Uri> already = new LinkedHashSet<>();
        for (Game g : isoSection.master) already.add(g.parts.get(0));
        for (Game g : wbfsSection.master) already.add(g.parts.get(0));
        int added = 0;
        for (Game g : found) {
            if (!already.add(g.parts.get(0))) continue;
            (g.kind == Game.Kind.WBFS ? wbfsSection : isoSection).master.add(g);
            added++;
        }
        applyFilters();
        if (added > 0) {
            Toast.makeText(this, added + " juego(s) agregado(s).", Toast.LENGTH_SHORT).show();
        }
    }

    /** Vuelve a calcular qué se ve en cada sección según la búsqueda, el formato elegido y el
     *  orden actual, sin perder los juegos que no coinciden (siguen en "master"). */
    private void applyFilters() {
        isoSection.applyFilter(searchQuery, currentSort);
        wbfsSection.applyFilter(searchQuery, currentSort);
        if (isoBlock != null) {
            isoBlock.setVisibility(filterMode == FilterMode.WBFS ? View.GONE : View.VISIBLE);
        }
        if (wbfsBlock != null) {
            wbfsBlock.setVisibility(filterMode == FilterMode.ISO_RVZ ? View.GONE : View.VISIBLE);
        }
        updateConvertLabel();
    }

    private void setSort(Comparator<Game> sort) {
        currentSort = sort;
        applyFilters();
    }

    /** Botón "Todos"/filtro: elegir si se muestran los dos formatos o solo uno. */
    private void showFilterMenu() {
        new AlertDialog.Builder(this)
                .setItems(new CharSequence[]{"Todos los juegos", "Solo ISO / RVZ", "Solo WBFS"}, (d, which) -> {
                    filterMode = which == 1 ? FilterMode.ISO_RVZ : which == 2 ? FilterMode.WBFS : FilterMode.ALL;
                    filterBtn.setText(which == 1 ? "ISO/RVZ" : which == 2 ? "WBFS" : "Todos");
                    applyFilters();
                })
                .show();
    }

    /** Quita de la lista los juegos marcados (no borra los archivos originales). */
    private void removeSelected() {
        if (isoSection.selected.isEmpty() && wbfsSection.selected.isEmpty()) {
            Toast.makeText(this, "Marca al menos un juego para quitarlo de la lista.", Toast.LENGTH_SHORT).show();
            return;
        }
        isoSection.removeSelected();
        wbfsSection.removeSelected();
        isoSection.rebuild();
        wbfsSection.rebuild();
        updateConvertLabel();
    }

    private void selectAll() {
        boolean all = isoSection.selected.size() == isoSection.list.size()
                && wbfsSection.selected.size() == wbfsSection.list.size()
                && (!isoSection.list.isEmpty() || !wbfsSection.list.isEmpty());
        isoSection.selected.clear();
        wbfsSection.selected.clear();
        if (!all) {
            for (int i = 0; i < isoSection.list.size(); i++) isoSection.selected.add(i);
            for (int i = 0; i < wbfsSection.list.size(); i++) wbfsSection.selected.add(i);
        }
        if (isoSection.adapter != null) isoSection.adapter.notifyDataSetChanged();
        if (wbfsSection.adapter != null) wbfsSection.adapter.notifyDataSetChanged();
        updateConvertLabel();
    }

    private void updateConvertLabel() {
        int n = isoSection.selected.size() + wbfsSection.selected.size();
        convertBtn.setText(n == 0 ? "Convertir seleccionados" : "Convertir " + n + " juego(s)");
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
        for (int pos : isoSection.selected) {
            jobs.add(new ConversionService.Job(isoSection.list.get(pos), destination, optLayout.isChecked(),
                    optSplit.isChecked(), optPad.isChecked()));
        }
        for (int pos : wbfsSection.selected) {
            jobs.add(new ConversionService.Job(wbfsSection.list.get(pos), destination, optLayout.isChecked(),
                    optSplit.isChecked(), optPad.isChecked()));
        }
        if (jobs.isEmpty()) {
            Toast.makeText(this, "Marca al menos un juego (toca su carátula).", Toast.LENGTH_SHORT).show();
            return;
        }
        int toWbfs = isoSection.selected.size();
        int toIso = wbfsSection.selected.size();
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
        isoSection.selected.clear();
        wbfsSection.selected.clear();
        if (isoSection.adapter != null) isoSection.adapter.notifyDataSetChanged();
        if (wbfsSection.adapter != null) wbfsSection.adapter.notifyDataSetChanged();
        updateConvertLabel();
        refreshProgress();
    }

    /** Abre los ajustes de almacenamiento del teléfono para formatear una USB/SD a mano. Android no
     *  le da a ninguna app normal permiso para formatear memorias (hace falta root o ser una app del
     *  sistema), así que esto solo navega a la pantalla correcta — nunca formatea nada por sí misma,
     *  y por lo tanto nunca puede tocar el almacenamiento interno por accidente. */
    private void openStorageSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
                Toast.makeText(this, "Busca «Almacenamiento» → tu USB o SD → «Formatear».", Toast.LENGTH_LONG).show();
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "No se encontraron los ajustes de almacenamiento en este teléfono.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Pinta el botón de notificaciones según lo que haya pendiente: un error de conversión (rojo,
     *  máxima prioridad porque es lo más urgente) o una actualización disponible (amarillo); si no
     *  hay nada pendiente, vuelve al estilo normal. */
    private void updateNotifBadge() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), BTN_BORDER);
        if (errorAlertPending) {
            notifBtn.setText("🔔 ¡Hubo un error!");
            bg.setColor(Color.parseColor("#F8B4B4")); // rojo de aviso
        } else if (updateAlertPending) {
            notifBtn.setText("🔔 ¡Actualización v" + pendingUpdateVersion + "!");
            bg.setColor(Color.parseColor("#FFF3B0")); // amarillo de aviso
        } else {
            notifBtn.setText("🔔 Novedades");
            bg.setColor(BTN_FILL);
        }
        notifBtn.setBackground(bg);
    }

    /** Al tocar el botón: si hay errores recientes sin ver, los muestra primero (quitando el
     *  aviso); si no, va directo al registro de novedades de cada versión. El aviso de
     *  actualización se apaga siempre al tocar el botón, porque el propio registro de novedades
     *  ya incluye la versión más nueva. */
    private void onNotifClicked() {
        boolean hadErrors = errorAlertPending;
        updateAlertPending = false;
        errorAlertPending = false;
        updateNotifBadge();
        if (hadErrors) {
            new AlertDialog.Builder(this)
                    .setTitle("Errores recientes de conversión")
                    .setMessage(recentErrorsLog.toString())
                    .setPositiveButton("Ver novedades de cada versión", (d, w) -> UpdateChecker.showChangelog(this))
                    .setNegativeButton("Cerrar", null)
                    .show();
        } else {
            UpdateChecker.showChangelog(this);
        }
    }

    private void copyLog() {
        CharSequence text = logText.getText();
        if (text == null || text.length() == 0) {
            Toast.makeText(this, "Todavía no hay nada en el registro.", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Registro de conversión", text));
        Toast.makeText(this, "Registro copiado.", Toast.LENGTH_SHORT).show();
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

        // Revisa solo las líneas nuevas desde la última vez para no repetir el aviso del mismo
        // error una y otra vez mientras el botón sigue marcado.
        java.util.List<String> results = ConversionService.results;
        boolean newError = false;
        for (int i = lastResultsSeen; i < results.size(); i++) {
            String r = results.get(i);
            if (r.startsWith("ERROR")) {
                recentErrorsLog.append(r).append("\n\n");
                newError = true;
            }
        }
        lastResultsSeen = results.size();
        if (newError) {
            errorAlertPending = true;
            updateNotifBadge();
        }
    }
}
