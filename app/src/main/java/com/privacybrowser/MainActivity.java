package com.privacybrowser;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.privacybrowser.browser.BrowserEngine;
import com.privacybrowser.browser.HistoryManager;
import com.privacybrowser.browser.ProxyManager;
import com.privacybrowser.browser.TabManager;
import com.privacybrowser.ui.BrowserUI;
import com.privacybrowser.ui.SettingsActivity;
import com.privacybrowser.ui.TabSwitcherActivity;
import com.privacybrowser.utils.Constants;
import com.privacybrowser.utils.NetworkUtils;
import com.privacybrowser.utils.UrlValidator;

/**
 * MainActivity.java
 * Entry point and root controller of Privacy Browser Pro.
 *
 * Architecture:
 * ┌──────────────────────────────────────────────────┐
 * │                 MainActivity                     │
 * │                                                  │
 * │  ┌────────────────────────────────────────────┐  │
 * │  │              BrowserUI                     │  │
 * │  │  (Address bar, progress, nav buttons)      │  │
 * │  └───────────────────┬────────────────────────┘  │
 * │                      │                           │
 * │  ┌───────────────────▼────────────────────────┐  │
 * │  │            BrowserEngine                   │  │
 * │  │  (WebView control, AdBlock, History)       │  │
 * │  └───────────────────┬────────────────────────┘  │
 * │                      │                           │
 * │  ┌───────────────────▼────────────────────────┐  │
 * │  │             TabManager                     │  │
 * │  │  (Tab sessions, switching, closing)        │  │
 * │  └────────────────────────────────────────────┘  │
 * │                                                  │
 * │  Responsibilities:                               │
 * │   - Activity lifecycle → engine lifecycle        │
 * │   - Tab switching → WebView swap                 │
 * │   - Intent handling (links from other apps)      │
 * │   - Back button override (WebView history first) │
 * │   - Share / Download handling                    │
 * │   - Settings / TabSwitcher launch                │
 * └──────────────────────────────────────────────────┘
 */
