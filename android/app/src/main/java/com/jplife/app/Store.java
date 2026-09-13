package com.jplife.app;

import android.content.Context;

import java.io.File;

public class Store {

    public static File downloadDir(Context c) {
        return new File(c.getFilesDir(), "downloads");
    }

    public static File sentDir(Context c) {
        return new File(c.getFilesDir(), "sent");
    }

    public static File archiveFile(Context c) {
        return new File(c.getFilesDir(), "archive.txt");
    }

    public static String titleOf(File media) {
        String name = media.getName();
        String stem = name;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        int u = stem.lastIndexOf('_');
        if (u > 0) stem = stem.substring(0, u);
        return stem;
    }
}