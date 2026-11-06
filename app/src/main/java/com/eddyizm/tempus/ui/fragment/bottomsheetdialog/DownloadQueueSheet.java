package com.eddyizm.tempus.ui.fragment.bottomsheetdialog;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.databinding.BottomSheetDownloadQueueBinding;
import com.eddyizm.tempus.databinding.ItemDownloadQueueBinding;
import com.eddyizm.tempus.service.DownloadProgress;
import com.eddyizm.tempus.service.DownloaderManager;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.util.ExternalAudioWriter;
import com.eddyizm.tempus.util.ProgressCircles;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The download queue, apart from the play queue: every song waiting or downloading, with how far
 * along it is. Check songs (or Select all) and Cancel, or swipe one sideways to cancel it.
 */
@UnstableApi
public class DownloadQueueSheet extends CarBottomSheetDialogFragment {
    private static final String TAG = "DownloadQueueSheet";

    private BottomSheetDownloadQueueBinding bind;
    private final Set<String> checked = new HashSet<>();
    private final RowAdapter adapter = new RowAdapter();
    private List<Row> rows = new ArrayList<>();

    /** From a view's context (a list row's, say), which may wrap the activity. */
    public static void show(Context context) {
        while (context instanceof android.content.ContextWrapper && !(context instanceof androidx.fragment.app.FragmentActivity)) {
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        if (context instanceof androidx.fragment.app.FragmentActivity) {
            show(((androidx.fragment.app.FragmentActivity) context).getSupportFragmentManager());
        }
    }

    public static void show(FragmentManager fragmentManager) {
        if (fragmentManager.findFragmentByTag(TAG) == null) new DownloadQueueSheet().show(fragmentManager, TAG);
    }

    /** A song as listed: a snapshot, so the list can tell what changed. */
    private static final class Row {
        final String id;
        final String title;
        final String detail;
        final float fraction;
        final boolean toFolder;

        Row(DownloadProgress.Item item) {
            Child song = item.song;
            id = song.getId();
            title = song.getTitle() != null ? song.getTitle() : song.getId();
            List<String> parts = new ArrayList<>();
            if (song.getArtist() != null) parts.add(song.getArtist());
            if (song.getAlbum() != null) parts.add(song.getAlbum());
            detail = String.join(" · ", parts);
            fraction = item.fraction;
            toFolder = item.toFolder;
        }

        boolean sameAs(Row other) {
            return fraction == other.fraction && Objects.equals(title, other.title) && Objects.equals(detail, other.detail);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        bind = BottomSheetDownloadQueueBinding.inflate(inflater, container, false);
        bind.downloadQueueList.setLayoutManager(new LinearLayoutManager(requireContext()));
        bind.downloadQueueList.setAdapter(adapter);

        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                int pos = vh.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || pos >= adapter.getCurrentList().size()) return;
                cancel(List.of(adapter.getCurrentList().get(pos)));
            }
        }).attachToRecyclerView(bind.downloadQueueList);

        bind.downloadQueueSelectAll.setOnClickListener(v -> {
            checked.clear();
            if (bind.downloadQueueSelectAll.isChecked()) for (Row row : rows) checked.add(row.id);
            adapter.notifyItemRangeChanged(0, adapter.getItemCount());
            renderActions();
        });
        bind.downloadQueueCancel.setOnClickListener(v -> {
            List<Row> picked = new ArrayList<>();
            for (Row row : rows) if (checked.contains(row.id)) picked.add(row);
            cancel(picked);
        });

        DownloadProgress.changes().observe(getViewLifecycleOwner(), v -> refresh());
        return bind.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void refresh() {
        if (bind == null) return;
        rows = new ArrayList<>();
        for (DownloadProgress.Item item : DownloadProgress.queue()) rows.add(new Row(item));
        Set<String> present = new HashSet<>();
        for (Row row : rows) present.add(row.id);
        checked.retainAll(present);
        adapter.submitList(rows);
        bind.downloadQueueTitle.setText(rows.isEmpty()
                ? getString(R.string.download_queue_title)
                : getString(R.string.download_queue_title_count, rows.size()));
        bind.downloadQueueEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        bind.downloadQueueActions.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        bind.downloadQueueHint.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        renderActions();
    }

    private void renderActions() {
        if (bind == null) return;
        bind.downloadQueueSelectAll.setChecked(!rows.isEmpty() && checked.size() == rows.size());
        bind.downloadQueueCancel.setEnabled(!checked.isEmpty());
        bind.downloadQueueCancel.setText(checked.isEmpty()
                ? getString(R.string.download_queue_cancel)
                : getString(R.string.download_queue_cancel_count, checked.size()));
    }

    private void cancel(Collection<Row> picked) {
        if (picked.isEmpty()) return;
        Context context = requireContext().getApplicationContext();
        for (Row row : picked) {
            if (row.toFolder) ExternalAudioWriter.cancel(row.id);
            else DownloaderManager.cancel(context, row.id);
            checked.remove(row.id);
        }
        Toast.makeText(context, context.getResources().getQuantityString(
                R.plurals.download_queue_cancelled, picked.size(), picked.size()), Toast.LENGTH_SHORT).show();
        refresh();
    }

    private final class RowAdapter extends ListAdapter<Row, RowAdapter.Holder> {
        RowAdapter() {
            super(new DiffUtil.ItemCallback<Row>() {
                @Override
                public boolean areItemsTheSame(@NonNull Row a, @NonNull Row b) {
                    return a.id.equals(b.id);
                }

                @Override
                public boolean areContentsTheSame(@NonNull Row a, @NonNull Row b) {
                    return a.sameAs(b);
                }
            });
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemDownloadQueueBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Row row = getItem(position);
            holder.item.downloadQueueSong.setText(row.title);
            holder.item.downloadQueueDetail.setText(row.detail);
            holder.item.downloadQueueDetail.setVisibility(row.detail.isEmpty() ? View.GONE : View.VISIBLE);
            holder.item.downloadQueueCheck.setChecked(checked.contains(row.id));
            holder.item.downloadQueuePercent.setText(row.fraction >= 0
                    ? Math.round(row.fraction * 100) + "%"
                    : getString(R.string.download_queue_waiting));
            ProgressCircles.show(holder.item.downloadQueueProgress, row.fraction);
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ItemDownloadQueueBinding item;

            Holder(ItemDownloadQueueBinding item) {
                super(item.getRoot());
                this.item = item;
                View.OnClickListener toggle = v -> {
                    int pos = getBindingAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) return;
                    String id = getItem(pos).id;
                    if (!checked.remove(id)) checked.add(id);
                    item.downloadQueueCheck.setChecked(checked.contains(id));
                    renderActions();
                };
                item.downloadQueueCheck.setOnClickListener(toggle);
                item.getRoot().setOnClickListener(v -> item.downloadQueueCheck.performClick());
            }
        }
    }
}
