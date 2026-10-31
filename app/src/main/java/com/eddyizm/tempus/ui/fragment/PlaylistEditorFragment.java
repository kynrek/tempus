package com.eddyizm.tempus.ui.fragment;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.navigation.fragment.NavHostFragment;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.databinding.FragmentPlaylistEditorBinding;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.subsonic.models.Playlist;
import com.eddyizm.tempus.ui.activity.MainActivity;
import com.eddyizm.tempus.ui.playlist.PlaylistEditorBridge;
import com.eddyizm.tempus.util.Constants;
import com.eddyizm.tempus.viewmodel.PlaylistEditorViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

/**
 * Renames, reorders and removes the tracks of one playlist in a full-panel Compose screen laid out
 * like the queue, then sends the whole list on Save through PlaylistEditorViewModel.saveTracks, a
 * rename followed by a full replace, since Subsonic has no reorder call. Leaving with unsaved
 * changes asks whether to save or discard them.
 * <p>
 * The view model belongs to this fragment, not the activity, so the edited list survives rotation
 * and is dropped with the screen or on process death. The screen's rows and the view model's list
 * are edited together, so nothing is posted back through LiveData.
 */
@UnstableApi
public class PlaylistEditorFragment extends Fragment implements PlaylistEditorBridge.Listener {
    /** Set on the parent fragment manager after a save, with the playlist's id and name. */
    public static final String RESULT_SAVED = "playlist_editor_saved";

    private FragmentPlaylistEditorBinding bind;
    private PlaylistEditorViewModel playlistEditorViewModel;
    private PlaylistEditorBridge editor;
    private Snackbar undoSnackbar;
    private AlertDialog openDialog;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        bind = FragmentPlaylistEditorBinding.inflate(inflater, container, false);
        playlistEditorViewModel = new ViewModelProvider(this).get(PlaylistEditorViewModel.class);

        Bundle args = getArguments();
        Playlist playlist = args != null ? args.getParcelable(Constants.PLAYLIST_OBJECT) : null;
        if (playlist == null) {
            NavHostFragment.findNavController(this).navigateUp();
            return bind.getRoot();
        }

        // Only on the first creation. A rotation recreates the view and keeps the edited list.
        if (playlistEditorViewModel.getPlaylistToEdit() == null) {
            playlistEditorViewModel.setSongsToAdd(null);
            playlistEditorViewModel.setPlaylistToEdit(playlist);
        }

        editor = new PlaylistEditorBridge(bind.playlistEditorComposeView, this);
        editor.init(playlistEditorViewModel.getEditedName());
        editor.setSaving(playlistEditorViewModel.isSavePending());

        initBackPressed();
        initSongsView();
        observeSaveResult();

