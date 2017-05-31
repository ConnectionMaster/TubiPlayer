package com.tubitv.media.views;

import android.content.Context;
import android.content.res.TypedArray;
import android.databinding.DataBindingUtil;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.SeekBar;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.tubitv.media.R;
import com.tubitv.media.databinding.ViewTubiPlayerControlBinding;
import com.tubitv.media.interfaces.TubiPlaybackControlInterface;

import java.util.Formatter;
import java.util.Locale;

import static com.google.android.exoplayer2.ExoPlayer.STATE_ENDED;
import static com.google.android.exoplayer2.ExoPlayer.STATE_READY;

/**
 * Created by stoyan tubi_tv_quality_on 3/23/17.
 */
public class TubiPlayerControlViewOld extends FrameLayout {
    /**
     * Simple tag for logging
     */
    private static final String TAG = TubiPlayerControlViewOld.class.getSimpleName();
    private TubiPlaybackControlInterface mTubiControllerInterface;

    /**
     * Listener to be notified about changes of the visibility of the UI control.
     */
    public interface VisibilityListener {

        /**
         * Called when the visibility changes.
         *
         * @param visibility The new visibility. Either {@link View#VISIBLE} or {@link View#GONE}.
         */
        void onVisibilityChange(int visibility);

    }

    /**
     * Dispatches seek operations to the player.
     */
    public interface SeekDispatcher {

        /**
         * @param player      The player to seek.
         * @param windowIndex The index of the window.
         * @param positionMs  The seek position in the specified window, or {@link C#TIME_UNSET} to seek
         *                    to the window's default position.
         * @return True if the seek was dispatched. False otherwise.
         */
        boolean dispatchSeek(ExoPlayer player, int windowIndex, long positionMs);

    }

    /**
     * Default {@link SeekDispatcher} that dispatches seeks to the player without modification.
     */
    public static final SeekDispatcher DEFAULT_SEEK_DISPATCHER = new SeekDispatcher() {

        @Override
        public boolean dispatchSeek(ExoPlayer player, int windowIndex, long positionMs) {
            player.seekTo(windowIndex, positionMs);
            return true;
        }

    };
    public static final int DEFAULT_SHOW_TIMEOUT_MS = 5000;
    private static final int PROGRESS_BAR_MAX = 1000;
    private static final int DEFAULT_FAST_FORWARD_MS = 15000;

    /**
     * The time in milliseconds that we skip by in {@link com.tubitv.media.databinding.ViewTubiPlayerControlBinding#viewTubiControllerRewindIb}
     * and {@link com.tubitv.media.databinding.ViewTubiPlayerControlBinding#viewTubiControllerForwardIb}
     */
    public int mSkipBy = DEFAULT_FAST_FORWARD_MS;

    private ViewTubiPlayerControlBinding mBinding;

    private final ComponentListener componentListener;


    private final StringBuilder formatBuilder;
    private final Formatter formatter;
    private final Timeline.Window currentWindow;

    private ExoPlayer player;
    private SeekDispatcher seekDispatcher;
    private VisibilityListener visibilityListener;

    private boolean isAttachedToWindow;
    private boolean dragging;
    private int showTimeoutMs;
    private long hideAtMs;
    private final Runnable updateProgressAction = new Runnable() {
        @Override
        public void run() {
            updateProgress();
        }
    };

    private final Runnable hideAction = new Runnable() {
        @Override
        public void run() {
            hide();
        }
    };

    public TubiPlayerControlViewOld(Context context) {
        this(context, null);
    }

