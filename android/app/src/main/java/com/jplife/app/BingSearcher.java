package com.jplife.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BingSearcher {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final Pattern M_PATTERN = Pattern.compile("m=\"(\\{.*?\\})\"", Pattern.DOTALL);

    public static class Result {
        public final String imageUrl;
        public final String pageUrl;
        public final String title;

        public Result(String imageUrl, String pageUrl, String title) {
            this.imageUrl = imageUrl;
            this.pageUrl = pageUrl;
            this.title = title;
        }
    }

    public static List<Result> search(String keyword, int first, int timeoutMs, String mkt) throws Exception {
        String q = URLEncoder.encode(keyword, "UTF-8");
        String mktParam = (mkt == null || mkt.isEmpty()) ? "" : "&setmkt=" + mkt;
        String urlStr = "https://www.bing.com/images/search?q=" + q + "&form=HDRSC2&first=" + first
                + mktParam + "&cc=JP";

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        conn.setRequestProperty("Referer", "https://www.bing.com/");
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new Exception("Bing HTTP " + code);
        }

        StringBuilder sb = new StringBuilder();
        InputStream in = conn.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append('\n');
        }
        br.close();
        conn.disconnect();

        List<Result> data = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher m = M_PATTERN.matcher(sb.toString());
        while (m.find()) {
            String group = m.group(1);
            if (group.length() < 3 || group.charAt(0) != '{') continue;
            String jsonRaw = unescapeHtml(group);
            try {
                JSONObject d = new JSONObject(jsonRaw);
                String murl = d.optString("murl", "").trim();
                if (murl.isEmpty() || seen.contains(murl)) continue;
                seen.add(murl);
                data.add(new Result(murl, d.optString("purl", ""), d.optString("t", "")));
                if (data.size() >= 30) break;
            } catch (Exception ignore) {
            }
        }
        return data;
    }

    static String unescapeHtml(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < n) {
                int semi = s.indexOf(';', i + 1);
                String ent = (semi > i + 1) ? s.substring(i + 1, semi) : null;
                String rep = null;
                if (ent != null) {
                    if (ent.equals("quot")) rep = "\"";
                    else if (ent.equals("amp")) rep = "&";
                    else if (ent.equals("apos")) rep = "'";
                    else if (ent.equals("lt")) rep = "<";
                    else if (ent.equals("gt")) rep = ">";
                    else if (ent.equals("#39")) rep = "'";
                    else if (ent.startsWith("#x") || ent.startsWith("#X")) {
                        try { rep = String.valueOf((char) Integer.parseInt(ent.substring(2), 16)); }
                        catch (Exception ignore) {}
                    } else if (ent.startsWith("#")) {
                        try { rep = String.valueOf((char) Integer.parseInt(ent.substring(1))); }
                        catch (Exception ignore) {}
                    }
                }
                if (rep != null) {
                    out.append(rep);
                    i = semi + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    public static boolean goodUrl(String murl) {
        try {
            URL u = new URL(murl);
            String path = u.getPath().toLowerCase();
            int dot = path.lastIndexOf('.');
            if (dot < 0 || dot == path.length() - 1) return false;
            String ext = path.substring(dot);
            Set<String> okExts = new HashSet<>();
            okExts.add(".jpg"); okExts.add(".jpeg"); okExts.add(".png"); okExts.add(".webp");
            if (!okExts.contains(ext)) return false;

            String lower = murl.toLowerCase();
            String[] bad = {"logo", "icon", "advert", ".svg", "pixel", "spacer",
                    "transparent", "banner", "sprite", "favicon"};
            for (String b : bad) {
                if (lower.contains(b)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String pickExtension(String murl) {
        try {
            String path = new URL(murl).getPath().toLowerCase();
            int dot = path.lastIndexOf('.');
            if (dot < 0) return ".jpg";
            String ext = path.substring(dot);
            Set<String> okExts = new HashSet<>();
            okExts.add(".jpg"); okExts.add(".jpeg"); okExts.add(".png"); okExts.add(".webp");
            return okExts.contains(ext) ? ext : ".jpg";
        } catch (Exception e) {
            return ".jpg";
        }
    }
}