package com.jplife.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class Downloader {

    public static long download(String imageUrl, File dest, int timeoutMs) throws Exception {
        URL u = new URL(imageUrl);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        conn.setRequestProperty("Referer", u.getProtocol() + "://" + u.getHost());
        conn.setInstanceFollowRedirects(true);

        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new Exception("HTTP " + code);
        }
        String ct = conn.getContentType();
        if (ct != null && ct.toLowerCase().contains("text/html")) {
            conn.disconnect();
            throw new Exception("HTML page instead of image");
        }
        InputStream in = conn.getInputStream();
        FileOutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
        }
        out.close();
        in.close();
        conn.disconnect();

        if (total < 4 * 1024) {
            dest.delete();
            throw new Exception("too small");
        }
        return total;
    }
}