package com.eddyizm.tempus.ui.adapter;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.databinding.ItemArtistReleaseBinding;
import com.eddyizm.tempus.glide.CustomGlideRequest;
import com.eddyizm.tempus.interfaces.ClickCallback;
import com.eddyizm.tempus.repository.AlbumRepository;
import com.eddyizm.tempus.service.MediaManager;
import com.eddyizm.tempus.subsonic.models.AlbumID3;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.util.Constants;
import com.eddyizm.tempus.viewmodel.ArtistPageViewModel;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * An artist's releases, oldest first. Each row opens the album, plays or queues it, or expands
 * to list its tracks in place (as on the album page), so you can scroll straight on to the
 * next album.
 */
@UnstableApi
public class ArtistReleaseAdapter extends RecyclerView.Adapter<ArtistReleaseAdapter.ViewHolder> {
    private final LifecycleOwner lifecycleOwner;
    private final ClickCallback click;
    private final AlbumRepository albumRepository = new AlbumRepository();
    private final Set<String> expanded = new HashSet<>();
    private final Map<String, List<Child>> tracks = new HashMap<>();
    private List<AlbumID3> releases = Collections.emptyList();
    @Nullable
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    public ArtistReleaseAdapter(LifecycleOwner lifecycleOwner, ClickCallback click) {
        this.lifecycleOwner = lifecycleOwner;
        this.click = click;
    }

    public void setItems(List<AlbumID3> releases) {
        this.releases = releases;
        notifyDataSetChanged();
    }

    public void setMediaBrowserListenableFuture(ListenableFuture<MediaBrowser> future) {
        this.mediaBrowserListenableFuture = future;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemArtistReleaseBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AlbumID3 album = releases.get(position);
        holder.item.releaseTitle.setText(album.getName());

        List<String> parts = new ArrayList<>();
        if (album.getYear() > 0) parts.add(String.valueOf(album.getYear()));
        String type = ArtistPageViewModel.sectionType(album);
        parts.add(Character.toUpperCase(type.charAt(0)) + type.substring(1));
        if (album.getSongCount() != null && album.getSongCount() > 0) {
            parts.add(holder.itemView.getResources().getQuantityString(R.plurals.artist_release_track_count, album.getSongCount(), album.getSongCount()));
        }
        holder.item.releaseSubtitle.setText(String.join(" · ", parts));

        CustomGlideRequest.Builder
                .from(holder.itemView.getContext(), album.getCoverArtId(), CustomGlideRequest.ResourceType.Album)
                .build()
                .into(holder.item.releaseCover);

        boolean open = expanded.contains(album.getId());
        holder.item.releaseExpandButton.setRotation(open ? 180f : 0f);
        holder.item.releaseExpandButton.setContentDescription(holder.itemView.getContext().getString(
                open ? R.string.artist_release_hide_tracks : R.string.artist_release_show_tracks));
        holder.item.releaseTracks.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open) {
            List<Child> albumTracks = tracks.get(album.getId());
            if (albumTracks != null) holder.trackAdapter(album).setItems(albumTracks);
        }
    }

    @Override
    public int getItemCount() {
        return releases.size();
    }

    /** Album order: disc, then track number. */
    private static List<Child> inAlbumOrder(List<Child> list) {
        List<Child> sorted = new ArrayList<>(list);
        sorted.sort(Comparator
                .comparing((Child song) -> song.getDiscNumber() != null ? song.getDiscNumber() : 1)
                .thenComparing(song -> song.getTrack() != null ? song.getTrack() : 0));
        return sorted;
    }

    private void withTracks(AlbumID3 album, Consumer<List<Child>> use) {
        List<Child> cached = tracks.get(album.getId());
        if (cached != null) {
            use.accept(cached);
            return;
        }
        albumRepository.getAlbumTracks(album.getId(), loaded -> {
            List<Child> ordered = inAlbumOrder(loaded);
            tracks.put(album.getId(), ordered);
            use.accept(ordered);
        });
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        final ItemArtistReleaseBinding item;
        @Nullable
        private SongHorizontalAdapter songAdapter;
        @Nullable
        private String songAdapterAlbumId;

        ViewHolder(ItemArtistReleaseBinding item) {
            super(item.getRoot());
            this.item = item;
            item.releaseTracks.setLayoutManager(new LinearLayoutManager(item.getRoot().getContext()));

            item.releaseHeader.setOnClickListener(v -> {
                AlbumID3 album = album();
                if (album == null) return;
                Bundle bundle = new Bundle();
                bundle.putParcelable(Constants.ALBUM_OBJECT, album.strippedForNav());
                click.onAlbumClick(bundle);
            });
            item.releaseHeader.setOnLongClickListener(v -> {
                AlbumID3 album = album();
                if (album == null) return false;
                Bundle bundle = new Bundle();
                bundle.putParcelable(Constants.ALBUM_OBJECT, album.strippedForNav());
                click.onAlbumLongClick(bundle);
                return true;
            });
            item.releaseExpandButton.setOnClickListener(v -> toggle());
            item.releasePlayButton.setOnClickListener(v -> {
                AlbumID3 album = album();
                if (album == null || mediaBrowserListenableFuture == null) return;
                withTracks(album, list -> {
                    if (!list.isEmpty()) MediaManager.startQueue(mediaBrowserListenableFuture, list, 0);
                });
            });
            item.releaseQueueButton.setOnClickListener(v -> {
                AlbumID3 album = album();
                if (album == null || mediaBrowserListenableFuture == null) return;
                withTracks(album, list -> {
                    if (list.isEmpty()) return;
                    MediaManager.enqueue(mediaBrowserListenableFuture, list, false);
                    SongHorizontalAdapter.showQueued(itemView, itemView.getResources().getQuantityString(R.plurals.album_added_to_queue, list.size(), list.size()));
                });
            });
        }

        @Nullable
        private AlbumID3 album() {
            int pos = getBindingAdapterPosition();
            return pos == RecyclerView.NO_POSITION ? null : releases.get(pos);
        }

        private void toggle() {
            AlbumID3 album = album();
            if (album == null) return;
            if (!expanded.remove(album.getId())) {
                expanded.add(album.getId());
                withTracks(album, list -> {
                    int pos = releases.indexOf(album);
                    if (pos >= 0) notifyItemChanged(pos);
                });
            }
            notifyItemChanged(getBindingAdapterPosition());
        }

        SongHorizontalAdapter trackAdapter(AlbumID3 album) {
            // The track adapter is built for one album (its disc titles), so a recycled row gets a new one.
            if (songAdapter == null || !album.getId().equals(songAdapterAlbumId)) {
                songAdapterAlbumId = album.getId();
                // A non-null album makes the rows look like the album page: track numbers, no
                // cover, no go-to-album button.
                songAdapter = new SongHorizontalAdapter(lifecycleOwner, click, false, false, album);
                item.releaseTracks.setAdapter(songAdapter);
            }
            if (mediaBrowserListenableFuture != null) songAdapter.setMediaBrowserListenableFuture(mediaBrowserListenableFuture);
            return songAdapter;
        }
    }
}
