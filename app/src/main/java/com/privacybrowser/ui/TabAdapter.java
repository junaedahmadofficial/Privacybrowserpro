package com.privacybrowser.ui;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.privacybrowser.R;
import com.privacybrowser.browser.TabManager;
import com.privacybrowser.utils.Constants;
import com.privacybrowser.utils.UrlValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TabAdapter.java
 * RecyclerView adapter for the Tab Switcher UI.
 *
 * Architecture:
 * ┌─────────────────────────────────────────────┐
 * │              TabAdapter                     │
 * │                                             │
 * │  RecyclerView                               │
 * │  ┌───────────────────────────────────────┐  │
 * │  │  TabViewHolder (item_tab.xml)         │  │
 * │  │  ┌─────────────────────────────────┐  │  │
 * │  │  │  [Thumbnail / Screenshot]       │  │  │
 * │  │  │  [🔒 Title          ] [✕ Close] │  │  │
 * │  │  │  [URL short display ]           │  │  │
 * │  │  └─────────────────────────────────┘  │  │
 * │  └───────────────────────────────────────┘  │
 * │                                             │
 * │  Active tab → highlighted border            │
 * │  Private tab → purple tint                  │
 * │  Swipe to close → ItemTouchHelper           │
 * └─────────────────────────────────────────────┘
 *
 * Features:
 *  - Grid layout (2 columns)
 *  - Swipe-to-close (left/right swipe removes tab)
 *  - Tap to switch tab
 *  - Close button (✕) per tab
 *  - Active tab highlighted with blue border
 *  - Private tabs show purple border + icon
 *  - Thumbnail screenshot preview
 *  - Smooth open/close animations
 *  - DiffUtil for efficient updates
 */
