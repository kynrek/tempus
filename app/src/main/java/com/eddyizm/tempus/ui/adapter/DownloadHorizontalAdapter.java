package com.eddyizm.tempus.ui.adapter;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.RecyclerView;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.databinding.ItemHorizontalDownloadBinding;
import com.eddyizm.tempus.glide.CustomGlideRequest;
import com.eddyizm.tempus.helper.recyclerview.FastScrollbar;
import com.eddyizm.tempus.interfaces.ClickCallback;
import com.eddyizm.tempus.model.Download;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.util.AppExecutors;
import com.eddyizm.tempus.util.Constants;
import com.eddyizm.tempus.util.MusicUtil;
import com.eddyizm.tempus.util.Util;

import java.text.CollationKey;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@UnstableApi
public class DownloadHorizontalAdapter extends RecyclerView.Adapter<DownloadHorizontalAdapter.ViewHolder> implements StandardViewTypeAdapter, FastScrollbar.BubbleTextGetter {
    private final ClickCallback click;

    private String view;
    private String filterKey;
    private String filterValue;

    private List<Child> songs;
    private List<Child> shuffling;
    private List<Child> grouped;

    private Map<String, Integer> counts = Collections.emptyMap();
    private int generation;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Where the list is worked out, and where the result is shown; tests run both in place. */
    static java.util.concurrent.Executor listWork = command -> AppExecutors.compute().execute(command);
    static java.util.concurrent.Executor listResult = command -> MAIN.post(command);

    private final java.util.Map<String, String> playlistCoverCache = new java.util.HashMap<>();
    private final java.util.Set<String> playlistCoverLookups = new java.util.HashSet<>();

    public DownloadHorizontalAdapter(ClickCallback click) {
        this.click = click;
        this.view = Constants.DOWNLOAD_TYPE_TRACK;
        this.songs = Collections.emptyList();
        this.grouped = Collections.emptyList();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemHorizontalDownloadBinding view = ItemHorizontalDownloadBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        switch (view) {
            case Constants.DOWNLOAD_TYPE_TRACK:
                initTrackLayout(holder, position);
                break;
            case Constants.DOWNLOAD_TYPE_ALBUM:
                initAlbumLayout(holder, position);
                break;
            case Constants.DOWNLOAD_TYPE_ARTIST:
                initArtistLayout(holder, position);
                break;
            case Constants.DOWNLOAD_TYPE_GENRE:
                initGenreLayout(holder, position);
                break;
            case Constants.DOWNLOAD_TYPE_YEAR:
                initYearLayout(holder, position);
                break;
            case Constants.DOWNLOAD_TYPE_PLAYLIST:
                initPlaylistLayout(holder, position);
                break;
        }
    }

    @Override
    public int getItemCount() {
        return grouped.size();
    }

    public void setItems(String view, String filterKey, String filterValue, List<Child> songs) {
        setItems(view, filterKey, filterValue, songs, null);
    }

    /**
     * Groups, sorts and counts on a worker thread (about 3,000 songs on the car box), then shows the
     * result and runs {@code onApplied} on the main thread. A newer call replaces an older one still
     * being worked out.
     */
    public void setItems(String view, String filterKey, String filterValue, List<Child> songs, Runnable onApplied) {
        final String newView = filterValue != null ? view : filterKey;
        final List<Child> input = songs != null ? new ArrayList<>(songs) : new ArrayList<>();
        final int gen = ++generation;

        listWork.execute(() -> {
            List<Child> result = groupSong(newView, filterKey, filterValue, input);
            if (!Constants.DOWNLOAD_TYPE_TRACK.equals(newView)) {
                result = sortByDisplayLabel(newView, result);
            }
            List<Child> shuffle = shufflingSong(filterKey, filterValue, new ArrayList<>(input));
            Map<String, Integer> counts = countsFor(newView, input);
            final List<Child> grouping = result;

            listResult.execute(() -> {
                if (gen != generation) return;
                this.view = newView;
                this.filterKey = filterKey;
                this.filterValue = filterValue;
                this.songs = input;
                this.grouped = grouping;
                this.shuffling = shuffle;
                this.counts = counts;
                notifyDataSetChanged();
                if (onApplied != null) onApplied.run();
            });
        });
    }

