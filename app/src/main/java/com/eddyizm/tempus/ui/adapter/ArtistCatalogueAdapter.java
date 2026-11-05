package com.eddyizm.tempus.ui.adapter;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.databinding.ItemLibraryCatalogueArtistBinding;
import com.eddyizm.tempus.glide.CustomGlideRequest;
import com.eddyizm.tempus.helper.recyclerview.AlphabetScrubber;
import com.eddyizm.tempus.helper.recyclerview.ScrubberSections;
import com.eddyizm.tempus.interfaces.ClickCallback;
import com.eddyizm.tempus.subsonic.models.ArtistID3;
import com.eddyizm.tempus.util.AlphabetSort;
import com.eddyizm.tempus.util.CatalogueView;
import com.eddyizm.tempus.util.Constants;
import com.eddyizm.tempus.util.MusicUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ArtistCatalogueAdapter extends RecyclerView.Adapter<ArtistCatalogueAdapter.ViewHolder> implements Filterable, StandardViewTypeAdapter, AlphabetScrubber.SectionProvider {
    private final ClickCallback click;
    private String view = CatalogueView.ART_TITLE_MEDIUM;
    private String sortOrder;

    private final Filter filtering = new Filter() {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            List<ArtistID3> filteredList = new ArrayList<>();

            if (constraint == null || constraint.length() == 0) {
                filteredList.addAll(artistFull);
            } else {
                String filterPattern = constraint.toString().toLowerCase().trim();

                for (ArtistID3 item : artistFull) {
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
            artists.clear();
            if (results.count > 0) artists.addAll((List) results.values);
            notifyDataSetChanged();
        }
    };

    private List<ArtistID3> artists;
    private List<ArtistID3> artistFull;

    public ArtistCatalogueAdapter(ClickCallback click) {
        this.click = click;
        this.artists = Collections.emptyList();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        ItemLibraryCatalogueArtistBinding item = CatalogueView.isRows(view)
                ? ItemLibraryCatalogueArtistBinding.bind(inflater.inflate(R.layout.item_library_catalogue_artist_row, parent, false))
                : ItemLibraryCatalogueArtistBinding.inflate(inflater, parent, false);
        if (CatalogueView.isRows(view)) {
            int size = Math.round(CatalogueView.rowCoverDp(view) * parent.getResources().getDisplayMetrics().density);
            ViewGroup.LayoutParams lp = item.artistCatalogueCoverImageView.getLayoutParams();
            lp.width = size;
            lp.height = size;
        }
        if (!CatalogueView.showsTitles(view)) {
            item.artistNameLabel.setVisibility(View.GONE);
            item.getRoot().setPadding(0, 0, 0, item.getRoot().getResources().getDimensionPixelSize(R.dimen.catalogue_full_art_gap));
        }
        return new ViewHolder(item);
    }

    /** Set before the adapter is attached: rows and tiles use different layouts. */
    public void setView(String view) {
        this.view = view;
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        ArtistID3 artist = artists.get(position);

        holder.item.artistNameLabel.setText(artist.getName());

        CustomGlideRequest.Builder
                .from(holder.itemView.getContext(), artist.getCoverArtId(), CustomGlideRequest.ResourceType.Artist)
                .build()
                .into(holder.item.artistCatalogueCoverImageView);
    }

    @Override
    public int getItemCount() {
        return artists.size();
    }

    public ArtistID3 getItem(int position) {
        return artists.get(position);
    }

    public void setItems(List<ArtistID3> artists) {
        this.artists = artists;
        this.artistFull = new ArrayList<>(artists);
        notifyDataSetChanged();
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
        ItemLibraryCatalogueArtistBinding item;

        ViewHolder(ItemLibraryCatalogueArtistBinding item) {
            super(item.getRoot());

            this.item = item;

            itemView.setOnClickListener(v -> onClick());
            itemView.setOnLongClickListener(v -> onLongClick());
        }

        public void onClick() {
            int position = getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return;
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.ARTIST_OBJECT, artists.get(position));

            click.onArtistClick(bundle);
        }

        public boolean onLongClick() {
            int position = getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return false;
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.ARTIST_OBJECT, artists.get(position));

            click.onArtistLongClick(bundle);

            return true;
        }
    }

    @Override
    public ScrubberSections buildSections() {
        if (!Constants.ARTIST_ORDER_BY_NAME.equals(sortOrder)) return null;
        List<ArtistID3> rows = artists;
        return ScrubberSections.letters(rows.size(), i -> rows.get(i).getName());
    }

    public void sort(String order) {
        sortOrder = order;
        switch (order) {
            case Constants.ARTIST_ORDER_BY_NAME:
                AlphabetSort.sort(artists, ArtistID3::getName, null);
                break;
            case Constants.ARTIST_ORDER_BY_RANDOM:
                Collections.shuffle(artists);
                break;
            case Constants.ARTIST_ORDER_BY_ALBUM_COUNT:
                artists.sort(Comparator.comparing(ArtistID3::getAlbumCount).reversed());
                break;
        }

        notifyDataSetChanged();
    }
}
