package com.eddyizm.tempus.glide;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.signature.ObjectKey;
import com.eddyizm.tempus.App;
import com.eddyizm.tempus.R;
import com.eddyizm.tempus.util.Preferences;
import com.eddyizm.tempus.util.Util;
import com.google.android.material.elevation.SurfaceColors;

import java.util.Map;
import java.util.WeakHashMap;

public class CustomGlideRequest {
    private static final String TAG = "CustomGlideRequest";

    public static final int CORNER_RADIUS = Preferences.isCornerRoundingEnabled() ? Preferences.getRoundedCornerSize() : 1;

    public static final DiskCacheStrategy DEFAULT_DISK_CACHE_STRATEGY = DiskCacheStrategy.ALL;

    public enum ResourceType {
        Unknown,
        Album,
        Artist,
        Folder,
        Directory,
        Playlist,
        Podcast,
        Radio,
        Song,
    }

    public static RequestOptions createRequestOptions(Context context, String item, ResourceType type) {
        Placeholders placeholders = placeholdersFor(context);
        Drawable missing = placeholders.get(context, type);
        return new RequestOptions()
                .placeholder(new ColorDrawable(placeholders.surfaceColor))
                .fallback(missing)
                .error(missing)
                .diskCacheStrategy(DEFAULT_DISK_CACHE_STRATEGY)
                .signature(new ObjectKey(item != null ? item : 0))
                .transform(new CenterCrop(), new RoundedCorners(CustomGlideRequest.CORNER_RADIUS));
    }

    /**
     * The placeholder colour and the missing-cover drawables, worked out once per themed context.
     * Every grid row bound used to resolve the colour and inflate two vector drawables, though
     * they only show when a cover is missing. Each request still gets its own drawable instance.
     */
    private static final Map<Context, Placeholders> PLACEHOLDERS = new WeakHashMap<>();

    private static Placeholders placeholdersFor(Context context) {
        synchronized (PLACEHOLDERS) {
            Placeholders placeholders = PLACEHOLDERS.get(context);
            if (placeholders == null) {
                placeholders = new Placeholders(SurfaceColors.SURFACE_5.getColor(context));
                PLACEHOLDERS.put(context, placeholders);
            }
            return placeholders;
        }
    }

    private static final class Placeholders {
        final int surfaceColor;
        private final Drawable.ConstantState[] states = new Drawable.ConstantState[ResourceType.values().length];

        Placeholders(int surfaceColor) {
            this.surfaceColor = surfaceColor;
        }

        @Nullable
        synchronized Drawable get(Context context, ResourceType type) {
            int res = placeholderRes(type);
            if (res == 0) return new ColorDrawable(surfaceColor);

            Drawable.ConstantState state = states[type.ordinal()];
            if (state != null) return state.newDrawable(context.getResources(), context.getTheme());

            Drawable drawable = AppCompatResources.getDrawable(context, res);
            if (drawable != null) states[type.ordinal()] = drawable.getConstantState();
            return drawable;
        }
    }

    private static int placeholderRes(ResourceType type) {
        switch (type) {
            case Album:
                return R.drawable.ic_placeholder_album;
            case Artist:
                return R.drawable.ic_placeholder_artist;
            case Folder:
                return R.drawable.ic_placeholder_folder;
            case Directory:
                return R.drawable.ic_placeholder_directory;
            case Playlist:
                return R.drawable.ic_placeholder_playlist;
            case Podcast:
                return R.drawable.ic_placeholder_podcast;
            case Radio:
                return R.drawable.ic_placeholder_radio;
            case Song:
                return R.drawable.ic_placeholder_song;
            default:
            case Unknown:
                return 0;
        }
    }

    public static String createUrl(String item, int size) {
        // TODO Removed log statement but we should considering caching cover art using a hash
        Map<String, String> params = App.getSubsonicClientInstance(false).getParams();

        StringBuilder uri = new StringBuilder();

        uri.append(App.getSubsonicClientInstance(false).getUrl());
        uri.append("getCoverArt");

        if (params.containsKey("u") && params.get("u") != null)
            uri.append("?u=").append(Util.encode(params.get("u")));
        if (params.containsKey("p") && params.get("p") != null)
            uri.append("&p=").append(params.get("p"));
        if (params.containsKey("s") && params.get("s") != null)
            uri.append("&s=").append(params.get("s"));
        if (params.containsKey("t") && params.get("t") != null)
            uri.append("&t=").append(params.get("t"));
        if (params.containsKey("v") && params.get("v") != null)
            uri.append("&v=").append(params.get("v"));
        if (params.containsKey("c") && params.get("c") != null)
            uri.append("&c=").append(params.get("c"));
        if (size != -1)
            uri.append("&size=").append(size);

        uri.append("&id=").append(item);

        return uri.toString();
    }

    public static void loadAlbumArtBitmap(Context context,
                                          String coverId,
                                          int size,
                                          CustomTarget<Bitmap> target) {
        String url = createUrl(coverId, size);
        var builder = Glide.with(context)
                .asBitmap()
                .load(url)
                .apply(createRequestOptions(context, coverId, ResourceType.Album));
        if (Preferences.isDataSavingMode()) {
            builder = builder.onlyRetrieveFromCache(true);
        }
        builder.into(target);
    }

    public static class Builder {
        private final RequestManager requestManager;
        private final RequestOptions options;
        private final String coverId;
        private String item;
        @Nullable
        private String preview;

        private Builder(Context context, String item, ResourceType type) {
            this.requestManager = Glide.with(context);
            this.coverId = item;

            // Lists and grids get the small copy; fullSize() asks for the big one.
            if (item != null) {
                this.item = createUrl(item, Preferences.getListImageSize());
            }

            // Applied per request: setting them as the RequestManager's defaults (shared by the
            // whole activity) cost a synchronized merge on every row bound.
            options = createRequestOptions(context, item, type);
        }

        public static Builder from(Context context, String item, ResourceType type) {
            return new Builder(context, item, type);
        }

        /**
         * For a cover shown large (album or artist page, player): loads the Settings image size,
         * showing the small list copy (usually already cached) until it arrives.
         */
        public Builder fullSize() {
            if (coverId != null) {
                int full = Preferences.getImageSize();
                int list = Preferences.getListImageSize();
                if (full != list) {
                    preview = item;
                    item = createUrl(coverId, full);
                }
            }
            return this;
        }

        public RequestBuilder<Drawable> build() {
            RequestBuilder<Drawable> builder = requestManager
                    .load(item)
                    .apply(options)
                    .transition(DrawableTransitionOptions.withCrossFade());
            if (preview != null) {
                builder = builder.thumbnail(requestManager.load(preview).apply(options));
            }
            if (Preferences.isDataSavingMode()) {
                builder = builder.onlyRetrieveFromCache(true);
            }
            return builder;
        }
    }
}