        return bind.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // The snackbar is shown on the activity's view, not on this screen's own, and its action
        // reaches the editor that is nulled just below.
        if (undoSnackbar != null) undoSnackbar.dismiss();
        undoSnackbar = null;
        if (openDialog != null) openDialog.dismiss();
        openDialog = null;
        bind = null;
        editor = null;
    }

    private void initBackPressed() {
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // The player, expanded over this screen, takes the back press first.
                MainActivity activity = (MainActivity) requireActivity();
                if (activity.isBottomSheetExpanded()) {
                    setEnabled(false);
                    activity.getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                    return;
                }
                onBack();
            }
        });
    }

    /**
     * Observed with the view lifecycle, so a result that lands while the app is in the background
     * is delivered when the screen is back on top. Navigating up from a callback in that window is
     * dropped by the navigation library, which refuses to pop once the fragment manager has saved
     * its state.
     */
    private void observeSaveResult() {
        playlistEditorViewModel.getSaveResult().observe(getViewLifecycleOwner(), message -> {
            if (message == null) return;
            playlistEditorViewModel.clearSaveResult();
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            playlistEditorViewModel.clearSavePending();
            editor.setSaving(false);
            if (message == R.string.playlist_editor_dialog_action_save_success) {
                // The page refetches its tracks on its own, the name it learns from here.
                Playlist playlist = playlistEditorViewModel.getPlaylistToEdit();
                Bundle result = new Bundle();
                result.putString(Constants.PLAYLIST_ID, playlist.getId());
                result.putString(Constants.PLAYLIST_NAME, playlist.getName());
                getParentFragmentManager().setFragmentResult(RESULT_SAVED, result);
                NavHostFragment.findNavController(this).navigateUp();
            }
        });
    }

    private void initSongsView() {
        playlistEditorViewModel.getPlaylistSongLiveList().observe(getViewLifecycleOwner(), songs -> {
            if (songs != null) {
                editor.setSongs(songs);
            } else {
                // With the allowCache false the view model passes, the repository publishes null
                // when the fetch fails or the playlist is gone, so there is nothing to edit and a
                // blank list would read as an empty playlist.
                Toast.makeText(requireContext(), R.string.playlist_editor_load_failure, Toast.LENGTH_SHORT).show();
                NavHostFragment.findNavController(this).navigateUp();
            }
        });
    }

    /** Leaves at once when there is nothing to lose. A save already sent carries on without the screen. */
    @Override
    public void onBack() {
        if (bind == null) return;

        if (playlistEditorViewModel.isSavePending() || !playlistEditorViewModel.hasUnsavedChanges()) {
            NavHostFragment.findNavController(this).navigateUp();
            return;
        }

        if (openDialog != null && openDialog.isShowing()) return;
        openDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.playlist_editor_unsaved_title)
                .setMessage(getString(R.string.playlist_editor_unsaved_message, playlistEditorViewModel.getEditedName()))
                .setPositiveButton(R.string.playlist_editor_dialog_positive_button, (dialog, which) -> onSave())
                .setNegativeButton(R.string.playlist_editor_discard, (dialog, which) -> {
                    if (bind != null) NavHostFragment.findNavController(this).navigateUp();
                })
                .setNeutralButton(R.string.playlist_editor_keep_editing, null)
                .show();
    }

    @Override
    public void onSave() {
        if (bind == null || playlistEditorViewModel.isSavePending()) return;

        if (undoSnackbar != null) undoSnackbar.dismiss();
        editor.setSaving(true);
        playlistEditorViewModel.saveTracks();
    }

    /** The new name is kept with the other edits and goes out with the next save. */
    @Override
    public void onRename() {
        if (bind == null || playlistEditorViewModel.isSavePending()) return;
        if (openDialog != null && openDialog.isShowing()) return;

        Context context = requireContext();
        EditText name = new EditText(context);
        name.setSingleLine();
        name.setHint(R.string.playlist_editor_dialog_hint_name);
        name.setText(playlistEditorViewModel.getEditedName());
        name.setSelectAllOnFocus(true);
        int padding = (int) (20 * context.getResources().getDisplayMetrics().density);
        FrameLayout frame = new FrameLayout(context);
        frame.setPadding(padding, padding / 2, padding, 0);
        frame.addView(name);

        openDialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.playlist_editor_rename)
                .setView(frame)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String newName = name.getText().toString().trim();
                    if (newName.isEmpty() || editor == null) return;
                    playlistEditorViewModel.setEditedName(newName);
                    editor.setName(newName);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        if (openDialog.getWindow() != null) {
            openDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        openDialog.show();
        name.requestFocus();
    }

    @Override
    public void onMove(int from, int to) {
        playlistEditorViewModel.moveInPlaylistSongLiveList(from, to);
    }

    @Override
    public void onRemove(int position) {
        Child removed = playlistEditorViewModel.removeFromPlaylistSongLiveList(position);

        MainActivity activity = (MainActivity) requireActivity();
        if (activity.bind == null) return;

        boolean[] undone = {false};
        undoSnackbar = Snackbar.make(activity.bind.playerBottomSheet, R.string.playlist_editor_removed_track, Snackbar.LENGTH_LONG)
                .setAnchorView(activity.getSnackbarAnchor())
                .setAction(R.string.song_bottom_sheet_undo, v -> {
                    if (editor == null || undone[0] || playlistEditorViewModel.isSavePending()) return;
                    undone[0] = true;

                    int at = playlistEditorViewModel.restoreToPlaylistSongLiveList(position, removed);
                    if (at >= 0) editor.restore(at, removed);
                });
        undoSnackbar.show();
    }

    @Override
    public boolean hasUnsavedChanges() {
        return playlistEditorViewModel.hasUnsavedChanges();
    }
}
