package com.privacybrowser.ui;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.privacybrowser.R;
import com.privacybrowser.browser.AdBlockEngine;
import com.privacybrowser.browser.BrowserEngine;
import com.privacybrowser.browser.HistoryManager;
import com.privacybrowser.browser.TabManager;
import com.privacybrowser.utils.Constants;
import com.privacybrowser.utils.UrlValidator;

import java.util.List;

/**
 * BrowserUI.java
 * Master UI controller for Privacy Browser Pro.
 *
 * Architecture:
 * ┌─────────────────────────────────────────────────┐
 * │                  BrowserUI                      │
 * │                                                 │
 * │  ┌─────────────────────────────────────────┐   │
 * │  │           Address Bar Area              │   │
 * │  │  [🔒][  URL EditText  ][✕][⋮]          │   │
 * │  └─────────────────────────────────────────┘   │
 * │  ┌─────────────────────────────────────────┐   │
 * │  │           Progress Bar                  │   │
 * │  └─────────────────────────────────────────┘   │
 * │  ┌─────────────────────────────────────────┐   │
 * │  │           WebView Container             │   │
 * │  │         (BrowserEngine renders here)    │   │
 * │  └─────────────────────────────────────────┘   │
 * │  ┌─────────────────────────────────────────┐   │
 * │  │           Bottom Navigation Bar         │   │
 * │  │  [←][→][🏠][📋 Tabs][⋮ Menu]          │   │
 * │  └─────────────────────────────────────────┘   │
 * └─────────────────────────────────────────────────┘
 *
 * Responsibilities:
 *  - Address bar input + autocomplete
 *  - Progress bar animation
 *  - Tab count badge
 *  - Private mode visual indicator
 *  - SSL padlock icon
 *  - Bottom navigation bar
 *  - Suggestion dropdown
 *  - Menu bottom sheet
 *  - Toast / snackbar messages
 */
public class BrowserUI implements BrowserEngine.BrowserEngineListener {

    private static final String TAG = "BrowserUI";

    // ─────────────────────────────────────────────
    // Context & Engines
    // ─────────────────────────────────────────────
    private final Context        context;
    private final BrowserEngine  browserEngine;
    private final TabManager     tabManager;
    private final HistoryManager historyManager;
    private final AdBlockEngine  adBlockEngine;

    // ─────────────────────────────────────────────
    // UI References
    // ─────────────────────────────────────────────

    // Address Bar
    private EditText    addressBar;
    private ImageView   sslIcon;
    private ImageButton btnClear;
    private ImageButton btnMenu;
    private LinearLayout addressBarContainer;

    // Progress
    private ProgressBar progressBar;

    // Tab Bar
    private TextView    tabCountBadge;
    private ImageButton btnTabs;

    // Bottom Navigation
    private ImageButton btnBack;
    private ImageButton btnForward;
    private ImageButton btnHome;
    private ImageButton btnBookmarks;
    private LinearLayout bottomNavBar;

    // WebView Container
    private FrameLayout webViewContainer;

    // Suggestion Dropdown
    private LinearLayout suggestionDropdown;

    // Private Mode Indicator
    private View        privateModeBar;
    private TextView    privateModeLabel;

    // Toolbar container
    private LinearLayout toolbarContainer;

    // ─────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────
    private boolean   isPrivateMode      = false;
    private boolean   isAddressBarFocused = false;
    private boolean   isLoading          = false;
    private String    currentUrl         = "";
    private Bitmap    currentFavicon     = null;

    // Handler for UI thread updates
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // Suggestion list update delay
    private static final int SUGGESTION_DELAY_MS = 200;
    private final Runnable suggestionRunnable = this::updateSuggestions;

    // ─────────────────────────────────────────────
    // Listener
    // ─────────────────────────────────────────────
    private BrowserUIListener uiListener;

    /**
     * Callbacks for MainActivity to handle UI events.
     */
    public interface BrowserUIListener {
        void onTabSwitcherRequested();
        void onNewTabRequested(boolean isPrivate);
        void onSettingsRequested();
        void onHomeRequested();
        void onBookmarksRequested();
        void onShareRequested(String url, String title);
        void onClearDataRequested();
    }

