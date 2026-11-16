package net.sourceforge.opencamera;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launcher screen for choosing a product destination. MainActivity remains the camera;
 * selecting a folder creates it when needed, saves it as active, and opens the camera.
 */
public class ProductFolderActivity extends AppCompatActivity {
    private static final int REQUEST_SELECT_ROOT = 7401;
    private static final String PAGE_PATHS = "paths";
    private static final String PAGE_FOLDERS = "folders";

    private static final int BG = Color.rgb(246, 248, 250);
    private static final int SURFACE = Color.WHITE;
    private static final int INK = Color.rgb(27, 37, 48);
    private static final int MUTED = Color.rgb(105, 117, 129);
    private static final int LINE = Color.rgb(225, 230, 236);
    private static final int ACCENT = Color.rgb(47, 156, 242);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService io;
    private LinearLayout pageContent;
    private LinearLayout pathRows;
    private LinearLayout folderRows;
    private TextView pathsTab;
    private TextView foldersTab;
    private ProgressBar pathsProgress;
    private ProgressBar foldersProgress;
    private EditText pathEditor;

    private Uri rootUri;
    private String page = PAGE_PATHS;
    private String folderPath = "";
    private String pathFilter = "";
    private String folderFilter = "";
    private String newPathValue = "";
    private String selectedPath = "";
    private ArrayList<String> discoveredPaths = new ArrayList<>();
    private ArrayList<String> childFolders = new ArrayList<>();
    private ArrayList<ProductFolderStore.FolderEntry> folderEntries = new ArrayList<>();
    private boolean pathsLoading;
    private boolean foldersLoading;
    private boolean pathsError;
    private boolean foldersError;
    private boolean launching;
    private long pathsRequest;
    private long foldersRequest;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        io = Executors.newSingleThreadExecutor();
        rootUri = readSelectedRoot();
        if (state != null) {
            page = state.getString("page", PAGE_PATHS);
            folderPath = state.getString("folderPath", "");
            pathFilter = state.getString("pathFilter", "");
            folderFilter = state.getString("folderFilter", "");
            newPathValue = state.getString("newPathValue", ProductFolderStore.getActivePath(this));
            selectedPath = state.getString("selectedPath", ProductFolderStore.getActivePath(this));
        }
        else {
            newPathValue = ProductFolderStore.getActivePath(this);
            selectedPath = newPathValue;
        }
        buildShell();
        renderPage();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reset the one-shot guard after MainActivity returns to this picker.
        launching = false;
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("page", page);
        state.putString("folderPath", folderPath);
        state.putString("pathFilter", pathFilter);
        state.putString("folderFilter", folderFilter);
        state.putString("newPathValue", newPathValue);
        state.putString("selectedPath", selectedPath);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        pathsRequest++;
        foldersRequest++;
        if (io != null)
            io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (PAGE_FOLDERS.equals(page) && !folderPath.isEmpty()) {
            int slash = folderPath.lastIndexOf('/');
            folderPath = slash < 0 ? "" : folderPath.substring(0, slash);
            renderPage();
        }
        else if (PAGE_FOLDERS.equals(page)) {
            page = PAGE_PATHS;
            renderPage();
        }
        else {
            super.onBackPressed();
        }
    }

    private void buildShell() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(BG);
        shell.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(18), dp(20), dp(14));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setPadding(dp(7), dp(7), dp(7), dp(7));
        logo.setBackground(rounded(ACCENT, ACCENT, 14));
        header.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(label(getString(R.string.app_name), 19, INK, true));
        titles.addView(label(getString(R.string.product_folder_hub_tagline), 12, MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        shell.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        pageContent = new LinearLayout(this);
        pageContent.setOrientation(LinearLayout.VERTICAL);
        pageContent.setPadding(dp(20), dp(4), dp(20), dp(20));
        scroll.addView(pageContent);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(12), dp(8), dp(12), dp(8));
        nav.setBackground(rounded(SURFACE, LINE, 0));
        pathsTab = makeTab(getString(R.string.product_folder_paths_tab), PAGE_PATHS);
        foldersTab = makeTab(getString(R.string.product_folder_folders_tab), PAGE_FOLDERS);
        nav.addView(pathsTab, new LinearLayout.LayoutParams(0, dp(48), 1f));
        nav.addView(foldersTab, new LinearLayout.LayoutParams(0, dp(48), 1f));
        shell.addView(nav);
        setContentView(shell);
    }

    private TextView makeTab(String value, String target) {
        TextView tab = label(value, 14, MUTED, true);
        tab.setGravity(Gravity.CENTER);
        tab.setOnClickListener(v -> {
            if (!target.equals(page)) {
                page = target;
                renderPage();
            }
        });
        return tab;
    }

    private void renderPage() {
        pageContent.removeAllViews();
        pathRows = null;
        folderRows = null;
        pathsProgress = null;
        foldersProgress = null;
        boolean showPaths = PAGE_PATHS.equals(page);
        pathsTab.setTextColor(showPaths ? ACCENT : MUTED);
        foldersTab.setTextColor(showPaths ? MUTED : ACCENT);
        pathsTab.setBackground(rounded(showPaths ? 0x142F9CF2 : Color.TRANSPARENT, Color.TRANSPARENT, 12));
        foldersTab.setBackground(rounded(showPaths ? Color.TRANSPARENT : 0x142F9CF2, Color.TRANSPARENT, 12));
        if (showPaths)
            renderPathsPage();
        else
            renderFoldersPage();
    }

    private void renderPathsPage() {
        pageContent.addView(label(getString(R.string.product_folder_paths_heading), 22, INK, true));
        addSpace(pageContent, 4);
        pageContent.addView(label(getString(R.string.product_folder_paths_help), 13, MUTED, false));
        addSpace(pageContent, 16);
        addRootCard(pageContent);
        if (rootUri == null) {
            addSpace(pageContent, 12);
            pageContent.addView(label(getString(R.string.product_folder_base_missing), 14, MUTED, false));
            addSpace(pageContent, 8);
            pageContent.addView(action(getString(R.string.product_folder_choose_base), true, this::chooseRoot));
            return;
        }

        addSpace(pageContent, 16);
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText search = input(getString(R.string.product_folder_search_paths));
        search.setText(pathFilter);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { pathFilter = s.toString(); populatePathRows(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView refresh = action(getString(R.string.product_folder_refresh), false, this::loadAllPaths);
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(-2, dp(48));
        refreshParams.leftMargin = dp(8);
        searchRow.addView(refresh, refreshParams);
        pageContent.addView(searchRow);

        addSpace(pageContent, 14);
        pageContent.addView(label(getString(R.string.product_folder_recent), 15, INK, true));
        addSpace(pageContent, 8);
        pathsProgress = new ProgressBar(this);
        pathsProgress.setIndeterminate(true);
        pageContent.addView(pathsProgress, new LinearLayout.LayoutParams(dp(24), dp(24)));
        pathRows = new LinearLayout(this);
        pathRows.setOrientation(LinearLayout.VERTICAL);
        pageContent.addView(pathRows);
        populatePathRows();

        addSpace(pageContent, 16);
        pageContent.addView(label(getString(R.string.product_folder_selected_path), 15, INK, true));
        addSpace(pageContent, 6);
        pathEditor = input(getString(R.string.product_folder_path_hint));
        pathEditor.setText(newPathValue);
        pathEditor.setSingleLine(true);
        pathEditor.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pathEditor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                newPathValue = s.toString();
                selectedPath = s.toString().trim();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        pageContent.addView(pathEditor, new LinearLayout.LayoutParams(-1, dp(54)));
        addSpace(pageContent, 10);
        pageContent.addView(action(getString(R.string.product_folder_start_camera), true,
                () -> startCameraForPath(pathEditor.getText().toString())));
        loadAllPaths();
    }

    private void renderFoldersPage() {
        pageContent.addView(label(getString(R.string.product_folder_folders_heading), 22, INK, true));
        addSpace(pageContent, 4);
        pageContent.addView(label(getString(R.string.product_folder_folders_help), 13, MUTED, false));
        addSpace(pageContent, 16);
        addRootCard(pageContent);
        if (rootUri == null) {
            addSpace(pageContent, 12);
            pageContent.addView(label(getString(R.string.product_folder_base_missing), 14, MUTED, false));
            addSpace(pageContent, 8);
            pageContent.addView(action(getString(R.string.product_folder_choose_base), true, this::chooseRoot));
            return;
        }

        addSpace(pageContent, 14);
        LinearLayout location = new LinearLayout(this);
        location.setGravity(Gravity.CENTER_VERTICAL);
        if (!folderPath.isEmpty()) {
            TextView up = action("‹", false, () -> {
                int slash = folderPath.lastIndexOf('/');
                folderPath = slash < 0 ? "" : folderPath.substring(0, slash);
                renderPage();
            });
            up.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            location.addView(up, new LinearLayout.LayoutParams(dp(48), dp(44)));
        }
        LinearLayout pathLabels = new LinearLayout(this);
        pathLabels.setOrientation(LinearLayout.VERTICAL);
        pathLabels.setPadding(dp(8), 0, 0, 0);
        pathLabels.addView(label(getString(R.string.product_folder_current_folder), 10, MUTED, true));
        pathLabels.addView(label(folderPath.isEmpty() ? getString(R.string.product_folder_root) : folderPath, 15, INK, true));
        location.addView(pathLabels, new LinearLayout.LayoutParams(0, -2, 1f));
        location.addView(action(getString(R.string.product_folder_refresh), false, this::loadChildFolders),
                new LinearLayout.LayoutParams(-2, dp(44)));
        pageContent.addView(location);

        addSpace(pageContent, 10);
        EditText search = input(getString(R.string.product_folder_search_folders));
        search.setText(folderFilter);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { folderFilter = s.toString(); populateFolderRows(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        pageContent.addView(search, new LinearLayout.LayoutParams(-1, dp(52)));

        addSpace(pageContent, 10);
        foldersProgress = new ProgressBar(this);
        foldersProgress.setIndeterminate(true);
        pageContent.addView(foldersProgress, new LinearLayout.LayoutParams(dp(24), dp(24)));
        folderRows = new LinearLayout(this);
        folderRows.setOrientation(LinearLayout.VERTICAL);
        pageContent.addView(folderRows);
        populateFolderRows();

        addSpace(pageContent, 16);
        pageContent.addView(action(getString(R.string.product_folder_new_folder), false, this::showNewFolderDialog));
        addSpace(pageContent, 8);
        pageContent.addView(action(getString(R.string.product_folder_use_folder_camera), true,
                () -> startCameraForPath(folderPath)));
        loadChildFolders();
    }

    private void addRootCard(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(12), dp(12));
        card.setBackground(rounded(SURFACE, LINE, 16));
        TextView icon = label("▣", 22, ACCENT, true);
        icon.setGravity(Gravity.CENTER);
        card.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(40)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(10), 0, dp(8), 0);
        info.addView(label(getString(R.string.product_folder_base_label), 10, MUTED, true));
        info.addView(label(rootUri == null ? getString(R.string.product_folder_base_not_selected) : rootLabel(), 14, INK, true));
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(action(rootUri == null ? getString(R.string.product_folder_choose_base)
                : getString(R.string.product_folder_change_base), false, this::chooseRoot),
                new LinearLayout.LayoutParams(-2, dp(42)));
        card.setOnClickListener(v -> chooseRoot());
        parent.addView(card);
    }

    private String rootLabel() {
        try {
            String id = DocumentsContract.getTreeDocumentId(rootUri);
            int colon = id.indexOf(':');
            String path = colon >= 0 ? id.substring(colon + 1) : id;
            return path.isEmpty() ? getString(R.string.product_folder_root) : path;
        }
        catch (Exception e) {
            return getString(R.string.product_folder_root);
        }
    }

    private void populatePathRows() {
        if (pathRows == null)
            return;
        pathRows.removeAllViews();
        if (pathsError)
            pathRows.addView(label(getString(R.string.product_folder_list_error), 13, Color.rgb(180, 60, 60), false));

        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        List<String> recent = ProductFolderStore.getRecentPaths(this);
        if (selectedPath != null && !selectedPath.isEmpty())
            ordered.add(selectedPath);
        ordered.addAll(recent);
        ordered.addAll(discoveredPaths);

        String filter = pathFilter.toLowerCase(Locale.ROOT).trim();
        int shown = 0;
        for (String path : ordered) {
            if (!filter.isEmpty() && !path.toLowerCase(Locale.ROOT).contains(filter))
                continue;
            pathRows.addView(pathRow(path, path.equals(selectedPath), recent.contains(path)));
            addSpace(pathRows, 7);
            shown++;
        }
        if (shown == 0 && !pathsLoading)
            pathRows.addView(label(filter.isEmpty() ? getString(R.string.product_folder_paths_empty)
                    : getString(R.string.product_folder_search_empty), 13, MUTED, false));
        if (pathsProgress != null)
            pathsProgress.setVisibility(pathsLoading ? View.VISIBLE : View.GONE);
    }

    private View pathRow(String path, boolean selected, boolean recent) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(8), dp(12));
        row.setBackground(rounded(selected ? 0x142F9CF2 : SURFACE, selected ? ACCENT : LINE, 15));
        row.addView(label("▣", 21, ACCENT, true), new LinearLayout.LayoutParams(dp(32), -2));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(8), 0, dp(8), 0);
        info.addView(label(path.substring(path.lastIndexOf('/') + 1), 15, INK, true));
        if (path.contains("/"))
            info.addView(label(path.substring(0, path.lastIndexOf('/')), 11, MUTED, false));
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(label(selected ? getString(R.string.product_folder_selected_tag)
                : recent ? getString(R.string.product_folder_recent_tag) : "", 9,
                selected ? ACCENT : MUTED, true));
        TextView more = label("⋮", 21, MUTED, true);
        more.setGravity(Gravity.CENTER);
        row.addView(more, new LinearLayout.LayoutParams(dp(34), dp(38)));
        row.setOnClickListener(v -> selectPath(path));
        more.setOnClickListener(v -> showFolderActions(v, path));
        row.setOnLongClickListener(v -> { showFolderActions(v, path); return true; });
        return row;
    }

    private void selectPath(String path) {
        selectedPath = path;
        newPathValue = path;
        if (pathEditor != null && !path.equals(pathEditor.getText().toString()))
            pathEditor.setText(path);
        populatePathRows();
    }

    private void showFolderActions(View anchor, String path) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(R.string.product_folder_rename).setOnMenuItemClickListener(item -> {
            showRenameFolderDialog(path);
            return true;
        });
        menu.getMenu().add(R.string.product_folder_delete).setOnMenuItemClickListener(item -> {
            confirmDeleteFolder(path);
            return true;
        });
        menu.show();
    }

    private void populateFolderRows() {
        if (folderRows == null)
            return;
        folderRows.removeAllViews();
        if (foldersError)
            folderRows.addView(label(getString(R.string.product_folder_list_error), 13, Color.rgb(180, 60, 60), false));

        String filter = folderFilter.toLowerCase(Locale.ROOT).trim();
        int shown = 0;
        for (ProductFolderStore.FolderEntry entry : folderEntries) {
            if (!filter.isEmpty() && !entry.name.toLowerCase(Locale.ROOT).contains(filter))
                continue;
            if (entry.isDirectory()) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(9), dp(6), dp(9));
                row.setBackground(rounded(SURFACE, LINE, 15));
                TextView icon = label("▣", 21, ACCENT, true);
                icon.setGravity(Gravity.CENTER);
                row.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
                TextView name = label(entry.name, 14, INK, true);
                LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1f);
                nameParams.leftMargin = dp(8);
                row.addView(name, nameParams);
                TextView more = label("⋮", 21, MUTED, true);
                more.setGravity(Gravity.CENTER);
                row.addView(more, new LinearLayout.LayoutParams(dp(38), dp(44)));
                String fullPath = folderPath.isEmpty() ? entry.name : folderPath + "/" + entry.name;
                row.setOnClickListener(v -> {
                    folderPath = fullPath;
                    folderFilter = "";
                    folderEntries.clear();
                    renderPage();
                });
                more.setOnClickListener(v -> showFolderActions(v, fullPath));
                row.setOnLongClickListener(v -> { showFolderActions(v, fullPath); return true; });
                folderRows.addView(row);
            }
            else if (entry.isImage()) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(7), dp(6), dp(7));
                row.setBackground(rounded(SURFACE, LINE, 15));
                ImageView thumbnail = new ImageView(this);
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumbnail.setBackground(rounded(0xFFE8EDF2, LINE, 9));
                thumbnail.setTag(entry.uri.toString());
                row.addView(thumbnail, new LinearLayout.LayoutParams(dp(64), dp(64)));
                loadThumbnail(entry, thumbnail);
                TextView name = label(entry.name, 13, INK, true);
                LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1f);
                nameParams.leftMargin = dp(10);
                nameParams.rightMargin = dp(6);
                row.addView(name, nameParams);
                TextView more = label("⋮", 21, MUTED, true);
                more.setGravity(Gravity.CENTER);
                row.addView(more, new LinearLayout.LayoutParams(dp(38), dp(44)));
                row.setOnClickListener(v -> showImagePreview(entry));
                more.setOnClickListener(v -> showImageActions(v, entry));
                row.setOnLongClickListener(v -> { showImageActions(v, entry); return true; });
                folderRows.addView(row);
            }
            addSpace(folderRows, 7);
            shown++;
        }
        if (shown == 0 && !foldersLoading)
            folderRows.addView(label(filter.isEmpty() ? getString(R.string.product_folder_items_empty)
                    : getString(R.string.product_folder_search_empty), 13, MUTED, false));
        if (foldersProgress != null)
            foldersProgress.setVisibility(foldersLoading ? View.VISIBLE : View.GONE);
    }

    private void showImageActions(View anchor, ProductFolderStore.FolderEntry entry) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(R.string.product_folder_delete_image).setOnMenuItemClickListener(item -> {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.product_folder_delete_image)
                    .setMessage(getString(R.string.product_folder_delete_image_confirm, entry.name))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.product_folder_delete, (dialog, which) -> deleteImage(entry))
                    .show();
            return true;
        });
        menu.show();
    }

    private void loadThumbnail(ProductFolderStore.FolderEntry entry, ImageView target) {
        final String key = entry.uri.toString();
        io.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    bitmap = getContentResolver().loadThumbnail(entry.uri, new android.util.Size(dp(96), dp(96)), null);
            }
            catch (Exception ignored) {
            }
            if (bitmap == null)
                bitmap = decodeImage(entry.uri, 160);
            final Bitmap result = bitmap;
            mainHandler.post(() -> {
                if (!isFinishing() && key.equals(target.getTag()) && result != null)
                    target.setImageBitmap(result);
            });
        });
    }

    private Bitmap decodeImage(Uri uri, int maxDimension) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null)
                return null;
            BitmapFactory.decodeStream(input, null, bounds);
        }
        catch (Exception e) {
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / sample > maxDimension * 2 || bounds.outHeight / sample > maxDimension * 2)
            sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null)
                return null;
            return BitmapFactory.decodeStream(input, null, options);
        }
        catch (Exception e) {
            return null;
        }
    }

    private void showImagePreview(ProductFolderStore.FolderEntry entry) {
        ImageView preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setTag(entry.uri.toString());
        preview.setMinimumHeight(dp(220));
        preview.setMaxHeight(dp(460));
        new AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setView(preview)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.product_folder_delete_image, (dialog, which) ->
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.product_folder_delete_image)
                                .setMessage(getString(R.string.product_folder_delete_image_confirm, entry.name))
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.product_folder_delete, (d, w) -> deleteImage(entry))
                                .show())
                .show();
        final String key = entry.uri.toString();
        io.execute(() -> {
            Bitmap result = decodeImage(entry.uri, 1400);
            mainHandler.post(() -> {
                if (!isFinishing() && key.equals(preview.getTag())) {
                    if (result != null)
                        preview.setImageBitmap(result);
                    else
                        Toast.makeText(this, R.string.product_folder_image_unavailable, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void deleteImage(ProductFolderStore.FolderEntry entry) {
        io.execute(() -> {
            try {
                ProductFolderStore.deleteDocument(this, entry.uri);
                mainHandler.post(() -> {
                    if (isFinishing())
                        return;
                    Toast.makeText(this, R.string.product_folder_image_deleted, Toast.LENGTH_SHORT).show();
                    loadChildFolders();
                });
            }
            catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isFinishing())
                        Toast.makeText(this, R.string.product_folder_delete_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadAllPaths() {
        if (rootUri == null || io == null)
            return;
        final Uri selectedRoot = rootUri;
        final String rootKey = selectedRoot.toString();
        final long request = ++pathsRequest;
        pathsLoading = true;
        pathsError = false;
        populatePathRows();
        io.execute(() -> {
            ArrayList<String> found = new ArrayList<>();
            boolean failed = false;
            try {
                found = ProductFolderStore.listAllFolders(this, selectedRoot);
            }
            catch (Exception e) {
                failed = true;
            }
            final ArrayList<String> result = found;
            final boolean error = failed;
            mainHandler.post(() -> {
                if (isFinishing() || request != pathsRequest || rootUri == null || !rootKey.equals(rootUri.toString()))
                    return;
                discoveredPaths = result;
                pathsLoading = false;
                pathsError = error;
                populatePathRows();
            });
        });
    }

    private void loadChildFolders() {
        if (rootUri == null || io == null)
            return;
        final Uri selectedRoot = rootUri;
        final String selectedPath = folderPath;
        final String rootKey = selectedRoot.toString();
        final long request = ++foldersRequest;
        foldersLoading = true;
        foldersError = false;
        populateFolderRows();
        io.execute(() -> {
            ArrayList<ProductFolderStore.FolderEntry> found = new ArrayList<>();
            boolean failed = false;
            try {
                found = ProductFolderStore.listFolderContents(this, selectedRoot, selectedPath);
            }
            catch (Exception e) {
                failed = true;
            }
            final ArrayList<ProductFolderStore.FolderEntry> result = found;
            final boolean error = failed;
            mainHandler.post(() -> {
                if (isFinishing() || request != foldersRequest || rootUri == null || !rootKey.equals(rootUri.toString())
                        || !selectedPath.equals(folderPath))
                    return;
                folderEntries = result;
                foldersLoading = false;
                foldersError = error;
                populateFolderRows();
            });
        });
    }

    private void chooseRoot() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && rootUri != null)
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, rootUri);
        try {
            startActivityForResult(intent, REQUEST_SELECT_ROOT);
        }
        catch (Exception e) {
            Toast.makeText(this, R.string.product_folder_picker_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SELECT_ROOT || resultCode != RESULT_OK || data == null)
            return;
        Uri selected = data.getData();
        if (selected == null)
            return;
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(selected, flags);
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            preferences.edit().putBoolean(PreferenceKeys.UsingSAFPreferenceKey, true)
                    .putString(PreferenceKeys.SaveLocationSAFPreferenceKey, selected.toString()).apply();
            rootUri = selected;
            folderPath = "";
            selectedPath = "";
            newPathValue = "";
            pathFilter = "";
            folderFilter = "";
            ProductFolderStore.setActivePath(this, "");
            discoveredPaths.clear();
            childFolders.clear();
            folderEntries.clear();
            renderPage();
        }
        catch (Exception e) {
            Toast.makeText(this, R.string.product_folder_root_error, Toast.LENGTH_LONG).show();
        }
    }

    private Uri readSelectedRoot() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (!preferences.getBoolean(PreferenceKeys.UsingSAFPreferenceKey, false))
            return null;
        String saved = preferences.getString(PreferenceKeys.SaveLocationSAFPreferenceKey, "");
        if (saved == null || saved.isEmpty())
            return null;
        try {
            return Uri.parse(saved);
        }
        catch (Exception e) {
            return null;
        }
    }

    private void showNewFolderDialog() {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(R.string.product_folder_new_folder_hint);
        new AlertDialog.Builder(this).setTitle(R.string.product_folder_new_folder).setView(name)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> createFolder(name.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showRenameFolderDialog(String oldPath) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(oldPath.substring(oldPath.lastIndexOf('/') + 1));
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle(R.string.product_folder_rename)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        renameFolderPath(oldPath, input.getText().toString()))
                .show();
    }

    private void renameFolderPath(String oldPath, String requestedName) {
        String name = requestedName == null ? "" : requestedName.trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\")) {
            Toast.makeText(this, R.string.product_folder_name_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        int slash = oldPath.lastIndexOf('/');
        String parent = slash < 0 ? "" : oldPath.substring(0, slash);
        final String newPath;
        try {
            newPath = ProductFolderStore.normalizePath(parent.isEmpty() ? name : parent + "/" + name);
        }
        catch (IllegalArgumentException e) {
            Toast.makeText(this, R.string.product_folder_name_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        if (oldPath.equals(newPath))
            return;
        final Uri selectedRoot = rootUri;
        io.execute(() -> {
            try {
                Uri document = ProductFolderStore.findFolderUri(this, selectedRoot, oldPath);
                ProductFolderStore.renameDocument(this, document, name);
                ProductFolderStore.renamePathReferences(this, oldPath, newPath);
                mainHandler.post(() -> {
                    if (isFinishing())
                        return;
                    selectedPath = replacePathPrefix(selectedPath, oldPath, newPath);
                    newPathValue = replacePathPrefix(newPathValue, oldPath, newPath);
                    folderPath = replacePathPrefix(folderPath, oldPath, newPath);
                    if (pathEditor != null)
                        pathEditor.setText(newPathValue);
                    Toast.makeText(this, getString(R.string.product_folder_renamed, newPath), Toast.LENGTH_SHORT).show();
                    discoveredPaths.clear();
                    folderEntries.clear();
                    renderPage();
                });
            }
            catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isFinishing())
                        Toast.makeText(this, R.string.product_folder_rename_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String replacePathPrefix(String path, String oldPath, String newPath) {
        if (path == null || oldPath == null || !(path.equals(oldPath) || path.startsWith(oldPath + "/")))
            return path == null ? "" : path;
        return newPath + path.substring(oldPath.length());
    }

    private void confirmDeleteFolder(String path) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.product_folder_delete)
                .setMessage(getString(R.string.product_folder_delete_folder_confirm, path))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.product_folder_delete, (dialog, which) -> deleteFolderPath(path))
                .show();
    }

    private void deleteFolderPath(String path) {
        int slash = path.lastIndexOf('/');
        String parent = slash < 0 ? "" : path.substring(0, slash);
        final Uri selectedRoot = rootUri;
        io.execute(() -> {
            try {
                Uri document = ProductFolderStore.findFolderUri(this, selectedRoot, path);
                ProductFolderStore.deleteDocument(this, document);
                ProductFolderStore.removePathReferences(this, path);
                mainHandler.post(() -> {
                    if (isFinishing())
                        return;
                    selectedPath = isSameOrChild(selectedPath, path) ? parent : selectedPath;
                    newPathValue = isSameOrChild(newPathValue, path) ? parent : newPathValue;
                    folderPath = isSameOrChild(folderPath, path) ? parent : folderPath;
                    if (pathEditor != null)
                        pathEditor.setText(newPathValue);
                    Toast.makeText(this, R.string.product_folder_deleted, Toast.LENGTH_SHORT).show();
                    discoveredPaths.clear();
                    folderEntries.clear();
                    renderPage();
                });
            }
            catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isFinishing())
                        Toast.makeText(this, R.string.product_folder_delete_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private boolean isSameOrChild(String path, String parent) {
        return path != null && (path.equals(parent) || path.startsWith(parent + "/"));
    }

    private void createFolder(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\")) {
            Toast.makeText(this, R.string.product_folder_name_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        final String fullPath;
        try {
            fullPath = ProductFolderStore.normalizePath(folderPath.isEmpty() ? name : folderPath + "/" + name);
        }
        catch (IllegalArgumentException e) {
            Toast.makeText(this, R.string.product_folder_name_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        final Uri selectedRoot = rootUri;
        io.execute(() -> {
            try {
                ProductFolderStore.resolveFolderUri(this, selectedRoot, fullPath);
                mainHandler.post(() -> {
                    if (isFinishing())
                        return;
                    folderPath = fullPath;
                    folderFilter = "";
                    Toast.makeText(this, getString(R.string.product_folder_created, fullPath), Toast.LENGTH_SHORT).show();
                    childFolders.clear();
                    renderPage();
                });
            }
            catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isFinishing())
                        Toast.makeText(this, R.string.product_folder_invalid, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void startCameraForPath(String requestedPath) {
        if (rootUri == null) {
            chooseRoot();
            return;
        }
        if (launching)
            return;
        final String normalized;
        try {
            normalized = ProductFolderStore.normalizePath(requestedPath);
        }
        catch (IllegalArgumentException e) {
            Toast.makeText(this, R.string.product_folder_invalid, Toast.LENGTH_LONG).show();
            return;
        }

        launching = true;
        Toast.makeText(this, R.string.product_folder_launching, Toast.LENGTH_SHORT).show();
        final Uri selectedRoot = rootUri;
        io.execute(() -> {
            try {
                ProductFolderStore.resolveFolderUri(this, selectedRoot, normalized);
                ProductFolderStore.setActivePath(this, normalized);
                ProductFolderStore.rememberRecentPath(this, normalized);
                mainHandler.post(() -> {
                    if (isFinishing())
                        return;
                    // Keep this picker underneath the camera so Back returns to Paths/Folders.
                    Intent camera = new Intent(this, MainActivity.class);
                    startActivity(camera);
                });
            }
            catch (Exception e) {
                mainHandler.post(() -> {
                    launching = false;
                    if (!isFinishing())
                        Toast.makeText(this, R.string.product_folder_invalid, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private EditText input(String hint) {
        EditText edit = new EditText(this);
        edit.setTextColor(INK);
        edit.setHintTextColor(MUTED);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        edit.setSingleLine(true);
        edit.setHint(hint);
        edit.setPadding(dp(14), dp(8), dp(14), dp(8));
        edit.setBackground(rounded(SURFACE, LINE, 13));
        return edit;
    }

    private TextView action(String value, boolean primary, Runnable listener) {
        TextView button = label(value, 14, primary ? Color.WHITE : ACCENT, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), dp(10), dp(16), dp(10));
        button.setMinHeight(dp(46));
        button.setBackground(rounded(primary ? ACCENT : SURFACE, primary ? ACCENT : LINE, 13));
        button.setOnClickListener(v -> listener.run());
        return button;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        if (bold)
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != Color.TRANSPARENT && stroke != 0)
            drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private void addSpace(LinearLayout parent, int heightDp) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
