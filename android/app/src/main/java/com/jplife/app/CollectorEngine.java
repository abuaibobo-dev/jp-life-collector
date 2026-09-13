package com.jplife.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class CollectorEngine {

    public interface Config {
        String botToken();
        String chatId();
        List<String> keywords();
        int maxPerKeyword();
        int searchPages();
        int keywordsPerRound();
        int runIntervalMinutes();
        int maxImageMb();
        boolean albumMode();
        String searchMarket();
    }

    public interface Sink {
        void log(String line);
        void state(boolean running, boolean loopRunning);
    }

    public static class Settings {
        public String botToken = "";
        public String chatId = "";
        public List<String> keywords = new ArrayList<>();
        public int maxPerKeyword = 10;
        public int searchPages = 2;
        public int keywordsPerRound = 3;
        public int runIntervalMinutes = 120;
        public int maxImageMb = 8;
        public boolean albumMode = true;
        public String searchMarket = "ja-JP";
    }

    private final Context ctx;
    private final File downloadDir;
    private final File archiveFile;
    private final File sentDir;
    private final Sink sink;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopFlag = new AtomicBoolean(false);
    private final Object lock = new Object();
    private Thread loopThread;
    private int sentCount = 0;

    public CollectorEngine(Context ctx, Sink sink) {
        this.ctx = ctx.getApplicationContext();
        this.sink = sink;
        this.downloadDir = new File(ctx.getFilesDir(), "downloads");
        this.archiveFile = new File(ctx.getFilesDir(), "archive.txt");
        this.sentDir = new File(ctx.getFilesDir(), "sent");
        downloadDir.mkdirs();
        sentDir.mkdirs();
        syncSentCount();
    }

    private void syncSentCount() {
        File[] files = sentDir.listFiles();
        sentCount = files == null ? 0 : files.length;
    }

    public boolean isRunning() {
        return running.get();
    }

    public int sentCount() {
        return sentCount;
    }

    public void runOnce(Settings s) {
        if (running.getAndSet(true)) {
            sink.log("[跳过] 上一轮仍在运行");
            return;
        }
        sink.state(true, loopThread != null && loopThread.isAlive());
        new Thread(() -> {
            try {
                doRound(s);
            } finally {
                running.set(false);
                sink.state(false, loopThread != null && loopThread.isAlive());
            }
        }, "collector").start();
    }

    public void startLoop(Settings s) {
        stopFlag.set(false);
        if (loopThread != null && loopThread.isAlive()) {
            sink.log("[循环] 已在运行");
            return;
        }
        loopThread = new Thread(() -> {
            sink.state(false, true);
            while (!stopFlag.get()) {
                if (!running.getAndSet(true)) {
                    try {
                        doRound(s);
                    } finally {
                        running.set(false);
                    }
                }
                int intervalMin = Math.max(1, s.runIntervalMinutes);
                sink.log("[循环] 等待 " + intervalMin + " 分钟后下一轮");
                synchronized (lock) {
                    try {
                        lock.wait(intervalMin * 60 * 1000L);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            sink.state(false, false);
        }, "collector-loop");
        loopThread.start();
    }

    public void stopLoop() {
        stopFlag.set(true);
        synchronized (lock) {
            lock.notifyAll();
        }
        sink.state(false, false);
        sink.log("[循环] 已停止");
    }

    private void doRound(Settings s) {
        if (s.botToken == null || s.botToken.isEmpty()) {
            sink.log("[上传跳过] 未配置 bot_token");
        }
        List<String> active = s.keywords;
        if (s.keywordsPerRound > 0 && s.keywords.size() > s.keywordsPerRound) {
            active = s.keywords.subList(0, s.keywordsPerRound);
        }
        List<File> collected = new ArrayList<>();
        for (String kw : active) {
            if (kw == null || kw.trim().isEmpty() || kw.trim().startsWith("#")) continue;
            sink.log("[开始] 采集关键词: " + kw.trim());
            collected.addAll(collectKeyword(s, kw.trim()));
        }
        sink.log("[采集完成] 本轮新增 " + collected.size() + " 张");
        uploadPending(s);
        syncSentCount();
        sink.state(false, loopThread != null && loopThread.isAlive());
    }

    private List<File> collectKeyword(Settings s, String keyword) {
        List<File> downloaded = new ArrayList<>();
        int per = Math.max(1, s.maxPerKeyword);
        int pages = Math.max(1, s.searchPages);
        if (pages > 3) pages = 3;
        for (int page = 0; page < pages; page++) {
            int first = page * 30 + 1;
            List<BingSearcher.Result> results;
            try {
                results = BingSearcher.search(keyword, first, 25000, s.searchMarket);
            } catch (Exception exc) {
                sink.log("  [搜索失败] " + keyword + " page" + (page + 1) + ": " + exc.getMessage());
                break;
            }
            for (BingSearcher.Result r : results) {
                if (!BingSearcher.goodUrl(r.imageUrl)) continue;
                if (inArchive(r.imageUrl)) continue;
                String ext = BingSearcher.pickExtension(r.imageUrl);
                String fname = safeName(r.title, 36) + ext;
                File dest = new File(downloadDir, fname);
                boolean ok = false;
                for (int attempt = 0; attempt < 2 && !ok; attempt++) {
                    try {
                        long size = Downloader.download(r.imageUrl, dest, 30000);
                        int maxBytes = Math.max(1, s.maxImageMb) * 1024 * 1024;
                        if (size > maxBytes) {
                            dest.delete();
                            sink.log("  [超限] " + dest.getName() + " (" + (size / 1024 / 1024.0) + "MB)");
                            ok = true;
                            break;
                        }
                    } catch (Exception exc) {
                        dest.delete();
                        sink.log("  [下载失败] " + truncate(r.title, 20) + " -> " + exc.getMessage());
                        try { Thread.sleep(1000); } catch (InterruptedException ignore) {}
                        continue;
                    }
                    writeMeta(dest, r, keyword);
                    appendArchive(r.imageUrl);
                    sink.log("  [已采集] " + dest.getName() + " ("
                            + (dest.length() / 1024 / 1024.0f) + "MB)");
                    downloaded.add(dest);
                    ok = true;
                    if (downloaded.size() >= per) return downloaded;
                }
            }
            try { Thread.sleep(1000); } catch (InterruptedException ignore) {}
        }
        return downloaded;
    }

    private void uploadPending(Settings s) {
        if (s.botToken == null || s.botToken.isEmpty()) {
            sink.log("[上传跳过] 未配置 bot_token");
            return;
        }
        if (s.chatId == null || s.chatId.isEmpty()) {
            sink.log("[上传跳过] 未配置 chat_id");
            return;
        }
        File[] files = downloadDir.listFiles();
        List<File> queued = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                String name = f.getName().toLowerCase();
                if ((name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                        || name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".bmp"))
                        && f.length() > 0) {
                    queued.add(f);
                }
            }
        }
        if (queued.isEmpty()) return;
        queued.sort((a, b) -> a.getName().compareTo(b.getName()));
        int maxImages = Math.min(20, queued.size());
        queued = queued.subList(0, maxImages);

        int ok = 0, fail = 0;
        if (!s.albumMode) {
            for (File media : queued) {
                if (media.length() > 9 * 1024 * 1024) {
                    sink.log("  [跳过>9MB] " + media.getName());
                    fail++;
                    continue;
                }
                try {
                    Uploader.sendPhoto(s.botToken, s.chatId, media, buildCaption(media));
                    markSent(media);
                    ok++;
                } catch (Exception exc) {
                    sink.log("  [发送异常] " + media.getName() + ": " + exc.getMessage());
                    fail++;
                }
                sleep(1000);
            }
        } else {
            Map<String, List<File>> groups = new LinkedHashMap<>();
            for (File media : queued) {
                if (media.length() > 9 * 1024 * 1024) {
                    sink.log("  [跳过>9MB] " + media.getName());
                    fail++;
                    continue;
                }
                groups.computeIfAbsent(mediaKeyword(media), k -> new ArrayList<>()).add(media);
            }
            for (Map.Entry<String, List<File>> e : groups.entrySet()) {
                String kw = e.getKey();
                List<File> list = e.getValue();
                for (int i = 0; i < list.size(); i += 10) {
                    List<File> batch = list.subList(i, Math.min(i + 10, list.size()));
                    if (batch.size() == 1) {
                        File media = batch.get(0);
                        try {
                            Uploader.sendPhoto(s.botToken, s.chatId, media, buildCaption(media));
                            markSent(media);
                            ok++;
                        } catch (Exception exc) {
                            sink.log("  [发送异常] " + media.getName() + ": " + exc.getMessage());
                            fail++;
                        }
                        sleep(1000);
                        continue;
                    }
                    String caption = "日本素材 | 关键词: " + kw + "\n共 " + batch.size() + " 张";
                    try {
                        Uploader.sendMediaGroup(s.botToken, s.chatId, batch, caption);
                        sink.log("  [组图发送成功] 关键词「" + kw + "」共 " + batch.size() + " 张");
                        for (File media : batch) markSent(media);
                        ok += batch.size();
                    } catch (Exception exc) {
                        sink.log("  [组图失败，回退单张] " + kw + ": " + exc.getMessage());
                        for (File media : batch) {
                            try {
                                Uploader.sendPhoto(s.botToken, s.chatId, media, buildCaption(media));
                                markSent(media);
                                ok++;
                            } catch (Exception exc2) {
                                sink.log("  [发送异常] " + media.getName() + ": " + exc2.getMessage());
                                fail++;
                            }
                            sleep(1000);
                        }
                    }
                    sleep(2000);
                }
            }
        }
        sink.log("[上传完成] 成功 " + ok + "，失败 " + fail);
    }

    private void markSent(File media) {
        File meta = new File(media.getAbsolutePath().replaceAll("\\.(jpg|jpeg|png|webp|gif|bmp)$", ".json"));
        sink.log("    OK " + media.getName());
        File movesTo = new File(sentDir, media.getName());
        media.renameTo(movesTo);
        if (meta.exists()) {
            File metaTo = new File(sentDir, meta.getName());
            meta.renameTo(metaTo);
        }
    }

    private void writeMeta(File image, BingSearcher.Result r, String keyword) {
        String path = image.getAbsolutePath().replaceAll("\\.(jpg|jpeg|png|webp|gif|bmp)$", ".json");
        try {
            JSONObject o = new JSONObject();
            o.put("title", r.title);
            o.put("image_url", r.imageUrl);
            o.put("page_url", r.pageUrl);
            o.put("keyword", keyword);
            FileOutputStream fos = new FileOutputStream(path);
            OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            w.write(o.toString(2));
            w.close();
        } catch (Exception ignore) {
        }
    }

    private String keywordOf(File image) {
        JSONObject meta = readMeta(image);
        if (meta != null) return meta.optString("keyword", "");
        return "";
    }

    private JSONObject readMeta(File image) {
        String path = image.getAbsolutePath().replaceAll("\\.(jpg|jpeg|png|webp|gif|bmp)$", ".json");
        File meta = new File(path);
        if (!meta.exists()) return null;
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(meta));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private String buildCaption(File media) {
        JSONObject meta = readMeta(media);
        if (meta == null) return null;
        List<String> lines = new ArrayList<>();
        String title = meta.optString("title", "").trim();
        if (!title.isEmpty()) lines.add(title.length() > 400 ? title.substring(0, 400) : title);
        String kw = meta.optString("keyword", "");
        if (!kw.isEmpty()) lines.add("#关键词: " + kw);
        String purl = meta.optString("page_url", "");
        if (!purl.isEmpty()) lines.add(purl);
        StringBuilder cb = new StringBuilder();
        for (String line : lines) {
            if (cb.length() > 0) cb.append('\n');
            cb.append(line);
        }
        String caption = cb.toString().trim();
        return caption.isEmpty() ? null : caption.substring(0, Math.min(caption.length(), 1024));
    }

    private String mediaKeyword(File media) {
        JSONObject meta = readMeta(media);
        if (meta != null) {
            String kw = meta.optString("keyword", "").trim();
            if (!kw.isEmpty()) return kw;
        }
        return "未分类";
    }

    private boolean inArchive(String url) {
        if (!archiveFile.exists()) return false;
        try {
            BufferedReader br = new BufferedReader(new FileReader(archiveFile));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.equals(url)) {
                    br.close();
                    return true;
                }
            }
            br.close();
        } catch (Exception ignore) {
        }
        return false;
    }

    private void appendArchive(String url) {
        try {
            FileOutputStream fos = new FileOutputStream(archiveFile, true);
            fos.write((url + "\n").getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception ignore) {
        }
    }

    private static String safeName(String text, int maxlen) {
        String val = text == null ? "img" : text.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").replaceAll("^_+|_+$", "");
        if (val.isEmpty()) val = "img";
        if (val.length() > maxlen) val = val.substring(0, maxlen);
        return val + "_" + System.currentTimeMillis();
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) : s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignore) {
        }
    }

    public void clearHistory() {
        if (archiveFile.exists()) archiveFile.delete();
        File[] files = downloadDir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        File[] sentFiles = sentDir.listFiles();
        if (sentFiles != null) {
            for (File f : sentFiles) f.delete();
        }
        syncSentCount();
        sink.log("[历史] 已清空缓存与记录");
    }
}