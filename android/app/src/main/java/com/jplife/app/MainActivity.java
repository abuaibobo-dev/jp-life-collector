package com.jplife.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String PREFS = "collector_cfg";

    private EditText kwInput;
    private EditText tokenInput;
    private EditText chatInput;
    private EditText intervalInput;
    private EditText perInput;
    private EditText pagesInput;
    private EditText roundInput;
    private EditText maxmbInput;
    private CheckBox albumCheck;
    private TextView logView;
    private TextView statusView;
    private SharedPreferences sp;
    private CollectorEngine engine;
    private android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences(PREFS, MODE_PRIVATE);

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

        buildUi();
        loadCfg();
        updateStatus(engine.isRunning(), false);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setTextColor(Color.parseColor("#0a6c2e"));
        root.addView(statusView);

        root.addView(label("关键词（每行一个）"));
        kwInput = new EditText(this);
        kwInput.setMinLines(8);
        kwInput.setGravity(Gravity.TOP | Gravity.START);
        root.addView(kwInput);

        root.addView(label("Telegram bot_token"));
        tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        root.addView(tokenInput);

        root.addView(label("chat_id（留空保存后自动获取）"));
        chatInput = new EditText(this);
        chatInput.setSingleLine(true);
        root.addView(chatInput);

        LinearLayout row1 = row();
        intervalInput = field("循环间隔(分钟)", "120");
        perInput = field("每词张数", "10");
        pagesInput = field("搜索页数", "2");
        row1.addView(intervalInput);
        row1.addView(perInput);
        row1.addView(pagesInput);
        root.addView(row1);

        LinearLayout row2 = row();
        roundInput = field("每轮词数", "3");
        maxmbInput = field("图片上限MB", "8");
        albumCheck = new CheckBox(this);
        albumCheck.setText("组图发送");
        row2.addView(roundInput);
        row2.addView(maxmbInput);
        row2.addView(albumCheck);
        root.addView(row2);

        LinearLayout btns = row();
        Button save = new Button(this);
        save.setText("保存配置");
        save.setOnClickListener(v -> saveCfg());
        btns.addView(save);

        Button once = new Button(this);
        once.setText("立即采集一轮");
        once.setOnClickListener(v -> {
            saveCfg();
            engine.runOnce(readSettings());
        });
        btns.addView(once);

        Button startLoop = new Button(this);
        startLoop.setText("开始循环");
        startLoop.setOnClickListener(v -> {
            saveCfg();
            engine.startLoop(readSettings());
        });
        btns.addView(startLoop);

        Button stopLoop = new Button(this);
        stopLoop.setText("停止循环");
        stopLoop.setOnClickListener(v -> engine.stopLoop());
        btns.addView(stopLoop);
        root.addView(btns);

        root.addView(label("运行日志"));
        logView = new TextView(this);
        logView.setTextColor(Color.BLACK);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        ScrollView sc = new ScrollView(this);
        sc.addView(root);
        setContentView(sc);
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#555555"));
        tv.setPadding(0, dp(8), 0, dp(2));
        return tv;
    }

    private LinearLayout row() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        return ll;
    }

    private EditText field(String hint, String def) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(def);
        et.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, dp(8), 0);
        et.setLayoutParams(lp);
        return et;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void saveCfg() {
        SharedPreferences.Editor ed = sp.edit();
        ed.putString("keywords", kwInput.getText().toString());
        ed.putString("bot_token", tokenInput.getText().toString().trim());
        ed.putString("chat_id", chatInput.getText().toString().trim());
        ed.putInt("run_interval_minutes", intOf(intervalInput, 120));
        ed.putInt("max_per_keyword", intOf(perInput, 10));
        ed.putInt("search_pages", intOf(pagesInput, 2));
        ed.putInt("keywords_per_round", intOf(roundInput, 3));
        ed.putInt("max_image_mb", intOf(maxmbInput, 8));
        ed.putBoolean("album_mode", albumCheck.isChecked());
        ed.apply();
        appendLog("[配置] 已保存");
    }

    private void loadCfg() {
        kwInput.setText(sp.getString("keywords", defaultKeywords()));
        tokenInput.setText(sp.getString("bot_token", ""));
        chatInput.setText(sp.getString("chat_id", ""));
        intervalInput.setText(String.valueOf(sp.getInt("run_interval_minutes", 120)));
        perInput.setText(String.valueOf(sp.getInt("max_per_keyword", 10)));
        pagesInput.setText(String.valueOf(sp.getInt("search_pages", 2)));
        roundInput.setText(String.valueOf(sp.getInt("keywords_per_round", 3)));
        maxmbInput.setText(String.valueOf(sp.getInt("max_image_mb", 8)));
        albumCheck.setChecked(sp.getBoolean("album_mode", true));
    }

    private CollectorEngine.Settings readSettings() {
        CollectorEngine.Settings s = new CollectorEngine.Settings();
        s.botToken = sp.getString("bot_token", "");
        s.chatId = sp.getString("chat_id", "");
        List<String> kws = new ArrayList<>();
        String raw = sp.getString("keywords", "");
        for (String line : raw.split("\n")) {
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
                + "日本の家庭料理 手作り\n"
                + "デリバリー 出前 日本 食\n"
                + "コンビニ 日本 買い物\n"
                + "日本の通勤 電車 朝\n"
                + "スーパーで買い物 日本\n"
                + "日本 オフィス 仕事風景\n"
                + "在宅勤務 テレワーク 日本\n"
                + "ランチ 一人 日本 カフェ\n"
                + "洗濯 家事 日本 生活";
    }

    private int intOf(EditText et, int def) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (Exception e) {
            return def;
        }
    }

    private void appendLog(String line) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
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
        if (loopRunning) s = "🟢 循环运行中";
        else if (running) s = "⏳ 工作中";
        else s = "⚪ 空闲";
        int pending = engine.sentCount();
        statusView.setText(s + "    已发送: " + pending);
    }
}