package com.eddyizm.tempus.ui.fragment.bottomsheetdialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * On the 480 px car screen a half-open sheet shows little more than its title row, and the longer
 * menus (an album's) don't fit even fully open. These sheets open fully and scroll.
 */
public class CarBottomSheetDialogFragment extends BottomSheetDialogFragment {
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        return new ScrollingSheet(requireContext(), getTheme());
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetBehavior<?> behavior = ((BottomSheetDialog) getDialog()).getBehavior();
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            // Left to itself a sheet taller than the screen runs off the bottom instead of scrolling.
            View sheet = getDialog().findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null && sheet.getParent() instanceof View) {
                View parent = (View) sheet.getParent();
                parent.post(() -> {
                    if (parent.getHeight() > 0) behavior.setMaxHeight(parent.getHeight());
                });
            }
        }
    }

    private static class ScrollingSheet extends BottomSheetDialog {
        ScrollingSheet(@NonNull Context context, int theme) {
            super(context, theme);
        }

        @Override
        public void setContentView(@NonNull View view) {
            if (view instanceof NestedScrollView || view.getParent() != null) {
                super.setContentView(view);
                return;
            }
            NestedScrollView scroll = new NestedScrollView(getContext());
            scroll.setFillViewport(true);
            scroll.addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            super.setContentView(scroll);
        }
    }
}
