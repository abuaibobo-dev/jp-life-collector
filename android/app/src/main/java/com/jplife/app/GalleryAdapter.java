package com.jplife.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.VH> {

    public static class Item {
        public final File file;
        public final String title;

        public Item(File file, String title) {
            this.file = file;
            this.title = title;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final LruCache<String, Bitmap> cache;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int cellWidth;

    public GalleryAdapter(int screenWidth, int cols) {
        int padding = dp(screenWidth, 16);
        cellWidth = (screenWidth - padding) / cols;
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        cache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        setHasStableIds(true);
    }

    private static int dp(int px, int d) {
        return px - d;
    }

    public void setItems(List<Item> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).file.getAbsolutePath().hashCode();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ImageView iv = new ImageView(parent.getContext());
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(4, 4, 4, 4);
        iv.setLayoutParams(lp);
        iv.setBackgroundColor(0xFF1A222B);
        return new VH(iv);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Item it = items.get(position);
        Bitmap bmp = cache.get(it.file.getAbsolutePath());
        holder.image.setImageBitmap(bmp);
        if (bmp == null) {
            load(holder, it);
        }
    }

    private void load(VH holder, Item it) {
        holder.boundKey = it.file.getAbsolutePath();
        pool.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(it.file.getAbsolutePath(), o);
            int sample = 1;
            while (o.outHeight / sample > 512) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, sample);
            Bitmap bmp = BitmapFactory.decodeFile(it.file.getAbsolutePath(), opts);
            if (bmp != null) {
                int w = bmp.getWidth();
                int h = bmp.getHeight();
                final Bitmap scaled = h > 0 ? scale(bmp, cellWidth, h * cellWidth / Math.max(1, w)) : bmp;
                cache.put(it.file.getAbsolutePath(), scaled);
                main.post(() -> {
                    if (holder.boundKey.equals(it.file.getAbsolutePath())) {
                        holder.image.setImageBitmap(scaled);
                    }
                });
            }
        });
    }

    private static Bitmap scale(Bitmap src, int targetW, int targetH) {
        return Bitmap.createScaledBitmap(src, Math.max(1, targetW), Math.max(1, targetH), true);
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView image;
        String boundKey = "";

        VH(ImageView image) {
            super(image);
            this.image = image;
        }
    }
}