    private List<Child> sortByDisplayLabel(String view, List<Child> items) {
        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        boolean album = Constants.DOWNLOAD_TYPE_ALBUM.equals(view);
        // Collation keys once per item, not two labels and two collations per comparison.
        List<Object[]> keyed = new ArrayList<>(items.size());
        for (Child item : items) {
            String label = getDisplayLabel(view, item);
            String albumName = album ? item.getAlbum() : null;
            keyed.add(new Object[]{
                    label != null ? collator.getCollationKey(label) : null,
                    albumName != null ? collator.getCollationKey(albumName) : null,
                    item});
        }
        Comparator<Object[]> byLabel = Comparator.comparing(k -> (CollationKey) k[0], Comparator.nullsLast(Comparator.naturalOrder()));
        if (album) {
            byLabel = byLabel.thenComparing(k -> (CollationKey) k[1], Comparator.nullsLast(Comparator.naturalOrder()));
        }
        keyed.sort(byLabel);
        List<Child> sorted = new ArrayList<>(keyed.size());
        for (Object[] k : keyed) sorted.add((Child) k[2]);
        return sorted;
    }

    private String getDisplayLabel(Child item) {
        return getDisplayLabel(view, item);
    }

    private static String getDisplayLabel(String view, Child item) {
        if (item == null) return null;

        String label;
        switch (view) {
            case Constants.DOWNLOAD_TYPE_TRACK:
                String trackAlbumArtist = albumArtistOrNull(item);
                label = trackAlbumArtist != null ? trackAlbumArtist : item.getAlbum();
                if (label == null || label.trim().isEmpty()) {
                    label = item.getTitle();
                }
                break;
            case Constants.DOWNLOAD_TYPE_ALBUM:
                String albumArtist = albumArtistOrNull(item);
                label = albumArtist != null ? albumArtist : item.getAlbum();
                break;
            case Constants.DOWNLOAD_TYPE_ARTIST:
                label = item.getArtist();
                break;
            case Constants.DOWNLOAD_TYPE_GENRE:
                label = item.getGenre();
                break;
            case Constants.DOWNLOAD_TYPE_YEAR:
                label = item.getYear() == null ? null : item.getYear().toString();
                break;
            case Constants.DOWNLOAD_TYPE_PLAYLIST:
                label = item instanceof Download ? ((Download) item).getPlaylistName() : null;
                break;
            default:
                label = null;
                break;
        }

        return label == null || label.trim().isEmpty() ? null : label.trim();
    }

    @Override
    public String getTextToShowInBubble(int position) {
        if (position < 0 || position >= grouped.size()) return null;

        String label = getDisplayLabel(grouped.get(position));
        if (label == null) return null;

        int codePoint = label.codePointAt(0);
        if (!Character.isLetterOrDigit(codePoint)) return "#";
        return new String(Character.toChars(codePoint)).toUpperCase(Locale.getDefault());
    }

    public Child getItem(int id) {
        return grouped.get(id);
    }

