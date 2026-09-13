package com.jplife.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

public class Uploader {

    private static final String API = "https://api.telegram.org/bot%s/%s";

    private static final Random RND = new Random();

    public static JSONObject sendPhoto(String token, String chatId, File media, String caption) throws Exception {
        String boundary = "----weba" + Long.toHexString(RND.nextLong());
        HttpURLConnection conn = (HttpURLConnection) new URL(
                String.format(API, token, "sendPhoto")).openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(600000);

        DataOutputStream out = new DataOutputStream(conn.getOutputStream());
        writeField(out, boundary, "chat_id", chatId);
        if (caption != null && !caption.isEmpty()) {
            writeField(out, boundary, "caption", caption);
        }
        writeFile(out, boundary, "photo", media);
        out.writeBytes("--" + boundary + "--\r\n");
        out.flush();
        out.close();

        int code = conn.getResponseCode();
        String body = readBody(conn);
        conn.disconnect();
        JSONObject js = new JSONObject(body);
        if (code == 200 && js.optBoolean("ok")) {
            return js;
        }
        throw new Exception(body.length() > 300 ? body.substring(0, 300) : body);
    }

    public static void sendMediaGroup(String token, String chatId, List<File> files, String caption) throws Exception {
        if (files.size() == 1) {
            sendPhoto(token, chatId, files.get(0), caption);
            return;
        }
        String boundary = "----weba" + Long.toHexString(RND.nextLong());
        HttpURLConnection conn = (HttpURLConnection) new URL(
                String.format(API, token, "sendMediaGroup")).openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setConnectTimeout(60000);
        conn.setReadTimeout(1200000);

        JSONArray attach = new JSONArray();
        for (int i = 0; i < files.size(); i++) {
            JSONObject obj = new JSONObject();
            obj.put("type", "photo");
            obj.put("media", "attach://img" + i);
            attach.put(obj);
        }
        DataOutputStream out = new DataOutputStream(conn.getOutputStream());
        writeField(out, boundary, "chat_id", chatId);
        writeField(out, boundary, "media", attach.toString());
        if (caption != null && !caption.isEmpty()) {
            writeField(out, boundary, "caption", caption);
        }
        for (int j = 0; j < files.size(); j++) {
            writeFile(out, boundary, "img" + j, files.get(j));
        }
        out.writeBytes("--" + boundary + "--\r\n");
        out.flush();
        out.close();

        int code = conn.getResponseCode();
        String body = readBody(conn);
        conn.disconnect();
        JSONObject js = new JSONObject(body);
        if (code == 200 && js.optBoolean("ok")) {
            return;
        }
        throw new Exception(body.length() > 300 ? body.substring(0, 300) : body);
    }

    private static void writeField(DataOutputStream out, String boundary, String name, String value) throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }

    private static void writeFile(DataOutputStream out, String boundary, String name, File file) throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + file.getName() + "\"\r\n");
        out.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
        BufferedInputStream in = new BufferedInputStream(new FileInputStream(file));
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        out.writeBytes("\r\n");
    }

    private static String readBody(HttpURLConnection conn) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        InputStream in = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            in.close();
        }
        return baos.toString("UTF-8");
    }
}