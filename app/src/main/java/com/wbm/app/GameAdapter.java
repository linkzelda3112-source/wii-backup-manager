package com.wbm.app;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.List;
import java.util.Set;

/** Adaptador de la cuadrícula de juegos, con carátula (GameTDB, con caché) y selección múltiple. */
final class GameAdapter extends BaseAdapter {
    private final Context ctx;
    private final List<Game> games;
    private final Set<Integer> selected;
    private final Runnable onToggle;
    private final java.util.function.IntConsumer onLongPress;
    private final int cardWidthPx;

    GameAdapter(Context ctx, List<Game> games, Set<Integer> selected, int cardWidthPx, Runnable onToggle,
            java.util.function.IntConsumer onLongPress) {
        this.ctx = ctx;
        this.games = games;
        this.selected = selected;
        this.cardWidthPx = cardWidthPx;
        this.onToggle = onToggle;
        this.onLongPress = onLongPress;
    }

    @Override
    public int getCount() {
        return games.size();
    }

    @Override
    public Object getItem(int position) {
        return games.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Game g = games.get(position);
        Holder h;
        View root;
        if (convertView != null && convertView.getTag() instanceof Holder) {
            root = convertView;
            h = (Holder) convertView.getTag();
        } else {
            h = new Holder();
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(4), dp(4), dp(4), dp(8));

            FrameLayout coverWrap = new FrameLayout(ctx);
            ImageView cover = new ImageView(ctx);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int coverH = Math.round(cardWidthPx * 1.4f);
            coverWrap.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, coverH));
            View badge = new View(ctx);
            FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(dp(14), dp(14));
            badgeLp.gravity = Gravity.TOP | Gravity.END;
            badgeLp.setMargins(0, dp(4), dp(4), 0);
            coverWrap.addView(badge, badgeLp);
            col.addView(coverWrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, coverH));

            TextView title = new TextView(ctx);
            title.setMaxLines(2);
            title.setTextSize(12);
            title.setGravity(Gravity.CENTER_HORIZONTAL);
            col.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView subtitle = new TextView(ctx);
            subtitle.setMaxLines(1);
            subtitle.setTextSize(10);
            subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
            subtitle.setTextColor(Color.GRAY);
            col.addView(subtitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            h.root = col;
            h.cover = cover;
            h.title = title;
            h.subtitle = subtitle;
            h.badge = badge;
            col.setTag(h);
            root = col;
        }

        h.title.setText(g.displayTitle());
        h.subtitle.setText(g.kindLabel() + " · " + Fmt.size(g.totalSize));
        boolean isSel = selected.contains(position);
        root.setBackgroundColor(isSel ? Color.argb(60, 33, 150, 243) : Color.TRANSPARENT);
        h.badge.setBackgroundColor(g.kind == Game.Kind.WBFS ? Color.parseColor("#2E7D32")
                : g.kind == Game.Kind.RVZ ? Color.parseColor("#6A1B9A") : Color.parseColor("#1565C0"));

        Glide.with(ctx)
                .load(CoverArt.coverUrl(g))
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .error(Glide.with(ctx).load(CoverArt.flatCoverUrl(g)).diskCacheStrategy(DiskCacheStrategy.DATA))
                .centerCrop()
                .into(h.cover);

        root.setOnClickListener(v -> {
            if (!selected.remove(position)) selected.add(position);
            notifyDataSetChanged();
            if (onToggle != null) onToggle.run();
        });
        root.setOnLongClickListener(v -> {
            if (onLongPress != null) onLongPress.accept(position);
            return true;
        });
        return root;
    }

    private static final class Holder {
        View root;
        ImageView cover;
        TextView title;
        TextView subtitle;
        View badge;
    }
}