    public List<Child> getShuffling() {
        return shuffling;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    private static List<Child> groupSong(String view, String filterKey, String filterValue, List<Child> songs) {
        switch (view) {
            case Constants.DOWNLOAD_TYPE_TRACK:
                return filterSong(filterKey, filterValue, songs.stream().filter(song -> Objects.nonNull(song.getId())).filter(Util.distinctByKey(Child::getId)).collect(Collectors.toList()));
            case Constants.DOWNLOAD_TYPE_ALBUM:
                return filterSong(filterKey, filterValue, songs.stream().filter(song -> Objects.nonNull(song.getAlbumId())).filter(Util.distinctByKey(Child::getAlbumId)).collect(Collectors.toList()));
            case Constants.DOWNLOAD_TYPE_ARTIST:
                return filterSong(filterKey, filterValue, songs.stream().filter(song -> Objects.nonNull(song.getArtistId())).filter(Util.distinctByKey(Child::getArtistId)).sorted(Comparator.comparing(Child::getArtist, Comparator.nullsLast(Comparator.naturalOrder()))).collect(Collectors.toList()));
            case Constants.DOWNLOAD_TYPE_GENRE:
                return filterSong(filterKey, filterValue, songs.stream().filter(song -> Objects.nonNull(song.getGenre())).filter(Util.distinctByKey(Child::getGenre)).collect(Collectors.toList()));
            case Constants.DOWNLOAD_TYPE_YEAR:
                return filterSong(filterKey, filterValue, songs.stream().filter(song -> Objects.nonNull(song.getYear())).filter(Util.distinctByKey(Child::getYear)).collect(Collectors.toList()));
            case Constants.DOWNLOAD_TYPE_PLAYLIST:
                return filterSong(filterKey, filterValue, songs.stream()
                        .filter(song -> song instanceof Download && ((Download) song).getPlaylistId() != null)
                        .filter(Util.distinctByKey(song -> ((Download) song).getPlaylistId()))
                        .collect(Collectors.toList()));
        }

        return Collections.emptyList();
    }

    private static List<Child> filterSong(String filterKey, String filterValue, List<Child> songs) {
        if (filterValue != null) {
            switch (filterKey) {
                case Constants.DOWNLOAD_TYPE_TRACK:
                    return songs.stream().filter(child -> child.getId().equals(filterValue)).collect(Collectors.toList());
                case Constants.DOWNLOAD_TYPE_ALBUM:
                    return songs.stream().filter(child -> Objects.equals(child.getAlbumId(), filterValue)).collect(Collectors.toList());
                case Constants.DOWNLOAD_TYPE_GENRE:
                    return songs.stream().filter(child -> Objects.equals(child.getGenre(), filterValue)).collect(Collectors.toList());
                case Constants.DOWNLOAD_TYPE_YEAR:
                    return songs.stream().filter(child -> Objects.equals(child.getYear(), Integer.valueOf(filterValue))).collect(Collectors.toList());
                case Constants.DOWNLOAD_TYPE_ARTIST:
                    return songs.stream().filter(child -> Objects.equals(child.getArtistId(), filterValue)).collect(Collectors.toList());
                case Constants.DOWNLOAD_TYPE_PLAYLIST:
                    return songs.stream().filter(child -> child instanceof Download && Objects.equals(((Download) child).getPlaylistId(), filterValue)).collect(Collectors.toList());
            }
        }

        return songs;
    }

    private static List<Child> shufflingSong(String filterKey, String filterValue, List<Child> songs) {
        if (filterValue == null) {
            return songs;
        }

        switch (filterKey) {
            case Constants.DOWNLOAD_TYPE_TRACK:
                return songs.stream().filter(child -> child.getId().equals(filterValue)).collect(Collectors.toList());
            case Constants.DOWNLOAD_TYPE_ALBUM:
                return songs.stream().filter(child -> Objects.equals(child.getAlbumId(), filterValue)).collect(Collectors.toList());
            case Constants.DOWNLOAD_TYPE_GENRE:
                return songs.stream().filter(child -> Objects.equals(child.getGenre(), filterValue)).collect(Collectors.toList());
            case Constants.DOWNLOAD_TYPE_YEAR:
                return songs.stream().filter(child -> Objects.equals(child.getYear(), Integer.valueOf(filterValue))).collect(Collectors.toList());
            case Constants.DOWNLOAD_TYPE_ARTIST:
                return songs.stream().filter(child -> Objects.equals(child.getArtistId(), filterValue)).collect(Collectors.toList());
            case Constants.DOWNLOAD_TYPE_PLAYLIST:
                return songs.stream().filter(child -> child instanceof Download && Objects.equals(((Download) child).getPlaylistId(), filterValue)).collect(Collectors.toList());
            default:
                return songs;
        }
    }

    /** Songs per album / artist / genre / year / playlist, counted once instead of per bound row. */
    private static Map<String, Integer> countsFor(String view, List<Child> songs) {
        Map<String, Integer> counts = new HashMap<>();
        for (Child child : songs) {
            String value;
            switch (view) {
                case Constants.DOWNLOAD_TYPE_ALBUM: value = child.getAlbumId(); break;
                case Constants.DOWNLOAD_TYPE_ARTIST: value = child.getArtistId(); break;
                case Constants.DOWNLOAD_TYPE_GENRE: value = child.getGenre(); break;
                case Constants.DOWNLOAD_TYPE_YEAR: value = child.getYear() != null ? child.getYear().toString() : null; break;
                case Constants.DOWNLOAD_TYPE_PLAYLIST:
                    value = child instanceof Download ? ((Download) child).getPlaylistId() : null;
                    break;
                default: value = null;
            }
            if (value != null) counts.merge(view + "\u0000" + value, 1, Integer::sum);
        }
        return counts;
    }

    private String countSong(String filterKey, String filterValue, List<Child> songs) {
        Integer count = filterValue != null ? counts.get(filterKey + "\u0000" + filterValue) : null;
        return String.valueOf(count != null ? count : 0);
    }

    private void initTrackLayout(ViewHolder holder, int position) {
        Child song = grouped.get(position);

        holder.item.downloadedItemTitleTextView.setText(song.getTitle());
        holder.item.downloadedItemSubtitleTextView.setText(
                holder.itemView.getContext().getString(
                        R.string.song_subtitle_formatter,
                        song.getArtist(),
                        MusicUtil.getReadableDurationString(song.getDuration(), false),
                        MusicUtil.getReadableAudioQualityString(song)
                )
        );

        holder.item.downloadedItemPreTextView.setText(song.getAlbum());

        CustomGlideRequest.Builder
                .from(holder.itemView.getContext(), song.getCoverArtId(), CustomGlideRequest.ResourceType.Song)
                .build()
                .into(holder.item.itemCoverImageView);

        holder.item.itemCoverImageView.setVisibility(View.VISIBLE);
        holder.item.downloadedItemMoreButton.setVisibility(View.VISIBLE);
        holder.item.divider.setVisibility(View.VISIBLE);

        if (position > 0 && grouped.get(position - 1) != null && !sameAlbum(grouped.get(position - 1), grouped.get(position))) {
            holder.item.divider.setPadding(0, (int) holder.itemView.getContext().getResources().getDimension(R.dimen.downloaded_item_padding), 0, 0);
        } else {
            if (position > 0) holder.item.divider.setVisibility(View.GONE);
        }
    }

    private static String albumArtistOrNull(Child song) {
        if (song == null) return null;
        String albumArtist = song.getAlbumArtist();
        return albumArtist != null && !albumArtist.trim().isEmpty() ? albumArtist.trim() : null;
    }

    private static String albumArtistOrArtist(Child song) {
        String albumArtist = albumArtistOrNull(song);
        return albumArtist != null ? albumArtist : song.getArtist();
    }


    // Album ids are missing on some servers, and two nulls are not the same album.
    // Follows the sort in DownloadDao, which leads on the album artist and falls back to the album.
    private static boolean sameAlbumGroup(Child one, Child other) {
        String oneArtist = albumArtistOrNull(one);
        String otherArtist = albumArtistOrNull(other);

        if (oneArtist != null || otherArtist != null) {
            return Objects.equals(oneArtist, otherArtist);
        }

        return sameAlbum(one, other);
    }

    private static boolean sameAlbum(Child one, Child other) {
        if (one.getAlbumId() != null && other.getAlbumId() != null) {
            return Objects.equals(one.getAlbumId(), other.getAlbumId());
        }
        return Objects.equals(one.getAlbum(), other.getAlbum());
    }

    private void initAlbumLayout(ViewHolder holder, int position) {
        Child song = grouped.get(position);

        holder.item.downloadedItemTitleTextView.setText(song.getAlbum());
        holder.item.downloadedItemSubtitleTextView.setText(holder.itemView.getContext().getString(R.string.download_item_single_subtitle_formatter, countSong(Constants.DOWNLOAD_TYPE_ALBUM, song.getAlbumId(), songs)));
        holder.item.downloadedItemPreTextView.setText(albumArtistOrArtist(song));

        CustomGlideRequest.Builder
                .from(holder.itemView.getContext(), song.getCoverArtId(), CustomGlideRequest.ResourceType.Song)
                .build()
                .into(holder.item.itemCoverImageView);

        holder.item.itemCoverImageView.setVisibility(View.VISIBLE);
        holder.item.downloadedItemMoreButton.setVisibility(View.VISIBLE);
        holder.item.divider.setVisibility(View.VISIBLE);

        if (position > 0 && grouped.get(position - 1) != null && !sameAlbumGroup(grouped.get(position - 1), grouped.get(position))) {
            holder.item.divider.setPadding(0, (int) holder.itemView.getContext().getResources().getDimension(R.dimen.downloaded_item_padding), 0, 0);
        } else {
            if (position > 0) holder.item.divider.setVisibility(View.GONE);
        }
    }

    private void initArtistLayout(ViewHolder holder, int position) {
        Child song = grouped.get(position);

        holder.item.downloadedItemTitleTextView.setText(song.getArtist());
        holder.item.downloadedItemSubtitleTextView.setText(holder.itemView.getContext().getString(R.string.download_item_single_subtitle_formatter, countSong(Constants.DOWNLOAD_TYPE_ARTIST, song.getArtistId(), songs)));

        CustomGlideRequest.Builder
                .from(holder.itemView.getContext(), song.getCoverArtId(), CustomGlideRequest.ResourceType.Song)
                .build()
                .into(holder.item.itemCoverImageView);

        holder.item.itemCoverImageView.setVisibility(View.VISIBLE);
        holder.item.downloadedItemMoreButton.setVisibility(View.VISIBLE);
        holder.item.divider.setVisibility(View.GONE);
    }

    private void initGenreLayout(ViewHolder holder, int position) {
        Child song = grouped.get(position);

        holder.item.downloadedItemTitleTextView.setText(song.getGenre());
        holder.item.downloadedItemSubtitleTextView.setText(holder.itemView.getContext().getString(R.string.download_item_single_subtitle_formatter, countSong(Constants.DOWNLOAD_TYPE_GENRE, song.getGenre(), songs)));

        holder.item.itemCoverImageView.setVisibility(View.GONE);
        holder.item.downloadedItemMoreButton.setVisibility(View.VISIBLE);
        holder.item.divider.setVisibility(View.GONE);
    }

    private void initYearLayout(ViewHolder holder, int position) {
        Child song = grouped.get(position);

        holder.item.downloadedItemTitleTextView.setText(String.valueOf(song.getYear()));
        holder.item.downloadedItemSubtitleTextView.setText(holder.itemView.getContext().getString(R.string.download_item_single_subtitle_formatter, countSong(Constants.DOWNLOAD_TYPE_YEAR, song.getYear().toString(), songs)));

        holder.item.itemCoverImageView.setVisibility(View.GONE);
        holder.item.downloadedItemMoreButton.setVisibility(View.VISIBLE);
        holder.item.divider.setVisibility(View.GONE);
    }

    private void initPlaylistLayout(ViewHolder holder, int position) {
        Child song = grouped.get(position);
        if (song instanceof Download) {
            Download download = (Download) song;
            holder.item.downloadedItemTitleTextView.setText(download.getPlaylistName());
            holder.item.downloadedItemSubtitleTextView.setText(holder.itemView.getContext().getString(R.string.download_item_single_subtitle_formatter, countSong(Constants.DOWNLOAD_TYPE_PLAYLIST, download.getPlaylistId(), songs)));

            String playlistId = download.getPlaylistId();
            String coverArtId = playlistCoverCache.get(playlistId);

            if (coverArtId == null) {
                // Async lookup from local playlist cache
                // One lookup per playlist (rebinding used to start another thread each time), and
                // the row is found again by id: the list may have moved by the time it answers.
                if (playlistCoverLookups.add(playlistId)) {
                    AppExecutors.io().execute(() -> {
                        String cachedCover = com.eddyizm.tempus.database.AppDatabase.getInstance().playlistDao().getPlaylistCoverArtId(playlistId);
                        if (cachedCover == null) return;
                        MAIN.post(() -> {
                            playlistCoverCache.put(playlistId, cachedCover);
                            for (int i = 0; i < grouped.size(); i++) {
                                Child row = grouped.get(i);
                                if (row instanceof Download && playlistId.equals(((Download) row).getPlaylistId())) {
                                    notifyItemChanged(i);
                                }
                            }
                        });
                    });
                }
                // Fallback to track cover art while loading
                coverArtId = download.getCoverArtId();
            }

            CustomGlideRequest.Builder
                    .from(holder.itemView.getContext(), coverArtId, CustomGlideRequest.ResourceType.Song)
                    .build()
                    .into(holder.item.itemCoverImageView);

            holder.item.itemCoverImageView.setVisibility(View.VISIBLE);
            holder.item.downloadedItemMoreButton.setVisibility(View.VISIBLE);
            holder.item.divider.setVisibility(View.GONE);
        }
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        ItemHorizontalDownloadBinding item;

        ViewHolder(ItemHorizontalDownloadBinding item) {
            super(item.getRoot());

            this.item = item;

            item.downloadedItemTitleTextView.setSelected(true);
            item.downloadedItemSubtitleTextView.setSelected(true);

            itemView.setOnClickListener(v -> onClick());
            itemView.setOnLongClickListener(v -> onLongClick());

            item.downloadedItemMoreButton.setOnClickListener(v -> onLongClick());
        }

        public void onClick() {
            Bundle bundle = new Bundle();

            switch (view) {
                case Constants.DOWNLOAD_TYPE_TRACK:
                    bundle.putParcelableArrayList(Constants.TRACKS_OBJECT, new ArrayList<>(grouped));
                    bundle.putInt(Constants.ITEM_POSITION, getBindingAdapterPosition());
                    click.onMediaClick(bundle);
                    break;
                case Constants.DOWNLOAD_TYPE_ALBUM:
                    bundle.putString(Constants.DOWNLOAD_TYPE_ALBUM, grouped.get(getBindingAdapterPosition()).getAlbumId());
                    click.onAlbumClick(bundle);
                    break;
                case Constants.DOWNLOAD_TYPE_ARTIST:
                    bundle.putString(Constants.DOWNLOAD_TYPE_ARTIST, grouped.get(getBindingAdapterPosition()).getArtistId());
                    click.onArtistClick(bundle);
                    break;
                case Constants.DOWNLOAD_TYPE_GENRE:
                    bundle.putString(Constants.DOWNLOAD_TYPE_GENRE, grouped.get(getBindingAdapterPosition()).getGenre());
                    click.onGenreClick(bundle);
                    break;
                case Constants.DOWNLOAD_TYPE_YEAR:
                    bundle.putString(Constants.DOWNLOAD_TYPE_YEAR, grouped.get(getBindingAdapterPosition()).getYear().toString());
                    click.onYearClick(bundle);
                    break;
                case Constants.DOWNLOAD_TYPE_PLAYLIST:
                    if (grouped.get(getBindingAdapterPosition()) instanceof Download) {
                        bundle.putString(Constants.DOWNLOAD_TYPE_PLAYLIST, ((Download) grouped.get(getBindingAdapterPosition())).getPlaylistId());
                        click.onPlaylistClick(bundle);
                    }
                    break;
            }
        }

        private boolean onLongClick() {
            ArrayList<Child> filteredSongs = new ArrayList<>();

            Bundle bundle = new Bundle();

            switch (view) {
                case Constants.DOWNLOAD_TYPE_TRACK:
                    filteredSongs.add(grouped.get(getBindingAdapterPosition()));
                    break;
                case Constants.DOWNLOAD_TYPE_ALBUM:
                    filteredSongs.addAll(filterSong(Constants.DOWNLOAD_TYPE_ALBUM, grouped.get(getBindingAdapterPosition()).getAlbumId(), songs));
                    break;
                case Constants.DOWNLOAD_TYPE_ARTIST:
                    filteredSongs.addAll(filterSong(Constants.DOWNLOAD_TYPE_ARTIST, grouped.get(getBindingAdapterPosition()).getArtistId(), songs));
                    break;
                case Constants.DOWNLOAD_TYPE_GENRE:
                    filteredSongs.addAll(filterSong(Constants.DOWNLOAD_TYPE_GENRE, grouped.get(getBindingAdapterPosition()).getGenre(), songs));
                    break;
                case Constants.DOWNLOAD_TYPE_YEAR:
                    filteredSongs.addAll(filterSong(Constants.DOWNLOAD_TYPE_YEAR, grouped.get(getBindingAdapterPosition()).getYear().toString(), songs));
                    break;
                case Constants.DOWNLOAD_TYPE_PLAYLIST:
                    if (grouped.get(getBindingAdapterPosition()) instanceof Download) {
                        filteredSongs.addAll(filterSong(Constants.DOWNLOAD_TYPE_PLAYLIST, ((Download) grouped.get(getBindingAdapterPosition())).getPlaylistId(), songs));
                    }
                    break;
            }

            if (filteredSongs.isEmpty()) return false;

            bundle.putParcelableArrayList(Constants.DOWNLOAD_GROUP, new ArrayList<>(filteredSongs));
            bundle.putString(Constants.DOWNLOAD_GROUP_TITLE, item.downloadedItemTitleTextView.getText().toString());
            bundle.putString(Constants.DOWNLOAD_GROUP_SUBTITLE, item.downloadedItemSubtitleTextView.getText().toString());
            click.onDownloadGroupLongClick(bundle);

            return true;
        }
    }
}