    public TubiPlayerControlViewOld(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TubiPlayerControlViewOld(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        int controllerLayoutId = R.layout.view_tubi_player_control;
        showTimeoutMs = DEFAULT_SHOW_TIMEOUT_MS;
        if (attrs != null) {
            TypedArray a = context.getTheme().obtainStyledAttributes(attrs,
                    R.styleable.TubiPlayerControlViewOld, 0, 0);
            try {
                showTimeoutMs = a.getInt(R.styleable.TubiPlayerControlViewOld_show_timeout_ms, showTimeoutMs);
            } finally {
                a.recycle();
            }
        }
        currentWindow = new Timeline.Window();
        formatBuilder = new StringBuilder();
        formatter = new Formatter(formatBuilder, Locale.getDefault());
        componentListener = new ComponentListener();
        seekDispatcher = DEFAULT_SEEK_DISPATCHER;


        initLayout(controllerLayoutId);
    }

    private void initLayout(int controllerLayoutId) {
        LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mBinding = DataBindingUtil.inflate(inflater, controllerLayoutId, this, true);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        mBinding.setController(new TubiPlayerControlView(getContext()));
        mBinding.viewTubiControllerSeekBar.setOnSeekBarChangeListener(componentListener);
        mBinding.viewTubiControllerSeekBar.setMax(PROGRESS_BAR_MAX);
//        mBinding.viewTubiControllerPlayToggleIb.addClickListener(componentListener);
    }

    public void setTubiControllerInterface(@NonNull TubiExoPlayerView tubiControllerInterface) {
        this.mTubiControllerInterface = tubiControllerInterface;
    }

    /**
     * Returns the player currently being controlled by this view, or null if no player is set.
     */
    public ExoPlayer getPlayer() {
        return player;
    }

    /**
     * Sets the {@link ExoPlayer} to control.
     *
     * @param player the {@code ExoPlayer} to control.
     */
    public void setPlayer(ExoPlayer player) {
        if (this.player == player) {
            return;
        }
        if (this.player != null) {
            this.player.removeListener(componentListener);
        }
        this.player = player;
        if (player != null) {
            player.addListener(componentListener);
        }
        updateAll();
    }

    /**
     * Sets the {@link VisibilityListener}.
     *
     * @param listener The listener to be notified about visibility changes.
     */
    public void setVisibilityListener(VisibilityListener listener) {
        this.visibilityListener = listener;
    }

    /**
     * Sets the {@link SeekDispatcher}.
     *
     * @param seekDispatcher The {@link SeekDispatcher}, or null to use
     *                       {@link #DEFAULT_SEEK_DISPATCHER}.
     */
    public void setSeekDispatcher(SeekDispatcher seekDispatcher) {
        this.seekDispatcher = seekDispatcher == null ? DEFAULT_SEEK_DISPATCHER : seekDispatcher;
    }

    /**
     * Returns the playback controls timeout. The playback controls are automatically hidden after
     * this duration of time has elapsed without user input.
     *
     * @return The duration in milliseconds. A non-positive value indicates that the controls will
     * remain visible indefinitely.
     */
    public int getShowTimeoutMs() {
        return showTimeoutMs;
    }

    /**
     * Sets the playback controls timeout. The playback controls are automatically hidden after this
     * duration of time has elapsed without user input.
     *
     * @param showTimeoutMs The duration in milliseconds. A non-positive value will cause the controls
     *                      to remain visible indefinitely.
     */
    public void setShowTimeoutMs(int showTimeoutMs) {
        this.showTimeoutMs = showTimeoutMs;
    }

    /**
     * Shows the playback controls. If {@link #getShowTimeoutMs()} is positive then the controls will
     * be automatically hidden after this duration of time has elapsed without user input.
     */
    public void show() {
        if (!isVisible()) {
            setVisibility(VISIBLE);
            if (visibilityListener != null) {
                visibilityListener.onVisibilityChange(getVisibility());
            }
            updateAll();
        }
        // Call hideAfterTimeout even if already visible to reset the timeout.
        hideAfterTimeout();
    }

    /**
     * Hides the controller.
     */
    public void hide() {
        if (isVisible()) {
            setVisibility(GONE);
            if (visibilityListener != null) {
                visibilityListener.onVisibilityChange(getVisibility());
            }
            removeCallbacks(updateProgressAction);
            removeCallbacks(hideAction);
            hideAtMs = C.TIME_UNSET;
        }
    }

    /**
     * Returns whether the controller is currently visible.
     */
    public boolean isVisible() {
        return getVisibility() == VISIBLE;
    }

    private void hideAfterTimeout() {
        removeCallbacks(hideAction);
        if (showTimeoutMs > 0) {
            hideAtMs = SystemClock.uptimeMillis() + showTimeoutMs;
            if (isAttachedToWindow) {
                postDelayed(hideAction, showTimeoutMs);
            }
        } else {
            hideAtMs = C.TIME_UNSET;
        }
    }

    private void updateAll() {
        onPlaybackState();
        updateNavigation();
        updateProgress();
    }

    /**
     * Updates the playback controlls when the player state changes.
     * ie. The play/pause and loading spinner
     */
    public void onPlaybackState() {
        if (!isVisible() || !isAttachedToWindow) {
            return;
        }

        boolean playing = player != null && player.getPlayWhenReady();
        int playbackState = player == null ? ExoPlayer.STATE_IDLE : player.getPlaybackState();
        switch (playbackState) {
            case ExoPlayer.STATE_READY:
                showLoading(true, playing);
                break;
            case ExoPlayer.STATE_BUFFERING:
                showLoading(false, false);
                break;
            case ExoPlayer.STATE_IDLE:  //nothing to play
            case ExoPlayer.STATE_ENDED: //stream ended
                break;
        }
    }

    /**
     * Toggles the views in this control when the player is
     *
     * @param isLoaded
     * @param isPlaying
     */
    private void showLoading(boolean isLoaded, boolean isPlaying) {
        int vis = isLoaded ? View.VISIBLE : View.INVISIBLE;
        mBinding.viewTubiControllerPlayToggleIb.setVisibility(vis);

        if (isLoaded) {
            mBinding.viewTubiControllerLoading.stop();
        } else {
            mBinding.viewTubiControllerLoading.start();
        }

        mBinding.viewTubiControllerPlayToggleIb.setChecked(isPlaying);
    }

    private void updateNavigation() {
        if (!isVisible() || !isAttachedToWindow) {
            return;
        }
        Timeline currentTimeline = player != null ? player.getCurrentTimeline() : null;
        boolean haveNonEmptyTimeline = currentTimeline != null && !currentTimeline.isEmpty();
        boolean isSeekable = false;
        if (haveNonEmptyTimeline) {
            int currentWindowIndex = player.getCurrentWindowIndex();
            currentTimeline.getWindow(currentWindowIndex, currentWindow);
            isSeekable = currentWindow.isSeekable;
        }
        if (mBinding.viewTubiControllerSeekBar != null) {
            mBinding.viewTubiControllerSeekBar.setEnabled(isSeekable);
        }
    }

    private void updateProgress() {
        if (!isVisible() || !isAttachedToWindow) {
            return;
        }
        long position = player == null ? 0 : player.getCurrentPosition();
        long duration = player == null ? 0 : player.getDuration();
        if (!dragging) {
            setProgressTime(position, duration);
        }

        if (mBinding.viewTubiControllerSeekBar != null) {
            if (!dragging) {
                mBinding.viewTubiControllerSeekBar.setProgress(progressBarValue(position));
            }
            long bufferedPosition = player == null ? 0 : player.getBufferedPosition();
            mBinding.viewTubiControllerSeekBar.setSecondaryProgress(progressBarValue(bufferedPosition));
        }
        removeCallbacks(updateProgressAction);
        // Schedule an update if necessary.
        int playbackState = player == null ? ExoPlayer.STATE_IDLE : player.getPlaybackState();
        if (playbackState != ExoPlayer.STATE_IDLE && playbackState != STATE_ENDED) {
            long delayMs;
            if (player.getPlayWhenReady() && playbackState == STATE_READY) {
                delayMs = 1000 - (position % 1000);
                if (delayMs < 200) {
                    delayMs += 1000;
                }
            } else {
                delayMs = 1000;
            }
            postDelayed(updateProgressAction, delayMs);
        }
    }

    private void setProgressTime(long position, long duration) {
        if (mBinding.viewTubiControllerElapsedTime != null) {
            mBinding.viewTubiControllerElapsedTime.setText(toProgressTime(position, false));
        }

        if (mBinding.viewTubiControllerRemainingTime != null) {
            mBinding.viewTubiControllerRemainingTime.setText(toProgressTime(duration - position, true));
        }
    }

    private String toProgressTime(long timeMs, boolean remaining) {
        if (timeMs == C.TIME_UNSET) {
            timeMs = 0;
        }
        long totalSeconds = (timeMs + 500) / 1000;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;
        formatBuilder.setLength(0);
        String time = hours > 0 ? formatter.format("%d:%02d:%02d", hours, minutes, seconds).toString()
                : formatter.format("%02d:%02d", minutes, seconds).toString();
        return remaining && timeMs != 0 ? "-" + time : time;
    }

    private int progressBarValue(long position) {
        long duration = player == null ? C.TIME_UNSET : player.getDuration();
        return duration == C.TIME_UNSET || duration == 0 ? 0
                : (int) ((position * PROGRESS_BAR_MAX) / duration);
    }

    private long positionValue(int progress) {
        long duration = player == null ? C.TIME_UNSET : player.getDuration();
        return duration == C.TIME_UNSET ? 0 : ((duration * progress) / PROGRESS_BAR_MAX);
    }

    public void seekBy(long timeMillis) {
        long position = player.getCurrentPosition();
        long place = position + timeMillis;
        //lower bound
        place = place < 0 ? 0 : place;
        //upper bound
        place = place > player.getDuration() ? player.getDuration() : place;
        seekTo(place);
    }

    private void seekTo(long positionMs) {
        seekTo(player.getCurrentWindowIndex(), positionMs);
    }

    private void seekTo(int windowIndex, long positionMs) {
        boolean dispatched = seekDispatcher.dispatchSeek(player, windowIndex, positionMs);
        if (!dispatched) {
            // The seek wasn't dispatched. If the progress bar was dragged by the user to perform the
            // seek then it'll now be in the wrong position. Trigger a progress update to snap it back.
            updateProgress();
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        isAttachedToWindow = true;
        if (hideAtMs != C.TIME_UNSET) {
            long delayMs = hideAtMs - SystemClock.uptimeMillis();
            if (delayMs <= 0) {
                hide();
            } else {
                postDelayed(hideAction, delayMs);
            }
        }
        updateAll();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        isAttachedToWindow = false;
        removeCallbacks(updateProgressAction);
        removeCallbacks(hideAction);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean handled = dispatchMediaKeyEvent(event) || super.dispatchKeyEvent(event);
        if (handled) {
            show();
        }
        return handled;
    }

    /**
     * Called to process media key events. Any {@link KeyEvent} can be passed but only media key
     * events will be handled.
     *
     * @param event A key event.
     * @return Whether the key event was handled.
     */
    public boolean dispatchMediaKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (player == null || !isHandledMediaKey(keyCode)) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    break;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    player.setPlayWhenReady(!player.getPlayWhenReady());
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    player.setPlayWhenReady(true);
                    break;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    player.setPlayWhenReady(false);
                    break;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    break;
                default:
                    break;
            }
        }
        show();
        return true;
    }

    private static boolean isHandledMediaKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    }


    public int getSkipBy() {
        return mSkipBy;
    }


    private final class ComponentListener implements ExoPlayer.EventListener,
            SeekBar.OnSeekBarChangeListener, View.OnClickListener {

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            removeCallbacks(hideAction);
            dragging = true;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser) {
                long position = positionValue(progress);
                long duration = player == null ? 0 : player.getDuration();
                setProgressTime(position, duration);
                if (player != null && !dragging) {
                    seekTo(position);
                }
            }
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            //Reset thumb, or smaller unpressed drawable will get blown up
            seekBar.setThumb(getResources().getDrawable(R.drawable.tubi_tv_drawable_scrubber_selector));
            dragging = false;
            if (player != null) {
                seekTo(positionValue(seekBar.getProgress()));
            }
            hideAfterTimeout();
        }

        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            onPlaybackState();
            updateProgress();
        }

        @Override
        public void onPositionDiscontinuity() {
            updateNavigation();
            onPlaybackState();
            updateProgress();
        }

        @Override
        public void onTimelineChanged(Timeline timeline, Object manifest) {
            updateNavigation();
            onPlaybackState();
            updateProgress();
        }

        @Override
        public void onLoadingChanged(boolean isLoading) {

        }

        @Override
        public void onTracksChanged(TrackGroupArray tracks, TrackSelectionArray selections) {
            // Do nothing.
        }

        @Override
        public void onPlayerError(ExoPlaybackException error) {
            // Do nothing.
        }

        @Override
        public void onClick(View view) {
            if (player != null) {
                boolean playing = player.getPlayWhenReady();
                player.setPlayWhenReady(!playing);
            }
            hideAfterTimeout();
        }

    }

}