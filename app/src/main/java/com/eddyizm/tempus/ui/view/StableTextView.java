package com.eddyizm.tempus.ui.view;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;

import androidx.annotation.Nullable;

import com.google.android.material.textview.MaterialTextView;

/**
 * A label that ignores being given the text it already shows. Media3's PlayerControlView sets the
 * position text once a second even while paused, and every setText asks for a layout pass of the
 * whole player, which is a frame of work for nothing. Used for the player's time labels.
 */
public class StableTextView extends MaterialTextView {
    private final boolean ready;   // false while TextView's own constructor sets the initial text

    public StableTextView(Context context) {
        this(context, null);
    }

    public StableTextView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, android.R.attr.textViewStyle);
    }

    public StableTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        ready = true;
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        if (ready && type == BufferType.NORMAL && TextUtils.equals(text, getText())) return;
        super.setText(text, type);
    }
}
