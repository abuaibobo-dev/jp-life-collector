package com.jplife.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String PREFS = "collector_cfg";

    private SharedPreferences sp;

    private View panelCollect;
    private View panelGallery;
    private View panelSettings;

    private TextView statusText;
    private TextView statusDetail;
    private View statusDot;
    private TextView logView;

    private EditText cfgKeywords;
    private EditText cfgToken;
    private EditText cfgChat;
    private EditText cfgInterval;
    private EditText cfgPerKeyword;
    private EditText cfgPages;
    private EditText cfgPerRound;
    private EditText cfgMaxMb;
    private SwitchMaterial cfgAlbum;

    private RecyclerView galleryGrid;
    private TextView galleryCount;
    private GalleryAdapter galleryAdapter;

    private Handler uiHandler = new Handler(Looper.getMainLooper());
    private CollectorEngine engine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(R.layout.activity_main);

        bindViews();
        engine = new CollectorEngine(this, new CollectorEngine.Sink() {
            @Override
            public void log(String line) {
                uiHandler.post(() -> appendLog(line));
            }

            @Override
            public void state(boolean running, boolean loopRunning) {
                uiHandler.post(() -> updateStatus(running, loopRunning));
            }
        });

        loadCfg();

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_collect) switchTab(panelCollect);
            else if (id == R.id.nav_gallery) {
                switchTab(panelGallery);
                refreshGallery();
            } else if (id == R.id.nav_settings) switchTab(panelSettings);
            return true;
        });

        Button runOnce = findViewById(R.id.btn_run_once);
        runOnce.setOnClickListener(v -> {
            saveCfg();
            engine.runOnce(readSettings());
        });
        Button loopStart = findViewById(R.id.btn_loop_start);
        loopStart.setOnClickListener(v -> {
            saveCfg();
            engine.startLoop(readSettings());
        });
        Button loopStop = findViewById(R.id.btn_loop_stop);
        loopStop.setOnClickListener(v -> engine.stopLoop());
        Button save = findViewById(R.id.btn_save);
        save.setOnClickListener(v -> {
            saveCfg();
            appendLog("[配置] 已保存");
        });
        Button clearHistory = findViewById(R.id.btn_clear_history);
        clearHistory.setOnClickListener(v -> {
            saveCfg();
            engine.clearHistory();
            refreshGallery();
        });

        int cols = getScreenWidth() < 480 ? 2 : 3;
        galleryAdapter = new GalleryAdapter(getScreenWidth(), cols);
        StaggeredGridLayoutManager lm = new StaggeredGridLayoutManager(cols,
                StaggeredGridLayoutManager.VERTICAL);
        galleryGrid.setLayoutManager(lm);
        galleryGrid.setAdapter(galleryAdapter);

        Button refresh = findViewById(R.id.btn_refresh_gallery);
        refresh.setOnClickListener(v -> refreshGallery());

        switchTab(panelCollect);
        updateStatus(engine.isRunning(), false);
    }

    private void bindViews() {
        panelCollect = findViewById(R.id.panel_collect);
        panelGallery = findViewById(R.id.panel_gallery);
        panelSettings = findViewById(R.id.panel_settings);
        statusText = findViewById(R.id.status_text);
        statusDetail = findViewById(R.id.status_detail);
        statusDot = findViewById(R.id.status_dot);
        logView = findViewById(R.id.log_view);
        cfgKeywords = findViewById(R.id.cfg_keywords);
        cfgToken = findViewById(R.id.cfg_token);
        cfgChat = findViewById(R.id.cfg_chat);
        cfgInterval = findViewById(R.id.cfg_interval);
        cfgPerKeyword = findViewById(R.id.cfg_per_keyword);
        cfgPages = findViewById(R.id.cfg_pages);
        cfgPerRound = findViewById(R.id.cfg_per_round);
        cfgMaxMb = findViewById(R.id.cfg_max_mb);
        cfgAlbum = findViewById(R.id.cfg_album);
        galleryGrid = findViewById(R.id.gallery_grid);
        galleryCount = findViewById(R.id.gallery_count);
    }

    private int getScreenWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private void switchTab(View panel) {
        panelCollect.setVisibility(panel == panelCollect ? View.VISIBLE : View.GONE);
        panelGallery.setVisibility(panel == panelGallery ? View.VISIBLE : View.GONE);
        panelSettings.setVisibility(panel == panelSettings ? View.VISIBLE : View.GONE);
    }

    private void saveCfg() {
        SharedPreferences.Editor ed = sp.edit();
        ed.putString("keywords", cfgKeywords.getText().toString());
        ed.putString("bot_token", cfgToken.getText().toString().trim());
        ed.putString("chat_id", cfgChat.getText().toString().trim());
        ed.putInt("run_interval_minutes", intOf(cfgInterval, 120));
        ed.putInt("max_per_keyword", intOf(cfgPerKeyword, 10));
        ed.putInt("search_pages", intOf(cfgPages, 2));
        ed.putInt("keywords_per_round", intOf(cfgPerRound, 3));
        ed.putInt("max_image_mb", intOf(cfgMaxMb, 8));
        ed.putBoolean("album_mode", cfgAlbum.isChecked());
        ed.apply();
        sp = getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void loadCfg() {
        cfgKeywords.setText(sp.getString("keywords", defaultKeywords()));
        cfgToken.setText(sp.getString("bot_token", ""));
        cfgChat.setText(sp.getString("chat_id", ""));
        cfgInterval.setText(String.valueOf(sp.getInt("run_interval_minutes", 120)));
        cfgPerKeyword.setText(String.valueOf(sp.getInt("max_per_keyword", 10)));
        cfgPages.setText(String.valueOf(sp.getInt("search_pages", 2)));
        cfgPerRound.setText(String.valueOf(sp.getInt("keywords_per_round", 3)));
        cfgMaxMb.setText(String.valueOf(sp.getInt("max_image_mb", 8)));
        cfgAlbum.setChecked(sp.getBoolean("album_mode", true));
    }

    private CollectorEngine.Settings readSettings() {
        CollectorEngine.Settings s = new CollectorEngine.Settings();
        s.botToken = sp.getString("bot_token", "");
        s.chatId = sp.getString("chat_id", "");
        List<String> kws = new ArrayList<>();
        String raw = sp.getString("keywords", "");
        String[] lines = raw.split("\n");
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) kws.add(t);
        }
        s.keywords = kws;
        s.runIntervalMinutes = sp.getInt("run_interval_minutes", 120);
        s.maxPerKeyword = sp.getInt("max_per_keyword", 10);
        s.searchPages = sp.getInt("search_pages", 2);
        s.keywordsPerRound = sp.getInt("keywords_per_round", 3);
        s.maxImageMb = sp.getInt("max_image_mb", 8);
        s.albumMode = sp.getBoolean("album_mode", true);
        return s;
    }

    private String defaultKeywords() {
        return "日本の自炊 キッチン 料理\n"
                + "デリバリー 出前 日本 食\n"
                + "コンビニ 日本 買い物\n"
                + "日本の通勤 電車 朝\n"
                + "スーパーで買い物 日本\n"
                + "日本 オフィス 仕事風景\n"
                + "在宅勤務 テレワーク 日本\n"
                + "ランチ 一人 日本 カフェ\n"
                + "洗濯 家事 日本 生活\n"
                + "一人暮らし 部屋 インテリア";
    }

    private int intOf(EditText et, int def) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (Exception e) {
            return def;
        }
    }

    private void appendLog(String line) {
        String time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM, Locale.getDefault())
                .format(new java.util.Date());
        logView.append(time + "  " + line + "\n");
        if (logView.getLineCount() > 500) {
            String all = logView.getText().toString();
            int idx = all.indexOf('\n', all.length() / 2);
            if (idx > 0) logView.setText(all.substring(idx + 1));
        }
    }

    private void updateStatus(boolean running, boolean loopRunning) {
        String s;
        int dot;
        if (loopRunning) {
            s = "循环运行中";
            dot = R.drawable.dot_green;
        } else if (running) {
            s = "正在采集…";
            dot = R.drawable.dot_amber;
        } else {
            s = "空闲";
            dot = R.drawable.dot_gray;
        }
        statusText.setText(s);
        GradientDrawable bg = (GradientDrawable) getResources().getDrawable(dot).getConstantState().newDrawable();
        statusDot.setBackground(bg);
        int sent = engine.sentCount();
        int pending = pendingCount();
        String detail = String.format(Locale.US, "已发送 %d · 待发送 %d", sent, pending);
        statusDetail.setText(detail);
    }

    private int pendingCount() {
        File[] files = Store.downloadDir(this).listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                    || name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".bmp")) {
                n++;
            }
        }
        return n;
    }

    private void refreshGallery() {
        File dir = Store.downloadDir(this);
        File[] files = dir.listFiles();
        List<GalleryAdapter.Item> items = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                        || name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".bmp")) {
                    items.add(new GalleryAdapter.Item(f, Store.titleOf(f)));
                }
            }
        }
        items.sort((a, b) -> b.file.getName().compareTo(a.file.getName()));
        galleryAdapter.setItems(items);
        galleryCount.setText(String.format(Locale.getDefault(), "已采集 %d 张", items.size()));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (galleryAdapter != null) galleryAdapter.shutdown();
    }
}