package com.colle.latterdaylauncher;

import android.widget.ScrollView;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements Runnable, View.OnClickListener,
        View.OnFocusChangeListener, View.OnLongClickListener, Comparator<ResolveInfo> {

    private static final String PREFS = "launcher_prefs";
    private static final String KEY_ORDER = "app_order";
    private static final int REORDER_COLOR = 0xFF3D7BFF;
    private static final boolean DEBUG = true;

    private ImageView bgImageView;
    private LinearLayout inputsRow;
    private LinearLayout appsRow;
    private TextView inputsHeader;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ImageView lessonImageView;
    private int loadedLesson = -1;
    private TextView lessonHeader;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final long ROTATION_INTERVAL_MS = 3 * 60 * 1000L;
    private final List<String> backgroundFiles = new ArrayList<>();
    private final Random random = new Random();
    private int currentBgIndex = -1;

    // Non-null while the user is moving a tile around
    private String reorderPackage = null;

    // True while OK/Enter is physically held down. A long press from the remote
    // sets pendingMenu*, and the menu is shown once the key is released.
    private boolean okKeyHeld = false;
    private String pendingMenuPkg = null;
    private String pendingMenuLabel = null;

    private static class TileAction {
        final TvInputInfo inputInfo;
        final String packageName;
        final String label;
        final GradientDrawable normalBg;
        final GradientDrawable focusedBg;

        TileAction(TvInputInfo inputInfo, String packageName, String label,
                   GradientDrawable normalBg, GradientDrawable focusedBg) {
            this.inputInfo = inputInfo;
            this.packageName = packageName;
            this.label = label;
            this.normalBg = normalBg;
            this.focusedBg = focusedBg;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();
        initBackgrounds();
        loadInputs();
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(this);
        handler.post(this);
        loadInputs();
        loadApps();
        loadLessonImage();

        UpdateChecker.checkForUpdates(this, false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(this);
        reorderPackage = null;
        okKeyHeld = false;
        pendingMenuPkg = null;
        pendingMenuLabel = null;
    }

    @Override
    public void run() {
        loadRandomBackground();
        handler.postDelayed(this, ROTATION_INTERVAL_MS);
    }

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        bgImageView = new ImageView(this);
        bgImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(bgImageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(this);
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x4D000000, 0x1A000000});
        scrim.setBackground(gradient);
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout contentColumn = new LinearLayout(this);
        contentColumn.setOrientation(LinearLayout.VERTICAL);
        contentColumn.setGravity(Gravity.BOTTOM);
        contentColumn.setPadding(dp(48), dp(24), dp(48), dp(36));

        LinearLayout lessonPanel = new LinearLayout(this);
        lessonPanel.setOrientation(LinearLayout.VERTICAL);
        lessonPanel.setPadding(dp(28), dp(16), dp(28), dp(24));
        lessonPanel.setClipChildren(false);
        lessonPanel.setClipToPadding(false);

        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(0x33FFFFFF);
        panelBg.setCornerRadius(dp(8));
        lessonPanel.setBackground(panelBg);

        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.bottomMargin = dp(20);
        lessonPanel.setLayoutParams(panelLp);

        lessonHeader = createSectionHeader("This Week's Lesson");
        lessonPanel.addView(lessonHeader);
        lessonImageView = createLessonTile();
        lessonPanel.addView(lessonImageView);
        contentColumn.addView(lessonPanel);

        inputsHeader = createSectionHeader("TV Inputs");
        contentColumn.addView(inputsHeader);

        HorizontalScrollView inputsScroll = createHorizontalScroller(dp(126), dp(20));
        inputsRow = new LinearLayout(this);
        inputsRow.setOrientation(LinearLayout.HORIZONTAL);
        inputsRow.setClipChildren(false);
        inputsRow.setClipToPadding(false);
        inputsRow.setPadding(dp(16), dp(8), dp(16), dp(8));   // matches appsRow
        inputsScroll.addView(inputsRow);
        contentColumn.addView(inputsScroll);

        TextView appsHeader = createSectionHeader("Apps");
        contentColumn.addView(appsHeader);

        HorizontalScrollView appsScroll = createHorizontalScroller(dp(140), 0);

        appsRow = new LinearLayout(this);
        appsRow.setOrientation(LinearLayout.HORIZONTAL);
        appsRow.setClipChildren(false);
        appsRow.setClipToPadding(false);
        appsRow.setPadding(dp(16), dp(8), dp(16), dp(8));
        appsScroll.addView(appsRow);
        contentColumn.addView(appsScroll);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.addView(contentColumn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private TextView createSectionHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(0xFFE0E0E0);
        tv.setTextSize(20);
        tv.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        tv.setLayoutParams(lp);
        return tv;
    }

    private HorizontalScrollView createHorizontalScroller(int heightPx, int bottomMarginPx) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heightPx);
        lp.bottomMargin = bottomMarginPx;
        scroll.setLayoutParams(lp);
        return scroll;
    }

    private View createTile(String labelText, Drawable iconDrawable, TvInputInfo inputInfo, String packageName) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(12), dp(12), dp(12), dp(12));
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setOnClickListener(this);
        tile.setOnFocusChangeListener(this);
        if (packageName != null) {
            tile.setOnLongClickListener(this); // only app tiles, not TV inputs
        }

        GradientDrawable normalBg = new GradientDrawable();
        normalBg.setColor(0x33FFFFFF);
        normalBg.setCornerRadius(dp(8));

        GradientDrawable focusedBg = new GradientDrawable();
        focusedBg.setColor(0x80FFFFFF);
        focusedBg.setCornerRadius(dp(8));
        focusedBg.setStroke(dp(2), Color.WHITE);

        tile.setBackground(normalBg);
        tile.setTag(new TileAction(inputInfo, packageName, labelText, normalBg, focusedBg));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(160), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.rightMargin = dp(16);
        tile.setLayoutParams(lp);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageDrawable(iconDrawable);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(48)));

        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextColor(Color.WHITE);
        label.setTextSize(14);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.topMargin = dp(8);
        tile.addView(label, textLp);

        return tile;
    }

    @Override
    public void onClick(View v) {
        if (reorderPackage != null) return; // ignore clicks while reordering

        Object tag = v.getTag();
        if (!(tag instanceof TileAction)) return;
        TileAction action = (TileAction) tag;

        if (action.inputInfo != null) {
            launchTvInput(action.inputInfo);
        } else if (action.packageName != null) {
            PackageManager pm = getPackageManager();
            Intent launchIntent = pm.getLeanbackLaunchIntentForPackage(action.packageName);
            if (launchIntent == null) {
                launchIntent = pm.getLaunchIntentForPackage(action.packageName);
            }
            if (launchIntent != null) {
                startActivity(launchIntent);
            }
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (reorderPackage != null) return true;

        Object tag = v.getTag();
        if (!(tag instanceof TileAction)) return false;
        TileAction action = (TileAction) tag;
        if (action.packageName == null) return false;

        if (okKeyHeld) {
            // Remote/keyboard long press: wait for the button to be released so the
            // release can't land on a menu button and click it.
            pendingMenuPkg = action.packageName;
            pendingMenuLabel = action.label;
        } else {
            // Touch long press: nothing is held on a key, show right away.
            showAppMenu(action.packageName, action.label);
        }
        return true; // consume so releasing the button doesn't also launch the app
    }

    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        Object tag = v.getTag();
        if (!(tag instanceof TileAction)) return;
        TileAction action = (TileAction) tag;

        if (hasFocus) {
            v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start();
            v.setBackground(action.focusedBg);
        } else {
            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start();
            v.setBackground(action.normalBg);
        }
    }

    @Override
    public int compare(ResolveInfo a, ResolveInfo b) {
        PackageManager pm = getPackageManager();
        return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
    }

    // ---------------------------------------------------------------------
    // Popup menu
    // ---------------------------------------------------------------------

    private void showAppMenu(final String pkg, String label) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(20), dp(24), dp(20));
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(0xF0202020);
        boxBg.setCornerRadius(dp(12));
        box.setBackground(boxBg);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, 0, 0, dp(16));
        box.addView(title);

        View first = addMenuButton(box, "Reorder", new Runnable() {
            @Override public void run() {
                dialog.dismiss();
                startReorder(pkg);
            }
        });

        addMenuButton(box, "App Info", new Runnable() {
            @Override public void run() {
                dialog.dismiss();
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + pkg));
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Can't open app info", Toast.LENGTH_SHORT).show();
                }
            }
        });

        addMenuButton(box, "Uninstall", new Runnable() {
            @Override public void run() {
                dialog.dismiss();
                Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
                try {
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Can't uninstall this app", Toast.LENGTH_SHORT).show();
                }
            }
        });

        dialog.setContentView(box);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER); // centered on screen
        }
        dialog.show();
        first.requestFocus();
    }

    private View addMenuButton(LinearLayout parent, String text, final Runnable action) {
        final TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(18);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(16), dp(14), dp(16), dp(14));
        btn.setFocusable(true);
        btn.setClickable(true);

        final GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x33FFFFFF);
        normal.setCornerRadius(dp(8));
        final GradientDrawable focused = new GradientDrawable();
        focused.setColor(REORDER_COLOR);
        focused.setCornerRadius(dp(8));
        focused.setStroke(dp(2), Color.WHITE);
        btn.setBackground(normal);

        btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(hasFocus ? focused : normal);
            }
        });
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                action.run();
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(btn, lp);
        return btn;
    }

    // ---------------------------------------------------------------------
    // Reorder mode
    // ---------------------------------------------------------------------

    private View findAppTile(String pkg) {
        for (int i = 0; i < appsRow.getChildCount(); i++) {
            View c = appsRow.getChildAt(i);
            Object t = c.getTag();
            if (t instanceof TileAction && pkg.equals(((TileAction) t).packageName)) {
                return c;
            }
        }
        return null;
    }

    private void startReorder(String pkg) {
        View tile = findAppTile(pkg);
        if (tile == null) return;
        reorderPackage = pkg;
        ((TileAction) tile.getTag()).focusedBg.setStroke(dp(3), REORDER_COLOR);
        tile.requestFocus();
        tile.setBackground(((TileAction) tile.getTag()).focusedBg);
        Toast.makeText(this, "Use Left/Right to move. Press OK to finish.", Toast.LENGTH_LONG).show();
    }

    private void endReorder() {
        if (reorderPackage == null) return;
        View tile = findAppTile(reorderPackage);
        if (tile != null) {
            ((TileAction) tile.getTag()).focusedBg.setStroke(dp(2), Color.WHITE);
            tile.invalidate();
        }
        reorderPackage = null;
    }

    private void moveReorderTile(int delta) {
        View tile = findAppTile(reorderPackage);
        if (tile == null) return;
        int index = appsRow.indexOfChild(tile);
        int newIdx = index + delta;
        if (newIdx < 0 || newIdx >= appsRow.getChildCount()) return;

        appsRow.removeViewAt(index);
        appsRow.addView(tile, newIdx);
        tile.requestFocus();
        saveOrder();
    }

    private void saveOrder() {
        List<String> order = new ArrayList<>();
        for (int i = 0; i < appsRow.getChildCount(); i++) {
            Object t = appsRow.getChildAt(i).getTag();
            if (t instanceof TileAction && ((TileAction) t).packageName != null) {
                order.add(((TileAction) t).packageName);
            }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_ORDER, TextUtils.join(",", order)).apply();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;

        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (down) {
                okKeyHeld = true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                okKeyHeld = false;
                if (pendingMenuPkg != null) {
                    final String pkg = pendingMenuPkg;
                    final String label = pendingMenuLabel;
                    pendingMenuPkg = null;
                    pendingMenuLabel = null;
                    // Show after this key-up has been fully handled
                    handler.post(new Runnable() {
                        @Override public void run() {
                            showAppMenu(pkg, label);
                        }
                    });
                    return true;
                }
            }
        }

        if (reorderPackage != null) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) moveReorderTile(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) moveReorderTile(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true; // keep focus on the tile being moved
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (down) endReorder();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (down) endReorder();
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ---------------------------------------------------------------------
    // Backgrounds
    // ---------------------------------------------------------------------

    private void initBackgrounds() {
        backgroundFiles.clear();
        try {
            // 1. Standard forward-slash folder lookup
            String[] files = getAssets().list("backgrounds");
            if (files != null && files.length > 0) {
                for (String file : files) {
                    if (isImageFile(file)) {
                        backgroundFiles.add("backgrounds/" + file);
                    }
                }
            }

            // 2. Fallback for Windows aapt2 backslash entries (e.g. "backgrounds\pic.jpg")
            if (backgroundFiles.isEmpty()) {
                String[] rootFiles = getAssets().list("");
                if (rootFiles != null) {
                    for (String file : rootFiles) {
                        if (file.startsWith("backgrounds") && isImageFile(file)) {
                            backgroundFiles.add(file);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean isImageFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
               lower.endsWith(".png") || lower.endsWith(".webp");
    }

    private void loadRandomBackground() {
        if (backgroundFiles.isEmpty()) return;

        int nextIndex = random.nextInt(backgroundFiles.size());
        if (backgroundFiles.size() > 1 && nextIndex == currentBgIndex) {
            nextIndex = (nextIndex + 1) % backgroundFiles.size();
        }
        currentBgIndex = nextIndex;

        String assetPath = backgroundFiles.get(currentBgIndex);

        Bitmap bmp = decodeSampledBitmapFromAsset(assetPath);
        if (bmp != null) {
            bgImageView.setAlpha(0f);
            bgImageView.setImageBitmap(bmp);
            bgImageView.animate().alpha(1f).setDuration(700).start();
        }
    }

    private Bitmap decodeSampledBitmapFromAsset(String assetPath) {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int reqWidth = dm.widthPixels > 0 ? dm.widthPixels : 1920;
            int reqHeight = dm.heightPixels > 0 ? dm.heightPixels : 1080;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            InputStream isBounds = getAssets().open(assetPath);
            BitmapFactory.decodeStream(isBounds, null, options);
            isBounds.close();

            int inSampleSize = 1;
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                final int halfHeight = options.outHeight / 2;
                final int halfWidth = options.outWidth / 2;
                while ((halfHeight / inSampleSize) >= reqHeight &&
                       (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2;
                }
            }

            options.inJustDecodeBounds = false;
            options.inSampleSize = inSampleSize;
            InputStream isDecode = getAssets().open(assetPath);
            Bitmap bitmap = BitmapFactory.decodeStream(isDecode, null, options);
            isDecode.close();
            return bitmap;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // TV inputs and apps
    // ---------------------------------------------------------------------

    private void loadInputs() {
        inputsRow.removeAllViews();
        TvInputManager tvInputManager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);

        if (tvInputManager != null) {
            List<TvInputInfo> inputs = tvInputManager.getTvInputList();
            for (TvInputInfo info : inputs) {
                if (info.getType() != TvInputInfo.TYPE_OTHER || info.isPassthroughInput()) {
                    CharSequence customLabel = info.loadCustomLabel(this);
                    String label = (customLabel != null)
                            ? customLabel.toString()
                            : info.loadLabel(this).toString();

                    Drawable icon = info.loadIcon(this);
                    if (icon == null) {
                        icon = getResources().getDrawable(android.R.drawable.ic_menu_slideshow);
                    }

                    inputsRow.addView(createTile(label, icon, info, null));
                }
            }
        }

        if (DEBUG && inputsRow.getChildCount() == 0) {
            Drawable icon = getResources().getDrawable(android.R.drawable.ic_menu_slideshow);
            inputsRow.addView(createTile("HDMI 1", icon, null, null));
            inputsRow.addView(createTile("HDMI 2", icon, null, null));
        }

        int visibility = (inputsRow.getChildCount() == 0) ? View.GONE : View.VISIBLE;
        inputsHeader.setVisibility(visibility);
        ((View) inputsRow.getParent()).setVisibility(visibility);
    }

    private void launchTvInput(TvInputInfo info) {
        try {
            Uri uri = TvContract.buildChannelUriForPassthroughInput(info.getId());
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Unable to switch input", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadApps() {
        appsRow.removeAllViews();
        PackageManager pm = getPackageManager();
        List<ResolveInfo> resolveInfos = new ArrayList<>();

        Intent leanbackIntent = new Intent(Intent.ACTION_MAIN, null);
        leanbackIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        resolveInfos.addAll(pm.queryIntentActivities(leanbackIntent, 0));

        Intent standardIntent = new Intent(Intent.ACTION_MAIN, null);
        standardIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo stdInfo : pm.queryIntentActivities(standardIntent, 0)) {
            boolean exists = false;
            for (ResolveInfo existing : resolveInfos) {
                if (existing.activityInfo.packageName.equals(stdInfo.activityInfo.packageName)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                resolveInfos.add(stdInfo);
            }
        }

        // Default: alphabetical
        Collections.sort(resolveInfos, this);

        // Then apply the user's saved order. Sort is stable, so apps that aren't
        // in the saved list (e.g. newly installed) stay alphabetical at the end.
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ORDER, "");
        if (!saved.isEmpty()) {
            final List<String> order = Arrays.asList(saved.split(","));
            Collections.sort(resolveInfos, new Comparator<ResolveInfo>() {
                @Override public int compare(ResolveInfo a, ResolveInfo b) {
                    int ia = order.indexOf(a.activityInfo.packageName);
                    int ib = order.indexOf(b.activityInfo.packageName);
                    if (ia < 0) ia = Integer.MAX_VALUE;
                    if (ib < 0) ib = Integer.MAX_VALUE;
                    return Integer.compare(ia, ib);
                }
            });
        }

        for (ResolveInfo info : resolveInfos) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;

            String title = info.loadLabel(pm).toString();
            Drawable icon = info.activityInfo.loadBanner(pm);
            if (icon == null) {
                icon = info.loadIcon(pm);
            }

            appsRow.addView(createTile(title, icon, null, pkg));
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    private ImageView createLessonTile() {
    ImageView iv = new ImageView(this) {
        private final Path clipPath = new Path();
        private final RectF clipRect = new RectF();

        @Override
        protected void onDraw(Canvas canvas) {
            // Clip the picture to a rounded rect just inside the border.
            // Radius is 8dp (border) minus 3dp (padding) so the curves line up.
            clipRect.set(getPaddingLeft(), getPaddingTop(),
                    getWidth() - getPaddingRight(), getHeight() - getPaddingBottom());
            float r = dp(5);
            clipPath.reset();
            clipPath.addRoundRect(clipRect, r, r, Path.Direction.CW);

            canvas.save();
            canvas.clipPath(clipPath);
            super.onDraw(canvas);
            canvas.restore();
        }
    };
    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
    iv.setPadding(dp(3), dp(3), dp(3), dp(3));
    iv.setFocusable(true);
    iv.setClickable(true);

    GradientDrawable normalBg = new GradientDrawable();
    normalBg.setColor(0x33FFFFFF);
    normalBg.setCornerRadius(dp(8));
    GradientDrawable focusedBg = new GradientDrawable();
    focusedBg.setColor(0x80FFFFFF);
    focusedBg.setCornerRadius(dp(8));
    focusedBg.setStroke(dp(2), Color.WHITE);
    iv.setBackground(normalBg);

    // Reuses your existing focus animation; null/null = not an app or TV input
    iv.setTag(new TileAction(null, null, "Lesson", normalBg, focusedBg));
    iv.setOnFocusChangeListener(this);
    iv.setOnClickListener(new View.OnClickListener() {
        @Override public void onClick(View v) {
            if (reorderPackage != null) return;
            try {
                int lesson = LessonImageFetcher.currentLesson();
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(LessonImageFetcher.buildUrl(lesson))));
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Can't open lesson", Toast.LENGTH_SHORT).show();
            }
        }
    });

    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(240), dp(135));
    iv.setLayoutParams(lp);
    return iv;
}

private void loadLessonImage() {
    final int lesson = LessonImageFetcher.currentLesson();
    if (lesson == loadedLesson) return; // already showing this week's lesson

    executor.execute(new Runnable() {
        @Override public void run() {
            try {
                File cacheFile = new File(getCacheDir(), "lesson_" + lesson + ".jpg");
                String titleKey = "lesson_title_" + lesson;
                Bitmap bmp = cacheFile.exists()
                        ? BitmapFactory.decodeFile(cacheFile.getAbsolutePath()) : null;
                String title = getSharedPreferences(PREFS, MODE_PRIVATE).getString(titleKey, null);

                if (bmp == null || title == null) {
                    LessonImageFetcher.Lesson fetched = LessonImageFetcher.fetchLesson(lesson, false);
                    bmp = fetched.image;
                    title = fetched.title;

                    FileOutputStream out = new FileOutputStream(cacheFile);
                    try {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out);
                    } finally {
                        out.close();
                    }
                    if (title != null) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putString(titleKey, title).apply();
                    }
                }

                final Bitmap resultImage = bmp;
                final String resultTitle = title;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        lessonImageView.setImageBitmap(resultImage);
                        lessonHeader.setText(resultTitle != null
                                ? "This Week's Lesson: " + resultTitle
                                : "This Week's Lesson");
                        loadedLesson = lesson;
                    }
                });
            } catch (Exception e) {
                e.printStackTrace(); // loadedLesson stays -1, so it retries next onResume
            }
        }
    });
}

@Override
protected void onDestroy() {
    super.onDestroy();
    executor.shutdownNow();
}

    @Override
    public void onBackPressed() {
        // Ignore back button on the home screen (reorder mode's Back is handled in dispatchKeyEvent)
    }
}