public class MainActivity extends Activity
        implements
        BrowserUI.BrowserUIListener,
        TabManager.TabChangeListener {

    private static final String TAG = "MainActivity";

    // ─────────────────────────────────────────────
    // Request Codes
    // ─────────────────────────────────────────────
    private static final int REQUEST_TAB_SWITCHER = 100;
    private static final int REQUEST_SETTINGS     = 101;

    // ─────────────────────────────────────────────
    // Core Components
    // ─────────────────────────────────────────────
    private BrowserEngine     browserEngine;
    private BrowserUI         browserUI;
    private TabManager        tabManager;
    private HistoryManager    historyManager;
    private ProxyManager      proxyManager;
    private SharedPreferences prefs;

    // ─────────────────────────────────────────────
    // Views
    // ─────────────────────────────────────────────
    private WebView       webView;
    private FrameLayout   webViewContainer;
    private View          rootView;

    // ─────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────
    private boolean isPrivateMode     = false;
    private boolean isFullscreen      = false;
    private long    lastBackPressTime = 0;

    // UI thread handler
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // ═════════════════════════════════════════════
    // ACTIVITY LIFECYCLE
    // ═════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full screen immersive setup
        setupWindow();

        // Set layout
        setContentView(R.layout.activity_main);

        // Initialize SharedPreferences
        prefs = getSharedPreferences(
            Constants.PREFS_NAME, MODE_PRIVATE
        );

        // Load default private mode setting
        isPrivateMode = prefs.getBoolean(
            Constants.PREF_PRIVATE_MODE, false
        );

        // Bind root view
        rootView         = findViewById(R.id.root_layout);
        webViewContainer = findViewById(R.id.webview_container);

        // Initialize all components
        initializeComponents();

        // Restore or start fresh session
        if (savedInstanceState != null) {
            restoreSession(savedInstanceState);
        } else {
            startFreshSession();
        }

        // Handle intent (e.g. link opened from another app)
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (browserEngine != null) {
            browserEngine.onResume();
        }
        // Refresh nav button states
        if (browserUI != null) {
            browserUI.updateNavButtons();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (browserEngine != null) {
            browserEngine.onPause();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Save WebView state for rotation/backgrounding
        if (browserEngine != null) {
            Bundle webState = browserEngine.saveState();
            if (webState != null) {
                outState.putBundle("webview_state", webState);
            }
        }
        // Save current tab ID
        TabManager.Tab activeTab = tabManager.getActiveTab();
        if (activeTab != null) {
            outState.putString("active_tab_id", activeTab.id);
        }
        outState.putBoolean("private_mode", isPrivateMode);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Private mode cleanup on exit
        if (isPrivateMode && browserEngine != null) {
            browserEngine.clearPrivateSessionData();
        }

        if (browserEngine != null) {
            browserEngine.onDestroy();
        }

        android.util.Log.d(TAG, "MainActivity destroyed");
    }

    // ═════════════════════════════════════════════
    // INITIALIZATION
    // ═════════════════════════════════════════════

    /**
     * Sets up window flags for immersive browser UI.
     * Hides status bar title, keeps screen on while browsing.
     */
    private void setupWindow() {
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );
        // Allow WebView content under status bar
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
    }

    /**
     * Initializes all core components in correct order.
     *
     * Order matters:
     *  1. TabManager    (no dependencies)
     *  2. HistoryManager (no dependencies)
     *  3. ProxyManager  (no dependencies)
     *  4. WebView       (created programmatically)
     *  5. BrowserEngine (needs WebView)
     *  6. BrowserUI     (needs BrowserEngine + rootView)
     */
    private void initializeComponents() {

        // ── 1. Managers ──
        tabManager     = TabManager.getInstance(this);
        historyManager = HistoryManager.getInstance(this);
        proxyManager   = ProxyManager.getInstance(this);

        // Register tab change listener
        tabManager.setTabChangeListener(this);

        // ── 2. Create WebView programmatically ──
        // Note: Creating in XML can cause memory leaks
        webView = new WebView(getApplicationContext());
        webView.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // Add WebView to container
        if (webViewContainer != null) {
            webViewContainer.addView(webView);
        }

        // ── 3. BrowserEngine ──
        browserEngine = new BrowserEngine(this, webView);
        browserEngine.setPrivateMode(isPrivateMode);

        // ── 4. BrowserUI ──
        browserUI = new BrowserUI(this, browserEngine, rootView);
        browserUI.setUiListener(this);
        browserUI.setPrivateMode(isPrivateMode);

        // ── 5. Apply saved proxy settings ──
        restoreProxySettings();

        android.util.Log.d(TAG, "Components initialized. "
            + "Private mode: " + isPrivateMode);
    }

    /**
     * Starts a fresh session — opens default home tab.
     */
    private void startFreshSession() {
        String homeUrl = prefs.getString(
            Constants.PREF_HOME_URL,
            Constants.DEFAULT_HOME_URL
        );

        // Get or create default tab
        TabManager.Tab activeTab = tabManager.getActiveTab();
        if (activeTab == null) {
            activeTab = tabManager.openNewTab(isPrivateMode, homeUrl);
        }

        // Attach engine to tab and load URL
        browserEngine.attachToTab(activeTab);
        browserEngine.loadUrl(homeUrl);

        android.util.Log.d(TAG, "Fresh session started. URL: " + homeUrl);
    }

    /**
     * Restores previous session from saved state.
     * Called after rotation or backgrounding.
     */
    private void restoreSession(Bundle savedState) {
        // Restore WebView state
        Bundle webState = savedState.getBundle("webview_state");
        if (webState != null) {
            browserEngine.restoreState(webState);
        }

        // Restore private mode
        isPrivateMode = savedState.getBoolean("private_mode", false);
        browserEngine.setPrivateMode(isPrivateMode);
        browserUI.setPrivateMode(isPrivateMode);

        // Restore active tab
        String activeTabId = savedState.getString("active_tab_id");
        if (activeTabId != null) {
            tabManager.switchToTab(activeTabId);
        }

        android.util.Log.d(TAG, "Session restored");
    }

    /**
     * Restores proxy settings from SharedPreferences.
     */
    private void restoreProxySettings() {
        String proxyType = prefs.getString(
            Constants.PREF_PROXY_TYPE,
            Constants.PROXY_TYPE_NONE
        );

        if (!proxyType.equals(Constants.PROXY_TYPE_NONE)) {
            String host = prefs.getString(
                Constants.PREF_PROXY_HOST,
                Constants.PROXY_HOST_DEFAULT
            );
            String portStr = prefs.getString(
                Constants.PREF_PROXY_PORT,
                String.valueOf(Constants.PROXY_PORT_HTTP)
            );

            try {
                int port = Integer.parseInt(portStr);
                ProxyManager.ProxyConfig config =
                    new ProxyManager.ProxyConfig.Builder()
                        .type(proxyType)
                        .host(host)
                        .port(port)
                        .build();
                proxyManager.applyConfig(config, webView);
                android.util.Log.d(TAG,
                    "Proxy restored: " + proxyType);
            } catch (NumberFormatException e) {
                android.util.Log.e(TAG, "Invalid proxy port in prefs");
            }
        }
    }

    // ═════════════════════════════════════════════
    // INTENT HANDLING
    // ═════════════════════════════════════════════

    /**
     * Handles incoming intents.
     *
     * Scenarios:
     *  1. App launched normally → nothing extra
     *  2. Link clicked in another app → open URL
     *  3. Share target → load shared URL
     */
    private void handleIntent(Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        Uri    data   = intent.getData();

        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            // Opened from external link
            String url = data.toString();
            android.util.Log.d(TAG, "External URL intent: " + url);

            // Validate before loading
            if (!UrlValidator.isDangerousScheme(url)) {
                // Open in new tab
                tabManager.openNewTab(isPrivateMode, url);
                browserEngine.loadUrl(url);
            }

        } else if (Intent.ACTION_SEND.equals(action)) {
            // Shared text/URL from another app
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null && !sharedText.isEmpty()) {
                android.util.Log.d(TAG, "Shared text: " + sharedText);
                browserEngine.loadUrl(sharedText);
            }
        }
    }

    // ═════════════════════════════════════════════
    // BACK BUTTON OVERRIDE
    // ═════════════════════════════════════════════

    /**
     * Overrides back button for browser-like behavior.
     *
     * Priority:
     *  1. Exit fullscreen video (if active)
     *  2. WebView go back (browser history)
     *  3. Double-tap back to exit app
     */
    @Override
    public void onBackPressed() {

        // ── 1. Exit fullscreen ──
        if (isFullscreen) {
            exitFullscreen();
            return;
        }

        // ── 2. WebView go back ──
        if (browserEngine != null && browserEngine.goBack()) {
            return; // WebView handled it
        }

        // ── 3. Double-tap back to exit ──
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastBackPressTime < 2000) {
            // Second tap within 2 seconds → exit
            super.onBackPressed();
        } else {
            lastBackPressTime = currentTime;
            Toast.makeText(
                this,
                "Press back again to exit",
                Toast.LENGTH_SHORT
            ).show();
        }
    }

    /**
     * Handles hardware key events.
     * Volume keys can scroll page (optional feature).
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SEARCH:
                // Search key → focus address bar
                if (browserUI != null) {
                    browserUI.focusAddressBar();
                    return true;
                }
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ═════════════════════════════════════════════
    // BROWSER UI LISTENER IMPLEMENTATION
    // ═════════════════════════════════════════════

    /**
     * Called when tab switcher button is tapped.
     * Launches TabSwitcherActivity.
     */
    @Override
    public void onTabSwitcherRequested() {
        Intent intent = new Intent(this, TabSwitcherActivity.class);
        startActivityForResult(intent, REQUEST_TAB_SWITCHER);
        // Slide up animation
        overridePendingTransition(
            android.R.anim.slide_in_left,
            android.R.anim.slide_out_right
        );
    }

    /**
     * Called when new tab is requested from menu.
     *
     * @param isPrivate Whether to open private tab
     */
    @Override
    public void onNewTabRequested(boolean isPrivate) {
        TabManager.Tab newTab = tabManager.openNewTab(isPrivate, null);
        browserEngine.attachToTab(newTab);
        browserEngine.setPrivateMode(isPrivate);

        // Load home page in new tab
        String homeUrl = prefs.getString(
            Constants.PREF_HOME_URL,
            Constants.DEFAULT_HOME_URL
        );
        browserEngine.loadUrl(homeUrl);

        // Update UI for private mode
        if (isPrivate != isPrivateMode) {
            isPrivateMode = isPrivate;
            browserUI.setPrivateMode(isPrivate);
        }

        browserUI.updateTabCountBadge();

        android.util.Log.d(TAG, "New tab opened. Private: " + isPrivate);
    }

    /**
     * Called when Settings button is tapped.
     */
    @Override
    public void onSettingsRequested() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivityForResult(intent, REQUEST_SETTINGS);
        overridePendingTransition(
            android.R.anim.fade_in,
            android.R.anim.fade_out
        );
    }

    /**
     * Called when Home button is tapped.
     */
    @Override
    public void onHomeRequested() {
        String homeUrl = prefs.getString(
            Constants.PREF_HOME_URL,
            Constants.DEFAULT_HOME_URL
        );
        browserEngine.loadUrl(homeUrl);
    }

    /**
     * Called when Bookmarks button is tapped.
     * Placeholder — extend with BookmarksActivity.
     */
    @Override
    public void onBookmarksRequested() {
        // TODO: Launch BookmarksActivity
        Toast.makeText(
            this, "Bookmarks coming soon", Toast.LENGTH_SHORT
        ).show();
    }

    /**
     * Called when Share button is tapped from menu.
     * Uses Android share sheet.
     */
    @Override
    public void onShareRequested(String url, String title) {
        if (url == null || url.isEmpty()) return;

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, url);
        shareIntent.putExtra(
            Intent.EXTRA_SUBJECT,
            title != null ? title : url
        );

        startActivity(Intent.createChooser(
            shareIntent, "Share via"
        ));
    }

    /**
     * Called when Clear Data is requested from menu.
     * Shows confirmation then clears.
     */
    @Override
    public void onClearDataRequested() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Clear Browsing Data")
            .setMessage(
                "Clear history, cookies, and cache?"
            )
            .setPositiveButton("Clear", (d, w) -> {
                browserEngine.clearAllBrowsingData();
                browserUI.showToast("✅ Browsing data cleared");
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ═════════════════════════════════════════════
    // TAB MANAGER LISTENER IMPLEMENTATION
    // ═════════════════════════════════════════════

    /**
     * Called when a new tab is added.
     */
    @Override
    public void onTabAdded(TabManager.Tab tab, int position) {
        uiHandler.post(() -> {
            if (browserUI != null) {
                browserUI.updateTabCountBadge();
            }
            android.util.Log.d(TAG, "Tab added at position: " + position);
        });
    }

    /**
     * Called when a tab is closed.
     */
    @Override
    public void onTabClosed(TabManager.Tab tab, int position) {
        uiHandler.post(() -> {
            if (browserUI != null) {
                browserUI.updateTabCountBadge();
            }

            // If closed tab was private, clean up
            if (tab.isPrivate) {
                browserEngine.clearPrivateSessionData();
            }

            android.util.Log.d(TAG, "Tab closed: " + tab.id);
        });
    }

    /**
     * Called when active tab changes (user switches tabs).
     * Swaps WebView content for the new tab.
     */
    @Override
    public void onTabSwitched(TabManager.Tab tab, int position) {
        uiHandler.post(() -> {
            android.util.Log.d(TAG,
                "Tab switched to: " + tab.id + " | " + tab.url);

            // Update engine for new tab
            browserEngine.attachToTab(tab);

            // Update private mode UI
            if (tab.isPrivate != isPrivateMode) {
                isPrivateMode = tab.isPrivate;
                browserUI.setPrivateMode(isPrivateMode);
                browserEngine.setPrivateMode(isPrivateMode);
            }

            // Update address bar with new tab's URL
            browserUI.updateAddressBar(tab.url);
            browserUI.updateTabCountBadge();
            browserUI.updateNavButtons();
        });
    }

    /**
     * Called when a tab's state is updated (URL, title, progress).
     */
    @Override
    public void onTabUpdated(TabManager.Tab tab, int position) {
        // Only update UI if this is the active tab
        TabManager.Tab activeTab = tabManager.getActiveTab();
        if (activeTab != null && activeTab.id.equals(tab.id)) {
            uiHandler.post(() -> {
                if (browserUI != null) {
                    browserUI.updateAddressBar(tab.url);
                }
            });
        }
    }

    /**
     * Called when all tabs are closed.
     * Opens a fresh default tab.
     */
    @Override
    public void onAllTabsClosed() {
        uiHandler.post(() -> {
            android.util.Log.d(TAG, "All tabs closed — opening default");
            startFreshSession();
            if (browserUI != null) {
                browserUI.updateTabCountBadge();
            }
        });
    }

    // ═════════════════════════════════════════════
    // ACTIVITY RESULTS
    // ═════════════════════════════════════════════

    /**
     * Handles results from TabSwitcherActivity and SettingsActivity.
     */
    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_TAB_SWITCHER) {
            handleTabSwitcherResult(resultCode, data);

        } else if (requestCode == REQUEST_SETTINGS) {
            handleSettingsResult();
        }
    }

    /**
     * Processes result from tab switcher.
     *
     * Result cases:
     *  RESULT_OK + tab_id  → switch to selected tab
     *  RESULT_OK + new_tab → open new tab
     *  RESULT_CANCELED     → nothing changed
     */
    private void handleTabSwitcherResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) return;

        String tabId = data.getStringExtra(Constants.INTENT_TAB_ID);
        boolean isPrivate = data.getBooleanExtra(
            Constants.INTENT_PRIVATE_MODE, false
        );
        boolean newTab = data.getBooleanExtra("new_tab", false);

        if (newTab) {
            // User requested new tab from switcher
            onNewTabRequested(isPrivate);

        } else if (tabId != null) {
            // User selected an existing tab
            boolean switched = tabManager.switchToTab(tabId);
            if (switched) {
                TabManager.Tab tab = tabManager.findById(tabId);
                if (tab != null) {
                    browserEngine.attachToTab(tab);
                    browserUI.updateAddressBar(tab.url);
                    android.util.Log.d(TAG,
                        "Switched to tab from switcher: " + tabId);
                }
            }
        }

        // Slide back animation
        overridePendingTransition(
            android.R.anim.slide_in_left,
            android.R.anim.slide_out_right
        );
    }

    /**
     * Re-applies settings changes after returning from SettingsActivity.
     * Reloads preferences that affect the current session.
     */
    private void handleSettingsResult() {
        // Reload preferences
        boolean adBlockEnabled = prefs.getBoolean(
            Constants.PREF_ADBLOCK_ENABLED, true
        );
        boolean jsEnabled = prefs.getBoolean(
            Constants.PREF_JS_ENABLED, true
        );
        boolean darkMode = prefs.getBoolean(
            Constants.PREF_DARK_MODE, false
        );

        // Apply to engine
        browserEngine.setAdBlockEnabled(adBlockEnabled);
        browserEngine.setJavascriptEnabled(jsEnabled);
        browserEngine.setDarkModeEnabled(darkMode);

        // Re-apply proxy
        restoreProxySettings();

        android.util.Log.d(TAG, "Settings re-applied");
    }

    // ═════════════════════════════════════════════
    // FULLSCREEN SUPPORT
    // ═════════════════════════════════════════════

    /**
     * Enters fullscreen mode for video playback.
     * Called from BrowserUI.onFullscreenRequested(enter=true)
     */
    public void enterFullscreen() {
        isFullscreen = true;
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
        android.util.Log.d(TAG, "Fullscreen entered");
    }

    /**
     * Exits fullscreen mode.
     */
    public void exitFullscreen() {
        isFullscreen = false;
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
        android.util.Log.d(TAG, "Fullscreen exited");
    }

    // ═════════════════════════════════════════════
    // NETWORK STATUS CHECK
    // ═════════════════════════════════════════════

    /**
     * Checks network on resume and shows warning if offline.
     * Called from onResume.
     */
    private void checkNetworkStatus() {
        if (!NetworkUtils.isConnected(this)) {
            Toast.makeText(
                this,
                "📵 No internet connection",
                Toast.LENGTH_LONG
            ).show();
        }
    }
              }