public class TabAdapter
        extends RecyclerView.Adapter<TabAdapter.TabViewHolder> {

    private static final String TAG = "TabAdapter";

    // ─────────────────────────────────────────────
    // Data
    // ─────────────────────────────────────────────
    private final List<TabManager.Tab> tabs;
    private String activeTabId = "";
    private final Context context;

    // ─────────────────────────────────────────────
    // Listener
    // ─────────────────────────────────────────────
    private TabAdapterListener listener;

    /**
     * Callbacks for tab events from the switcher.
     */
    public interface TabAdapterListener {
        void onTabSelected(TabManager.Tab tab);
        void onTabClosed(TabManager.Tab tab, int position);
        void onNewTabRequested(boolean isPrivate);
    }

    // ═════════════════════════════════════════════
    // CONSTRUCTOR
    // ═════════════════════════════════════════════

    public TabAdapter(
            @NonNull Context context,
            @NonNull List<TabManager.Tab> initialTabs,
            @NonNull String activeTabId) {

        this.context     = context;
        this.tabs        = new ArrayList<>(initialTabs);
        this.activeTabId = activeTabId;

        // Stable IDs for efficient animations
        setHasStableIds(true);
    }

    // ═════════════════════════════════════════════
    // RECYCLER VIEW ADAPTER OVERRIDES
    // ═════════════════════════════════════════════

    @NonNull
    @Override
    public TabViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent, int viewType) {

        View itemView = LayoutInflater.from(context)
            .inflate(R.layout.item_tab, parent, false);

        return new TabViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(
            @NonNull TabViewHolder holder, int position) {

        TabManager.Tab tab = tabs.get(position);
        holder.bind(tab, tab.id.equals(activeTabId));
    }

    @Override
    public int getItemCount() {
        return tabs.size();
    }

    @Override
    public long getItemId(int position) {
        // Use hashCode of tab ID for stable ID
        return tabs.get(position).id.hashCode();
    }

    // ═════════════════════════════════════════════
    // VIEW HOLDER
    // ═════════════════════════════════════════════

    /**
     * ViewHolder for a single tab card.
     */
    class TabViewHolder extends RecyclerView.ViewHolder {

        // Views
        private final FrameLayout  cardContainer;
        private final ImageView    thumbnail;
        private final TextView     tvTitle;
        private final TextView     tvUrl;
        private final ImageButton  btnClose;
        private final ImageView    ivPrivateIcon;
        private final View         activeIndicator;
        private final LinearLayout tabInfoBar;
        private final ImageView    ivFavicon;

        // Animation state
        private boolean isAnimating = false;

        TabViewHolder(@NonNull View itemView) {
            super(itemView);

            cardContainer   = itemView.findViewById(R.id.tab_card_container);
            thumbnail       = itemView.findViewById(R.id.tab_thumbnail);
            tvTitle         = itemView.findViewById(R.id.tab_title);
            tvUrl           = itemView.findViewById(R.id.tab_url);
            btnClose        = itemView.findViewById(R.id.btn_close_tab);
            ivPrivateIcon   = itemView.findViewById(R.id.iv_private_icon);
            activeIndicator = itemView.findViewById(R.id.active_indicator);
            tabInfoBar      = itemView.findViewById(R.id.tab_info_bar);
            ivFavicon       = itemView.findViewById(R.id.iv_favicon);
        }

        /**
         * Binds a Tab to this ViewHolder.
         *
         * @param tab      Tab data to display
         * @param isActive Whether this is the currently active tab
         */
        void bind(TabManager.Tab tab, boolean isActive) {

            // ── Title ──
            tvTitle.setText(tab.getDisplayTitle());

            // ── URL ──
            String displayUrl = tab.isBlank()
                ? (tab.isPrivate ? "Private Tab" : "New Tab")
                : UrlValidator.toDisplayUrl(tab.url);
            tvUrl.setText(displayUrl);

            // ── Thumbnail ──
            bindThumbnail(tab);

            // ── Private Mode Styling ──
            if (tab.isPrivate) {
                ivPrivateIcon.setVisibility(View.VISIBLE);
                applyPrivateTabStyle();
            } else {
                ivPrivateIcon.setVisibility(View.GONE);
                applyNormalTabStyle();
            }

            // ── Active Tab Highlight ──
            if (isActive) {
                applyActiveStyle(tab.isPrivate);
            } else {
                applyInactiveStyle();
            }

            // ── Loading indicator ──
            if (tab.isLoading) {
                showLoadingState();
            } else if (tab.hasError) {
                showErrorState();
            } else {
                showNormalState();
            }

            // ── Click: Select tab ──
            itemView.setOnClickListener(v -> {
                if (listener != null && !isAnimating) {
                    animateTabSelect(v);
                    listener.onTabSelected(tab);
                }
            });

            // ── Close button ──
            btnClose.setOnClickListener(v -> {
                if (listener != null && !isAnimating) {
                    animateTabClose(itemView, () -> {
                        int pos = getAdapterPosition();
                        if (pos != RecyclerView.NO_ID && pos < tabs.size()) {
                            listener.onTabClosed(tab, pos);
                        }
                    });
                }
            });
        }

        /**
         * Binds thumbnail screenshot to the tab card.
         * Falls back to generated placeholder if no screenshot.
         */
        private void bindThumbnail(TabManager.Tab tab) {
            if (tab.thumbnail != null && !tab.thumbnail.isRecycled()) {
                thumbnail.setImageBitmap(tab.thumbnail);
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                // Generate placeholder with domain initial
                Bitmap placeholder = generatePlaceholder(tab);
                thumbnail.setImageBitmap(placeholder);
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            }
        }

        /**
         * Generates a placeholder thumbnail when no screenshot is available.
         * Shows site domain initial on colored background.
         *
         * Example: "google.com" → "G" on blue background
         */
        private Bitmap generatePlaceholder(TabManager.Tab tab) {
            int width  = Constants.TAB_THUMBNAIL_WIDTH;
            int height = Constants.TAB_THUMBNAIL_HEIGHT;

            Bitmap bitmap = Bitmap.createBitmap(
                width, height, Bitmap.Config.ARGB_8888
            );
            Canvas canvas = new Canvas(bitmap);

            // Background color based on tab type
            int bgColor = tab.isPrivate
                ? Color.parseColor("#1a0a2e")  // Dark purple
                : Color.parseColor("#1e2a3a"); // Dark blue-grey

            canvas.drawColor(bgColor);

            // Draw domain initial
            String host  = UrlValidator.extractHost(tab.url);
            String label = tab.isBlank()
                ? (tab.isPrivate ? "🕵" : "⊕")
                : (host.isEmpty() ? "?" : String.valueOf(
                    Character.toUpperCase(host.charAt(0))
                  ));

            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(64f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setFakeBoldText(true);

            float x = width / 2f;
            float y = height / 2f - (textPaint.descent()
                                    + textPaint.ascent()) / 2f;
            canvas.drawText(label, x, y, textPaint);

            return bitmap;
        }

        // ─────────────────────────────────────────
        // Visual Styling
        // ─────────────────────────────────────────

        /**
         * Active tab — highlighted border.
         */
        private void applyActiveStyle(boolean isPrivate) {
            if (cardContainer == null) return;

            int borderColor = isPrivate
                ? Color.parseColor("#7c4dff")  // Purple for private
                : Color.parseColor("#2979ff"); // Blue for normal

            GradientDrawableHelper.setBorder(
                cardContainer, borderColor, 3f
            );
            activeIndicator.setVisibility(View.VISIBLE);
            activeIndicator.setBackgroundColor(borderColor);

            // Slightly scale up active tab
            cardContainer.setScaleX(1.02f);
            cardContainer.setScaleY(1.02f);
        }

        /**
         * Inactive tab — subtle border.
         */
        private void applyInactiveStyle() {
            if (cardContainer == null) return;

            GradientDrawableHelper.setBorder(
                cardContainer,
                Color.parseColor("#333333"),
                1f
            );
            activeIndicator.setVisibility(View.GONE);
            cardContainer.setScaleX(1.0f);
            cardContainer.setScaleY(1.0f);
        }

        /**
         * Private tab color tinting.
         */
        private void applyPrivateTabStyle() {
            if (tabInfoBar != null) {
                tabInfoBar.setBackgroundColor(
                    Color.parseColor("#1a0a2e")
                );
            }
            tvTitle.setTextColor(Color.parseColor("#ce93d8"));
            tvUrl.setTextColor(Color.parseColor("#9575cd"));
        }

        /**
         * Normal tab color.
         */
        private void applyNormalTabStyle() {
            if (tabInfoBar != null) {
                tabInfoBar.setBackgroundColor(
                    Color.parseColor("#1e1e1e")
                );
            }
            tvTitle.setTextColor(Color.WHITE);
            tvUrl.setTextColor(Color.parseColor("#aaaaaa"));
        }

        /**
         * Shows loading state — progress indicator on thumbnail.
         */
        private void showLoadingState() {
            thumbnail.setAlpha(0.5f);
        }

        /**
         * Shows error state — red tint on thumbnail.
         */
        private void showErrorState() {
            thumbnail.setAlpha(0.7f);
            thumbnail.setColorFilter(
                Color.parseColor("#33ff0000"),
                android.graphics.PorterDuff.Mode.OVERLAY
            );
        }

        /**
         * Normal loaded state.
         */
        private void showNormalState() {
            thumbnail.setAlpha(1.0f);
            thumbnail.clearColorFilter();
        }

        // ─────────────────────────────────────────
        // Animations
        // ─────────────────────────────────────────

        /**
         * Tap animation — slight scale bounce.
         */
        private void animateTabSelect(View view) {
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(
                view, "scaleX", 1f, 0.95f, 1f
            );
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(
                view, "scaleY", 1f, 0.95f, 1f
            );
            AnimatorSet set = new AnimatorSet();
            set.playTogether(scaleX, scaleY);
            set.setDuration(150);
            set.setInterpolator(new DecelerateInterpolator());
            set.start();
        }

        /**
         * Close animation — scale down + fade out.
         * Runs callback after animation completes.
         */
        private void animateTabClose(View view, Runnable onComplete) {
            isAnimating = true;

            ObjectAnimator scaleX  = ObjectAnimator.ofFloat(
                view, "scaleX", 1f, 0f
            );
            ObjectAnimator scaleY  = ObjectAnimator.ofFloat(
                view, "scaleY", 1f, 0f
            );
            ObjectAnimator alpha   = ObjectAnimator.ofFloat(
                view, "alpha", 1f, 0f
            );

            AnimatorSet set = new AnimatorSet();
            set.playTogether(scaleX, scaleY, alpha);
            set.setDuration(200);
            set.setInterpolator(new DecelerateInterpolator());
            set.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator anim) {
                    isAnimating = false;
                    onComplete.run();
                }
            });
            set.start();
        }
    }

    // ═════════════════════════════════════════════
    // DATA MANAGEMENT
    // ═════════════════════════════════════════════

    /**
     * Updates the full tab list using DiffUtil.
     * Efficiently animates only changed items.
     *
     * @param newTabs     Updated list of tabs
     * @param activeTabId ID of currently active tab
     */
    public void updateTabs(
            List<TabManager.Tab> newTabs, String activeTabId) {

        this.activeTabId = activeTabId;

        // Calculate diff for efficient updates
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(
            new TabDiffCallback(this.tabs, newTabs)
        );

        this.tabs.clear();
        this.tabs.addAll(newTabs);

        // Apply diff animations
        diffResult.dispatchUpdatesTo(this);
    }

    /**
     * Adds a single tab with animation.
     * Called when new tab is opened.
     */
    public void addTab(TabManager.Tab tab) {
        tabs.add(tab);
        int position = tabs.size() - 1;
        notifyItemInserted(position);

        // Animate in
        // RecyclerView handles item animator automatically
    }

    /**
     * Removes a tab by ID with animation.
     * Called when tab is closed.
     */
    public void removeTab(String tabId) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).id.equals(tabId)) {
                tabs.remove(i);
                notifyItemRemoved(i);
                return;
            }
        }
    }

    /**
     * Updates a single tab's display data.
     * Called on title/URL/thumbnail change.
     */
    public void updateTab(String tabId) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).id.equals(tabId)) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    /**
     * Sets the active tab and refreshes affected items.
     */
    public void setActiveTab(String newActiveTabId) {
        String oldActiveTabId = this.activeTabId;
        this.activeTabId = newActiveTabId;

        // Refresh old active tab (remove highlight)
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).id.equals(oldActiveTabId)) {
                notifyItemChanged(i);
                break;
            }
        }

        // Refresh new active tab (add highlight)
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).id.equals(newActiveTabId)) {
                notifyItemChanged(i);
                break;
            }
        }
    }

    /**
     * Returns current tab list (unmodifiable).
     */
    public List<TabManager.Tab> getTabs() {
        return Collections.unmodifiableList(tabs);
    }

    // ═════════════════════════════════════════════
    // DIFF UTIL CALLBACK
    // ═════════════════════════════════════════════

    /**
     * DiffUtil callback for efficient tab list updates.
     * Compares tabs by ID and content for minimal redraws.
     */
    private static class TabDiffCallback extends DiffUtil.Callback {

        private final List<TabManager.Tab> oldList;
        private final List<TabManager.Tab> newList;

        TabDiffCallback(
                List<TabManager.Tab> oldList,
                List<TabManager.Tab> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() { return oldList.size(); }

        @Override
        public int getNewListSize() { return newList.size(); }

        /**
         * Same tab? → Compare by unique ID.
         */
        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            return oldList.get(oldPos).id
                .equals(newList.get(newPos).id);
        }

        /**
         * Same content? → Compare display fields.
         * If false → item is redrawn.
         */
        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            TabManager.Tab oldTab = oldList.get(oldPos);
            TabManager.Tab newTab = newList.get(newPos);

            // Compare all display-relevant fields
            boolean sameTitle = safeEquals(oldTab.title, newTab.title);
            boolean sameUrl   = safeEquals(oldTab.url,   newTab.url);
            boolean sameLoad  = oldTab.isLoading == newTab.isLoading;
            boolean sameError = oldTab.hasError  == newTab.hasError;
            boolean sameProg  = oldTab.progress  == newTab.progress;

            return sameTitle && sameUrl && sameLoad
                && sameError && sameProg;
        }

        private boolean safeEquals(String a, String b) {
            if (a == null && b == null) return true;
            if (a == null || b == null) return false;
            return a.equals(b);
        }
    }

    // ═════════════════════════════════════════════
    // SWIPE TO CLOSE (ItemTouchHelper)
    // ═════════════════════════════════════════════

    /**
     * Attaches swipe-to-close gesture to the RecyclerView.
     * Swipe left or right to close a tab.
     *
     * Usage in TabSwitcherActivity:
     *   TabAdapter.attachSwipeToClose(recyclerView, adapter);
     */
    public static void attachSwipeToClose(
            RecyclerView recyclerView,
            TabAdapter adapter) {

        ItemTouchHelper.SimpleCallback swipeCallback =
            new ItemTouchHelper.SimpleCallback(
                0, // No drag
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT // Swipe both ways
            ) {
                @Override
                public boolean onMove(
                        @NonNull RecyclerView rv,
                        @NonNull RecyclerView.ViewHolder vh,
                        @NonNull RecyclerView.ViewHolder target) {
                    return false; // No drag-and-drop
                }

                @Override
                public void onSwiped(
                        @NonNull RecyclerView.ViewHolder viewHolder,
                        int direction) {

                    int position = viewHolder.getAdapterPosition();
                    if (position == RecyclerView.NO_ID) return;
                    if (position >= adapter.tabs.size()) return;

                    TabManager.Tab tab = adapter.tabs.get(position);

                    if (adapter.listener != null) {
                        adapter.listener.onTabClosed(tab, position);
                    }
                }

                /**
                 * Draw swipe background — red with trash icon.
                 */
                @Override
                public void onChildDraw(
                        @NonNull Canvas canvas,
                        @NonNull RecyclerView recyclerView,
                        @NonNull RecyclerView.ViewHolder viewHolder,
                        float dX, float dY,
                        int actionState,
                        boolean isCurrentlyActive) {

                    View itemView = viewHolder.itemView;
                    Paint bgPaint = new Paint();
                    bgPaint.setColor(Color.parseColor("#c62828")); // Red

                    // Draw red background
                    RectF background;
                    if (dX > 0) {
                        // Swiping right
                        background = new RectF(
                            itemView.getLeft(),
                            itemView.getTop(),
                            itemView.getLeft() + dX,
                            itemView.getBottom()
                        );
                    } else {
                        // Swiping left
                        background = new RectF(
                            itemView.getRight() + dX,
                            itemView.getTop(),
                            itemView.getRight(),
                            itemView.getBottom()
                        );
                    }

                    canvas.drawRoundRect(background, 12f, 12f, bgPaint);

                    // Draw "✕" text
                    Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    textPaint.setColor(Color.WHITE);
                    textPaint.setTextSize(40f);
                    textPaint.setTextAlign(Paint.Align.CENTER);
                    textPaint.setFakeBoldText(true);

                    float centerY = (itemView.getTop()
                        + itemView.getBottom()) / 2f
                        - (textPaint.descent()
                        + textPaint.ascent()) / 2f;

                    if (Math.abs(dX) > 80) {
                        float textX = dX > 0
                            ? itemView.getLeft() + dX / 2f
                            : itemView.getRight() + dX / 2f;
                        canvas.drawText("✕", textX, centerY, textPaint);
                    }

                    super.onChildDraw(canvas, recyclerView, viewHolder,
                        dX, dY, actionState, isCurrentlyActive);
                }
            };

        new ItemTouchHelper(swipeCallback).attachToRecyclerView(recyclerView);
    }

    // ═════════════════════════════════════════════
    // "NEW TAB" BUTTON (Last Item)
    // ═════════════════════════════════════════════

    /**
     * View type constants for normal tab vs "New Tab" button.
     */
    private static final int VIEW_TYPE_TAB     = 0;
    private static final int VIEW_TYPE_NEW_TAB = 1;

    // ═════════════════════════════════════════════
    // GRADIENT DRAWABLE HELPER
    // ═════════════════════════════════════════════

    /**
     * Helper to apply rounded border to a view.
     * Used for active/inactive tab card styling.
     */
    private static class GradientDrawableHelper {

        static void setBorder(View view, int color, float strokeWidthDp) {
            android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable();

            drawable.setShape(
                android.graphics.drawable.GradientDrawable.RECTANGLE
            );
            drawable.setCornerRadius(16f);
            drawable.setColor(Color.parseColor("#1e1e1e"));
            drawable.setStroke(
                (int) strokeWidthDp,
                color
            );

            view.setBackground(drawable);
        }
    }

    // ═════════════════════════════════════════════
    // SETTER
    // ═════════════════════════════════════════════

    public void setListener(TabAdapterListener listener) {
        this.listener = listener;
    }

    public int getTabCount() {
        return tabs.size();
    }
      }