    // ═════════════════════════════════════════════
    // CONSTRUCTOR
    // ═════════════════════════════════════════════

    public BrowserUI(
            @NonNull Context context,
            @NonNull BrowserEngine browserEngine,
            @NonNull ViewGroup rootView) {

        this.context        = context;
        this.browserEngine  = browserEngine;
        this.tabManager     = TabManager.getInstance(context);
        this.historyManager = HistoryManager.getInstance(context);
        this.adBlockEngine  = AdBlockEngine.getInstance(context);

        // Register as BrowserEngine listener
        browserEngine.setListener(this);

        // Bind all UI views
        bindViews(rootView);

        // Setup all click/input listeners
        setupAddressBar();
        setupBottomNavBar();
        setupTabBadge();

        // Initial state
        updatePrivateModeUI(false);
        updateTabCountBadge();
    }

    // ═════════════════════════════════════════════
    // VIEW BINDING
    // ═════════════════════════════════════════════

    /**
     * Binds all view references from the layout.
     */
    private void bindViews(ViewGroup root) {
        // Address Bar components
        addressBarContainer = root.findViewById(R.id.address_bar_container);
        addressBar          = root.findViewById(R.id.address_bar);
        sslIcon             = root.findViewById(R.id.ssl_icon);
        btnClear            = root.findViewById(R.id.btn_clear);
        btnMenu             = root.findViewById(R.id.btn_menu);

        // Progress
        progressBar = root.findViewById(R.id.progress_bar);

        // Tab Badge
        tabCountBadge = root.findViewById(R.id.tab_count_badge);
        btnTabs       = root.findViewById(R.id.btn_tabs);

        // Bottom Nav
        btnBack       = root.findViewById(R.id.btn_back);
        btnForward    = root.findViewById(R.id.btn_forward);
        btnHome       = root.findViewById(R.id.btn_home);
        btnBookmarks  = root.findViewById(R.id.btn_bookmarks);
        bottomNavBar  = root.findViewById(R.id.bottom_nav_bar);

        // Containers
        webViewContainer  = root.findViewById(R.id.webview_container);
        suggestionDropdown = root.findViewById(R.id.suggestion_dropdown);
        privateModeBar    = root.findViewById(R.id.private_mode_bar);
        privateModeLabel  = root.findViewById(R.id.private_mode_label);
        toolbarContainer  = root.findViewById(R.id.toolbar_container);
    }

    // ═════════════════════════════════════════════
    // ADDRESS BAR SETUP
    // ═════════════════════════════════════════════

    /**
     * Sets up address bar input handling.
     *
     * Features:
     *  - Tap → select all text (Chrome-like UX)
     *  - Type → show suggestions from history
     *  - Enter / "Go" → load URL
     *  - Clear button (×) → appears while typing
     *  - Focus → show full URL
     *  - Unfocus → show display URL (stripped scheme)
     */
    @SuppressLint("ClickableViewAccessibility")
    private void setupAddressBar() {

        // ── Focus change handler ──
        addressBar.setOnFocusChangeListener((v, hasFocus) -> {
            isAddressBarFocused = hasFocus;

            if (hasFocus) {
                // Show full URL when focused
                if (currentUrl != null && !currentUrl.isEmpty()) {
                    addressBar.setText(currentUrl);
                    addressBar.selectAll();
                }
                // Show clear button
                btnClear.setVisibility(
                    addressBar.getText().length() > 0
                    ? View.VISIBLE : View.GONE
                );
                // Show suggestion dropdown
                updateSuggestions();
                // Animate address bar expand
                animateAddressBarFocus(true);

            } else {
                // Show display URL when unfocused
                setDisplayUrl(currentUrl);
                // Hide clear button
                btnClear.setVisibility(View.GONE);
                // Hide suggestions
                hideSuggestions();
                // Animate address bar collapse
                animateAddressBarFocus(false);
            }
        });

        // ── Text change handler ──
        addressBar.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int b, int c) {
                // Show/hide clear button
                btnClear.setVisibility(
                    s.length() > 0 ? View.VISIBLE : View.GONE
                );
            }

