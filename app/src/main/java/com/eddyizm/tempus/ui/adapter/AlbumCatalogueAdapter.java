package com.eddyizm.tempus.ui.adapter;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.eddyizm.tempus.databinding.ItemLibraryCatalogueAlbumBinding;
import com.eddyizm.tempus.glide.CustomGlideRequest;
import com.eddyizm.tempus.helper.recyclerview.AlphabetScrubber;
import com.eddyizm.tempus.helper.recyclerview.ScrubberSections;
import com.eddyizm.tempus.interfaces.ClickCallback;
import com.eddyizm.tempus.subsonic.models.AlbumID3;
import com.eddyizm.tempus.util.AlphabetSort;
import com.eddyizm.tempus.util.Constants;
import com.eddyizm.tempus.util.MusicUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class AlbumCatalogueAdapter extends RecyclerView.Adapter<AlbumCatalogueAdapter.ViewHolder> implements Filterable, StandardViewTypeAdapter, AlphabetScrubber.SectionProvider {
    private final ClickCallback click;
    private String sortOrder;
    private String currentFilter;
    private boolean showArtist;

    private final Filter filtering = new Filter() {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            List<AlbumID3> filteredList = new ArrayList<>();

            if (constraint == null || constraint.length() == 0) {
                filteredList.addAll(albumsFull);
            } else {
                String filterPattern = constraint.toString().toLowerCase().trim();
                currentFilter = filterPattern;

                for (AlbumID3 item : albumsFull) {
                    if (item.getName().toLowerCase().contains(filterPattern)) {
                        filteredList.add(item);
                    }
                }
            }

            FilterResults results = new FilterResults();
            results.values = filteredList;

            return results;
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            albums = (List<AlbumID3>) results.values;
            notifyDataSetChanged();
        }
    };

    private List<AlbumID3> albums;
    private List<AlbumID3> albumsFull;

    public AlbumCatalogueAdapter(ClickCallback click, boolean showArtist) {
        this.click = click;
        this.albums = Collections.emptyList();
        this.albumsFull = Collections.emptyList();
        this.currentFilter = "";
        this.showArtist = showArtist;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemLibraryCatalogueAlbumBinding view = ItemLibraryCatalogueAlbumBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        AlbumID3 album = albums.get(position);

        holder.item.albumNameLabel.setText(album.getName());
        holder.item.artistNameLabel.setText(album.getArtist());
        holder.item.artistNameLabel.setVisibility(showArtist ? View.VISIBLE : View.GONE);

        CustomGlideRequest.Builder
                .from(holder.itemView.getContext(), album.getCoverArtId(), CustomGlideRequest.ResourceType.Album)
                .build()
                .into(holder.item.albumCatalogueCoverImageView);
    }

    @Override
    public int getItemCount() {
        return albums.size();
    }

    public AlbumID3 getItem(int position) {
        return albums.get(position);
    }

    public void setItems(List<AlbumID3> albums) {
        this.albumsFull = new ArrayList<>(albums);
        filtering.filter(currentFilter);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public Filter getFilter() {
        return filtering;
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        ItemLibraryCatalogueAlbumBinding item;

        ViewHolder(ItemLibraryCatalogueAlbumBinding item) {
            super(item.getRoot());

            this.item = item;

            itemView.setOnClickListener(v -> onClick());
            itemView.setOnLongClickListener(v -> onLongClick());
        }

        private void onClick() {
            int position = getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return;
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.ALBUM_OBJECT, albums.get(position).strippedForNav());

            click.onAlbumClick(bundle);
        }

        private boolean onLongClick() {
            int position = getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return false;
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.ALBUM_OBJECT, albums.get(position).strippedForNav());

            click.onAlbumLongClick(bundle);

            return true;
        }
    }

    public void setItemsWithoutFilter(List<AlbumID3> albums) {
        this.albumsFull = new ArrayList<>(albums);
        this.albums = new ArrayList<>(albums);
        notifyDataSetChanged();
    }

    /** A–Z for the name orders, years for year, months (and days) for the recent orders. */
    @Override
    public ScrubberSections buildSections() {
        if (sortOrder == null) return null;
        List<AlbumID3> rows = albums;
        switch (sortOrder) {
            case Constants.ALBUM_ORDER_BY_NAME:
                return ScrubberSections.letters(rows.size(), i -> rows.get(i).getName());
            case Constants.ALBUM_ORDER_BY_ARTIST:
                return ScrubberSections.letters(rows.size(), i -> rows.get(i).getArtist());
            case Constants.ALBUM_ORDER_BY_YEAR:
                return ScrubberSections.years(rows.size(), i -> rows.get(i).getYear());
            case Constants.ALBUM_ORDER_BY_RECENTLY_ADDED:
                return ScrubberSections.months(rows.size(), i -> rows.get(i).getCreated(), Locale.getDefault(), TimeZone.getDefault());
            case Constants.ALBUM_ORDER_BY_RECENTLY_PLAYED:
                return ScrubberSections.months(rows.size(), i -> rows.get(i).getPlayed(), Locale.getDefault(), TimeZone.getDefault());
            default:
                return null;
        }
    }

    public void sort(String order) {
        if (albums == null) return;
        sortOrder = order;

        // Sort the unfiltered copy too, so the search filter keeps this order (and the A–Z index stays valid).
        albumsFull = new ArrayList<>(albumsFull);
        sortList(albumsFull, order);
        sortList(albums, order);

        notifyDataSetChanged();
    }

    /**
     * {@link #setItemsWithoutFilter} followed by {@link #sort}, with one sort and one refresh
     * instead of two of each. Called for every page of albums that arrives.
     */
    public void setItemsSorted(List<AlbumID3> albums, String order) {
        List<AlbumID3> sorted = new ArrayList<>(albums);
        if (order != null) {
            sortOrder = order;
            sortList(sorted, order);
        }
        this.albumsFull = sorted;
        this.albums = new ArrayList<>(sorted);
        notifyDataSetChanged();
    }

    private static void sortList(List<AlbumID3> albums, String order) {
        switch (order) {
            case Constants.ALBUM_ORDER_BY_NAME:
                AlphabetSort.sort(albums, AlbumID3::getName, null);
                break;
            case Constants.ALBUM_ORDER_BY_ARTIST:
                AlphabetSort.sort(albums, AlbumID3::getArtist, Comparator.comparing(AlbumID3::getYear));
                break;
            case Constants.ALBUM_ORDER_BY_YEAR:
                albums.sort(Comparator.comparing(AlbumID3::getYear));
                break;
            case Constants.ALBUM_ORDER_BY_RANDOM:
                Collections.shuffle(albums);
                break;
            case Constants.ALBUM_ORDER_BY_RECENTLY_ADDED:
                albums.sort(Comparator.comparing(
                    album -> album.getCreated() != null ? album.getCreated() : new Date(0),
                    Comparator.nullsLast(Date::compareTo)
                ));
                Collections.reverse(albums);
                break;
            case Constants.ALBUM_ORDER_BY_RECENTLY_PLAYED:
                albums.sort(Comparator.comparing(
                    album -> album.getPlayed() != null ? album.getPlayed() : new Date(0),
                    Comparator.nullsLast(Date::compareTo)
                ));
                Collections.reverse(albums);
                break;
            case Constants.ALBUM_ORDER_BY_MOST_PLAYED:
                albums.sort(Comparator.comparing(
                    album -> album.getPlayCount() != null ? album.getPlayCount() : 0L
                ));
                Collections.reverse(albums);
                break;
        }
    }
}