            @Override
            public void afterTextChanged(Editable s) {
                // Debounce suggestion updates
                uiHandler.removeCallbacks(suggestionRunnable);
                uiHandler.postDelayed(
                    suggestionRunnable, SUGGESTION_DELAY_MS
                );
            }
        });

        // ── IME action (keyboard "Go" button) ──
        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                || actionId == EditorInfo.IME_ACTION_SEARCH
                || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {

                String input = addressBar.getText().toString().trim();
                if (!input.isEmpty()) {
                    navigateTo(input);
                }
                return true;
            }
            return false;
        });

        // ── Clear button ──
        btnClear.setOnClickListener(v -> {
            addressBar.setText("");
            addressBar.requestFocus();
            showKeyboard(addressBar);
        });

        // ── Menu button ──
        btnMenu.setOnClickListener(v -> showBrowserMenu());

        // ── SSL icon click → show SSL info ──
        sslIcon.setOnClickListener(v -> showSslInfo());
    }

    // ═════════════════════════════════════════════
    // BOTTOM NAVIGATION BAR
    // ═════════════════════════════════════════════

    /**
     * Sets up bottom navigation bar buttons.
     */
    private void setupBottomNavBar() {

        // ── Back button ──
        btnBack.setOnClickListener(v -> {
            if (!browserEngine.goBack()) {
                showToast("Nothing to go back to");
            }
        });

        // Long press back → show back history list
        btnBack.setOnLongClickListener(v -> {
            showToast("Back history");
            return true;
        });

        // ── Forward button ──
        btnForward.setOnClickListener(v -> {
            if (!browserEngine.goForward()) {
                showToast("Nothing to go forward to");
            }
        });

        // ── Home button ──
        btnHome.setOnClickListener(v -> {
            String homeUrl = context
                .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(Constants.PREF_HOME_URL, Constants.DEFAULT_HOME_URL);
            navigateTo(homeUrl);
            if (uiListener != null) uiListener.onHomeRequested();
        });

        // ── Bookmarks button ──
        btnBookmarks.setOnClickListener(v -> {
            if (uiListener != null) uiListener.onBookmarksRequested();
        });

        // ── Tab switcher button ──
        btnTabs.setOnClickListener(v -> {
            if (uiListener != null) uiListener.onTabSwitcherRequested();
        });
    }

    // ═════════════════════════════════════════════
    // TAB BADGE
    // ═════════════════════════════════════════════

    /**
     * Sets up and updates the tab count badge.
     * Shows number of open tabs (max 9, then "9+").
     */
    private void setupTabBadge() {
        updateTabCountBadge();
    }

    /**
     * Updates tab count badge text.
     */
    public void updateTabCountBadge() {
        int count = tabManager.getTabCount();
        String text = count > 9 ? "9+" : String.valueOf(count);

        uiHandler.post(() -> {
            if (tabCountBadge != null) {
                tabCountBadge.setText(text);

                // Red badge in private mode
                if (isPrivateMode) {
                    tabCountBadge.setBackgroundResource(
                        R.drawable.tab_badge_private
                    );
                } else {
                    tabCountBadge.setBackgroundResource(
                        R.drawable.tab_badge_normal
                    );
                }
            }
        });
    }

    // ═════════════════════════════════════════════
    // PROGRESS BAR
    // ═════════════════════════════════════════════

    /**
     * Updates progress bar with smooth animation.
     *
     * @param progress 0–100
     */
    public void updateProgress(int progress) {
        uiHandler.post(() -> {
            if (progressBar == null) return;

            if (progress >= Constants.PROGRESS_COMPLETE) {
                // Animate to 100% then hide
                animateProgress(100);
                uiHandler.postDelayed(
                    () -> progressBar.setVisibility(View.GONE),
                    300
                );
            } else {
                progressBar.setVisibility(View.VISIBLE);
                animateProgress(progress);
            }
        });
    }

    /**
     * Animates progress bar smoothly.
     */
    private void animateProgress(int target) {
        ObjectAnimator animator = ObjectAnimator.ofInt(
            progressBar, "progress",
            progressBar.getProgress(), target
        );
        animator.setDuration(Constants.ANIMATION_DURATION);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.start();
    }

    // ═════════════════════════════════════════════
    // ADDRESS BAR UPDATES
    // ═════════════════════════════════════════════

    /**
     * Updates address bar with current URL.
     * Shows display URL (stripped scheme) when not focused.
     * Shows full URL when focused.
     *
     * @param url Full URL to display
     */
    public void updateAddressBar(String url) {
        this.currentUrl = url != null ? url : "";

        uiHandler.post(() -> {
            if (addressBar == null) return;

            // Only update if not currently being edited
            if (!isAddressBarFocused) {
                setDisplayUrl(url);
            }

            // Update SSL padlock
            updateSslIcon(url);
        });
    }

    /**
     * Sets display URL — strips https:// for cleaner look.
     */
    private void setDisplayUrl(String url) {
        if (url == null || url.isEmpty()
            || url.equals(Constants.BLANK_PAGE)) {
            addressBar.setHint("Search or enter address");
            addressBar.setText("");
            return;
        }
        addressBar.setText(UrlValidator.toDisplayUrl(url));
    }

    /**
     * Updates SSL padlock icon based on URL scheme.
     *
     * 🔒 Green  → HTTPS (secure)
     * ⚠️ Yellow → HTTP  (not secure)
     * 🌐 Grey   → Other / blank
     */
    private void updateSslIcon(String url) {
        if (sslIcon == null) return;

        if (url == null || url.isEmpty()
            || url.equals(Constants.BLANK_PAGE)) {
            sslIcon.setImageResource(R.drawable.ic_globe);
            sslIcon.setColorFilter(Color.parseColor("#888888"));
            return;
        }

        if (UrlValidator.isSecure(url)) {
            // HTTPS — green lock
            sslIcon.setImageResource(R.drawable.ic_lock);
            sslIcon.setColorFilter(Color.parseColor("#4CAF50"));
        } else {
            // HTTP — orange warning
            sslIcon.setImageResource(R.drawable.ic_warning);
            sslIcon.setColorFilter(Color.parseColor("#FF9800"));
        }
    }

    // ═════════════════════════════════════════════
    // SUGGESTION DROPDOWN
    // ═════════════════════════════════════════════

    /**
     * Updates suggestion dropdown based on current address bar text.
     *
     * Sources:
     *  1. History search results
     *  2. URL completion (adds https://)
     *  3. Search suggestion
     */
    private void updateSuggestions() {
        if (suggestionDropdown == null) return;

        String query = addressBar.getText().toString().trim();

        if (query.isEmpty()) {
            // Show recent history when no query
            showRecentSuggestions();
            return;
        }

        suggestionDropdown.removeAllViews();

        // ── 1. History matches ──
        List<HistoryManager.HistoryEntry> historyResults =
            historyManager.search(query);

        for (HistoryManager.HistoryEntry entry : historyResults) {
            addSuggestionItem(
                entry.getDisplayTitle(),
                entry.url,
                R.drawable.ic_history,
                false
            );
        }

        // ── 2. Direct URL suggestion ──
        if (UrlValidator.getLoadableUrl(query).startsWith("http")) {
            String urlSuggestion = query.contains("://")
                ? query : "https://" + query;
            addSuggestionItem(
                urlSuggestion,
                urlSuggestion,
                R.drawable.ic_globe,
                false
            );
        }

        // ── 3. Search suggestion ──
        addSuggestionItem(
            "Search: " + query,
            query,
            R.drawable.ic_search,
            true // isSearch = true
        );

        // Show dropdown if items exist
        suggestionDropdown.setVisibility(
            suggestionDropdown.getChildCount() > 0
            ? View.VISIBLE : View.GONE
        );
    }

    /**
     * Shows recent history items when address bar is focused but empty.
     */
    private void showRecentSuggestions() {
        if (suggestionDropdown == null) return;
        suggestionDropdown.removeAllViews();

        List<String> recentUrls = historyManager.getRecentUrls(5);
        for (String url : recentUrls) {
            addSuggestionItem(
                UrlValidator.toDisplayUrl(url),
                url,
                R.drawable.ic_history,
                false
            );
        }

        suggestionDropdown.setVisibility(
            recentUrls.isEmpty() ? View.GONE : View.VISIBLE
        );
    }

    /**
     * Adds a single item to the suggestion dropdown.
     *
     * @param title    Display text
     * @param url      URL to load when clicked
     * @param iconRes  Icon drawable resource
     * @param isSearch Whether this is a search suggestion
     */
    private void addSuggestionItem(
            String title, String url,
            int iconRes, boolean isSearch) {

        View item = LayoutInflater.from(context)
            .inflate(R.layout.item_suggestion, suggestionDropdown, false);

        TextView tvTitle = item.findViewById(R.id.suggestion_title);
        TextView tvUrl   = item.findViewById(R.id.suggestion_url);
        ImageView ivIcon = item.findViewById(R.id.suggestion_icon);

        tvTitle.setText(title);
        tvUrl.setText(isSearch ? "Search" : UrlValidator.toDisplayUrl(url));
        ivIcon.setImageResource(iconRes);

        item.setOnClickListener(v -> {
            hideSuggestions();
            hideKeyboard(addressBar);
            addressBar.clearFocus();
            navigateTo(url);
        });

        suggestionDropdown.addView(item);
    }

    /**
     * Hides suggestion dropdown.
     */
    private void hideSuggestions() {
        uiHandler.removeCallbacks(suggestionRunnable);
        if (suggestionDropdown != null) {
            suggestionDropdown.setVisibility(View.GONE);
            suggestionDropdown.removeAllViews();
        }
    }

    // ═════════════════════════════════════════════
    // PRIVATE MODE UI
    // ═════════════════════════════════════════════

    /**
     * Updates UI for private mode state.
     *
     * Private mode changes:
     *  - Top bar turns dark purple
     *  - "Private" label shown
     *  - Address bar tinted
     *  - Tab badge turns purple
     */
    public void updatePrivateModeUI(boolean isPrivate) {
        this.isPrivateMode = isPrivate;

        uiHandler.post(() -> {
            if (privateModeBar != null) {
                privateModeBar.setVisibility(
                    isPrivate ? View.VISIBLE : View.GONE
                );
            }

            if (privateModeLabel != null) {
                privateModeLabel.setText(
                    isPrivate ? "🕵️ Private Mode" : ""
                );
            }

            // Tint toolbar for private mode
            if (toolbarContainer != null) {
                toolbarContainer.setBackgroundColor(
                    isPrivate
                    ? Color.parseColor("#1a0a2e") // Dark purple
                    : Color.parseColor("#1e1e1e")  // Normal dark
                );
            }

            // Update tab badge color
            updateTabCountBadge();

            // Update address bar hint
            if (addressBar != null) {
                addressBar.setHint(
                    isPrivate
                    ? "Private search or address"
                    : "Search or enter address"
                );
            }
        });
    }

    // ═════════════════════════════════════════════
    // NAVIGATION
    // ═════════════════════════════════════════════

    /**
     * Navigates to a URL or search query.
     * Hides keyboard and clears address bar focus.
     */
    public void navigateTo(String urlOrQuery) {
        hideKeyboard(addressBar);
        addressBar.clearFocus();
        hideSuggestions();
        browserEngine.loadUrl(urlOrQuery);
    }

    // ═════════════════════════════════════════════
    // BROWSER MENU (Bottom Sheet)
    // ═════════════════════════════════════════════

    /**
     * Shows the browser overflow menu as a bottom sheet dialog.
     *
     * Menu items:
     *  New Tab | New Private Tab
     *  Bookmarks | History | Downloads
     *  Share | Find in Page
     *  Settings | Clear Data
     */
    private void showBrowserMenu() {
        android.app.Dialog dialog = new android.app.Dialog(
            context,
            R.style.BottomSheetDialogTheme
        );
        dialog.setContentView(R.layout.dialog_browser_menu);

        // Position at bottom
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            dialog.getWindow().setGravity(android.view.Gravity.BOTTOM);
        }

        // ── Bind menu items ──
        bindMenuItem(dialog, R.id.menu_new_tab, () -> {
            dialog.dismiss();
            if (uiListener != null) uiListener.onNewTabRequested(false);
        });

        bindMenuItem(dialog, R.id.menu_new_private_tab, () -> {
            dialog.dismiss();
            if (uiListener != null) uiListener.onNewTabRequested(true);
        });

        bindMenuItem(dialog, R.id.menu_bookmarks, () -> {
            dialog.dismiss();
            if (uiListener != null) uiListener.onBookmarksRequested();
        });

        bindMenuItem(dialog, R.id.menu_share, () -> {
            dialog.dismiss();
            if (uiListener != null) {
                uiListener.onShareRequested(
                    currentUrl,
                    browserEngine.getCurrentTitle()
                );
            }
        });

        bindMenuItem(dialog, R.id.menu_reload, () -> {
            dialog.dismiss();
            browserEngine.reload();
        });

        bindMenuItem(dialog, R.id.menu_settings, () -> {
            dialog.dismiss();
            if (uiListener != null) uiListener.onSettingsRequested();
        });

        bindMenuItem(dialog, R.id.menu_clear_data, () -> {
            dialog.dismiss();
            if (uiListener != null) uiListener.onClearDataRequested();
        });

        // AdBlock stats in menu
        TextView tvAdBlockStats = dialog.findViewById(R.id.tv_adblock_stats);
        if (tvAdBlockStats != null) {
            tvAdBlockStats.setText(browserEngine.getAdBlockStats());
        }

        dialog.show();
    }

    /**
     * Helper to bind a click listener to a menu item.
     */
    private void bindMenuItem(
            android.app.Dialog dialog,
            int viewId,
            Runnable action) {

        View item = dialog.findViewById(viewId);
        if (item != null) {
            item.setOnClickListener(v -> action.run());
        }
    }

    // ═════════════════════════════════════════════
    // SSL INFO DIALOG
    // ═════════════════════════════════════════════

    /**
     * Shows SSL certificate information dialog.
     * Tapping the padlock icon triggers this.
     */
    private void showSslInfo() {
        boolean isSecure = UrlValidator.isSecure(currentUrl);
        String  host     = UrlValidator.extractHost(currentUrl);

        String message = isSecure
            ? "✅ Connection is secure\n\n"
              + "Your information is private when sent to " + host + "."
            : "⚠️ Connection is not secure\n\n"
              + host + " is not using HTTPS. "
              + "Data sent to this site could be read by others.";

        new android.app.AlertDialog.Builder(context)
            .setTitle(isSecure ? "Secure Connection" : "Not Secure")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show();
    }

    // ═════════════════════════════════════════════
    // BROWSING ENGINE LISTENER CALLBACKS
    // ═════════════════════════════════════════════

    /**
     * Called when page starts loading.
     * Shows progress bar, updates address bar.
     */
    @Override
    public void onPageStarted(String url, Bitmap favicon) {
        isLoading = true;
        uiHandler.post(() -> {
            updateAddressBar(url);
            progressBar.setVisibility(View.VISIBLE);
            updateNavButtons();
        });
    }

    /**
     * Called when page finishes loading.
     * Hides progress bar, updates title.
     */
    @Override
    public void onPageFinished(String url, String title) {
        isLoading = false;
        uiHandler.post(() -> {
            updateAddressBar(url);
            updateNavButtons();
            progressBar.setVisibility(View.GONE);
        });
    }

    /**
     * Called with load progress (0–100).
     */
    @Override
    public void onProgressChanged(int progress) {
        updateProgress(progress);
    }

    /**
     * Called when page title changes.
     */
    @Override
    public void onTitleChanged(String title) {
        // Title shown in tab switcher — handled by TabManager
    }

    /**
     * Called when URL changes during navigation.
     */
    @Override
    public void onUrlChanged(String url) {
        updateAddressBar(url);
    }

    /**
     * Called when favicon is received.
     */
    @Override
    public void onFaviconReceived(Bitmap favicon) {
        this.currentFavicon = favicon;
        // Update SSL icon area if needed
    }

    /**
     * Called on page load error.
     * Error page is shown by BrowserEngine automatically.
     */
    @Override
    public void onLoadError(int errorCode, String description, String url) {
        uiHandler.post(() -> {
            progressBar.setVisibility(View.GONE);
            updateNavButtons();
        });
    }

    /**
     * Called on SSL error.
     * Shows warning toast.
     */
    @Override
    public void onSslError(String url, boolean proceed) {
        uiHandler.post(() ->
            showToast("⚠️ SSL Certificate Error — " +
                UrlValidator.extractHost(url))
        );
    }

    /**
     * Called when download is requested.
     */
    @Override
    public void onDownloadRequested(
            String url, String mimeType, long contentLength) {
        uiHandler.post(() ->
            showToast("Download: " + UrlValidator.toDisplayUrl(url))
        );
    }

    /**
     * Called when page requests new tab (target="_blank").
     */
    @Override
    public void onNewTabRequested(String url, boolean isPrivate) {
        if (uiListener != null) {
            uiListener.onNewTabRequested(isPrivate);
        }
        // Open URL in new tab via TabManager
        tabManager.openNewTab(isPrivate, url);
        updateTabCountBadge();
    }

    /**
     * Called for fullscreen video requests.
     */
    @Override
    public void onFullscreenRequested(View view, boolean enter) {
        uiHandler.post(() -> {
            if (enter) {
                // Hide toolbar and nav bar for fullscreen
                toolbarContainer.setVisibility(View.GONE);
                bottomNavBar.setVisibility(View.GONE);
                if (webViewContainer != null) {
                    webViewContainer.addView(view);
                }
            } else {
                // Restore UI
                toolbarContainer.setVisibility(View.VISIBLE);
                bottomNavBar.setVisibility(View.VISIBLE);
                if (webViewContainer != null) {
                    webViewContainer.removeView(view);
                }
            }
        });
    }

    // ═════════════════════════════════════════════
    // NAV BUTTON STATE
    // ═════════════════════════════════════════════

    /**
     * Updates back/forward button enabled states.
     * Dimmed when not available.
     */
    public void updateNavButtons() {
        uiHandler.post(() -> {
            if (btnBack != null) {
                btnBack.setAlpha(
                    browserEngine.canGoBack() ? 1.0f : 0.4f
                );
            }
            if (btnForward != null) {
                btnForward.setAlpha(
                    browserEngine.canGoForward() ? 1.0f : 0.4f
                );
            }
        });
    }

    // ═════════════════════════════════════════════
    // ANIMATIONS
    // ═════════════════════════════════════════════

    /**
     * Animates address bar on focus/unfocus.
     * Expands slightly when focused — Chrome-like feel.
     */
    private void animateAddressBarFocus(boolean focused) {
        if (addressBarContainer == null) return;

        float targetElevation = focused ? 8f : 2f;
        ObjectAnimator elevation = ObjectAnimator.ofFloat(
            addressBarContainer, "elevation",
            addressBarContainer.getElevation(), targetElevation
        );
        elevation.setDuration(Constants.ANIMATION_DURATION);
        elevation.start();
    }

    // ═════════════════════════════════════════════
    // KEYBOARD HELPERS
    // ═════════════════════════════════════════════

    /**
     * Shows soft keyboard for a view.
     */
    public void showKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager)
            context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    /**
     * Hides soft keyboard.
     */
    public void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager)
            context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && view != null) {
            imm.hideSoftInputFromWindow(
                view.getWindowToken(), 0
            );
        }
    }

    /**
     * Focuses address bar and shows keyboard.
     * Called when user taps address bar area.
     */
    public void focusAddressBar() {
        if (addressBar != null) {
            addressBar.requestFocus();
            showKeyboard(addressBar);
        }
    }

    // ═════════════════════════════════════════════
    // TOAST / MESSAGES
    // ═════════════════════════════════════════════

    /**
     * Shows a short Toast message on UI thread.
     */
    public void showToast(String message) {
        uiHandler.post(() ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        );
    }

    /**
     * Shows a long Toast message.
     */
    public void showLongToast(String message) {
        uiHandler.post(() ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        );
    }

    // ═════════════════════════════════════════════
    // PUBLIC SETTERS
    // ═════════════════════════════════════════════

    public void setUiListener(BrowserUIListener listener) {
        this.uiListener = listener;
    }

    public void setPrivateMode(boolean isPrivate) {
        updatePrivateModeUI(isPrivate);
    }

    public boolean isLoading() { return isLoading; }
    public String  getCurrentUrl() { return currentUrl; }
  }
