package com.privacybrowser.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.privacybrowser.R;
import com.privacybrowser.browser.AdBlockEngine;
import com.privacybrowser.browser.HistoryManager;
import com.privacybrowser.browser.ProxyManager;
import com.privacybrowser.utils.Constants;
import com.privacybrowser.utils.NetworkUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SettingsActivity.java
 * Full settings screen for Privacy Browser Pro.
 *
 * Architecture:
 * ┌──────────────────────────────────────────────────┐
 * │              SettingsActivity                    │
 * │                                                  │
 * │  ┌────────────────────────────────────────────┐  │
 * │  │  🔒 Privacy & Security                    │  │
 * │  │   • AdBlock ON/OFF                        │  │
 * │  │   • Block third-party cookies             │  │
 * │  │   • Do Not Track                          │  │
 * │  │   • Private mode default                  │  │
 * │  ├────────────────────────────────────────────┤  │
 * │  │  🌐 Proxy / Tor Mode                      │  │
 * │  │   • Proxy OFF / HTTP / SOCKS5             │  │
 * │  │   • Custom host + port                    │  │
 * │  │   • Test connection                       │  │
 * │  │   • Open Orbot                            │  │
 * │  ├────────────────────────────────────────────┤  │
 * │  │  🖥 Browser Settings                      │  │
 * │  │   • JavaScript ON/OFF                     │  │
 * │  │   • Dark mode                             │  │
 * │  │   • Search engine                         │  │
 * │  │   • Home page                             │  │
 * │  ├────────────────────────────────────────────┤  │
 * │  │  🗑 Data Management                       │  │
 * │  │   • Clear history                         │  │
 * │  │   • Clear cookies                         │  │
 * │  │   • Clear cache                           │  │
 * │  │   • Clear all data                        │  │
 * │  ├────────────────────────────────────────────┤  │
 * │  │  📊 Stats                                 │  │
 * │  │   • Ads blocked count                     │  │
 * │  │   • History count                         │  │
 * │  │   • App version                           │  │
 * │  └────────────────────────────────────────────┘  │
 * └──────────────────────────────────────────────────┘
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";

    // ─────────────────────────────────────────────
    // Core References
    // ─────────────────────────────────────────────
    private SharedPreferences prefs;
    private AdBlockEngine     adBlockEngine;
    private HistoryManager    historyManager;
    private ProxyManager      proxyManager;

    // Background executor for network tests
    private final ExecutorService executor =
        Executors.newSingleThreadExecutor();
    private final Handler uiHandler =
        new Handler(Looper.getMainLooper());

    // ─────────────────────────────────────────────
    // Privacy & Security Views
    // ─────────────────────────────────────────────
    private Switch  switchAdBlock;
    private Switch  switchBlockThirdParty;
    private Switch  switchDoNotTrack;
    private Switch  switchPrivateDefault;
    private Switch  switchSaveHistory;
    private TextView tvAdBlockStats;

    // ─────────────────────────────────────────────
    // Proxy Views
    // ─────────────────────────────────────────────
    private TextView    tvProxyStatus;
    private LinearLayout rowProxyOff;
    private LinearLayout rowProxyHttp;
    private LinearLayout rowProxySocks5;
    private LinearLayout rowProxyCustom;
    private LinearLayout rowTestProxy;
    private LinearLayout rowOpenOrbot;
    private TextView    tvProxyTestResult;

    // ─────────────────────────────────────────────
    // Browser Settings Views
    // ─────────────────────────────────────────────
    private Switch   switchJavaScript;
    private Switch   switchDarkMode;
    private TextView tvSearchEngine;
    private TextView tvHomePage;

    // ─────────────────────────────────────────────
    // Data Management Views
    // ─────────────────────────────────────────────
    private LinearLayout rowClearHistory;
    private LinearLayout rowClearCookies;
    private LinearLayout rowClearCache;
    private LinearLayout rowClearAll;
    private TextView     tvHistoryCount;

    // ─────────────────────────────────────────────
    // Stats Views
    // ─────────────────────────────────────────────
    private TextView tvAdsBlockedCount;
    private TextView tvHistoryEntries;
    private TextView tvAppVersion;
    private TextView tvConnectionType;

    // ═════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Initialize managers
        prefs          = getSharedPreferences(
            Constants.PREFS_NAME, Context.MODE_PRIVATE
        );
        adBlockEngine  = AdBlockEngine.getInstance(this);
        historyManager = HistoryManager.getInstance(this);
        proxyManager   = ProxyManager.getInstance(this);

        // Setup toolbar
        setupToolbar();

        // Bind all views
        bindViews();

        // Load current settings into views
        loadSettings();

        // Setup all listeners
        setupPrivacySection();
        setupProxySection();
        setupBrowserSection();
        setupDataSection();

        // Refresh stats
        refreshStats();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStats();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    // ═════════════════════════════════════════════
    // TOOLBAR
    // ═════════════════════════════════════════════

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.settings_toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                getSupportActionBar().setTitle("Settings");
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ═════════════════════════════════════════════
    // VIEW BINDING
    // ═════════════════════════════════════════════

    private void bindViews() {

        // ── Privacy & Security ──
        switchAdBlock         = findViewById(R.id.switch_adblock);
        switchBlockThirdParty = findViewById(R.id.switch_block_third_party);
        switchDoNotTrack      = findViewById(R.id.switch_do_not_track);
        switchPrivateDefault  = findViewById(R.id.switch_private_default);
        switchSaveHistory     = findViewById(R.id.switch_save_history);
        tvAdBlockStats        = findViewById(R.id.tv_adblock_stats);

        // ── Proxy ──
        tvProxyStatus     = findViewById(R.id.tv_proxy_status);
        rowProxyOff       = findViewById(R.id.row_proxy_off);
        rowProxyHttp      = findViewById(R.id.row_proxy_http);
        rowProxySocks5    = findViewById(R.id.row_proxy_socks5);
        rowProxyCustom    = findViewById(R.id.row_proxy_custom);
        rowTestProxy      = findViewById(R.id.row_test_proxy);
        rowOpenOrbot      = findViewById(R.id.row_open_orbot);
        tvProxyTestResult = findViewById(R.id.tv_proxy_test_result);

        // ── Browser ──
        switchJavaScript = findViewById(R.id.switch_javascript);
        switchDarkMode   = findViewById(R.id.switch_dark_mode);
        tvSearchEngine   = findViewById(R.id.tv_search_engine);
        tvHomePage       = findViewById(R.id.tv_home_page);

        // ── Data ──
        rowClearHistory = findViewById(R.id.row_clear_history);
        rowClearCookies = findViewById(R.id.row_clear_cookies);
        rowClearCache   = findViewById(R.id.row_clear_cache);
        rowClearAll     = findViewById(R.id.row_clear_all);
        tvHistoryCount  = findViewById(R.id.tv_history_count);

        // ── Stats ──
        tvAdsBlockedCount = findViewById(R.id.tv_ads_blocked);
        tvHistoryEntries  = findViewById(R.id.tv_history_entries);
        tvAppVersion      = findViewById(R.id.tv_app_version);
        tvConnectionType  = findViewById(R.id.tv_connection_type);
    }

    // ═════════════════════════════════════════════
    // LOAD CURRENT SETTINGS
    // ═════════════════════════════════════════════

    /**
     * Loads all saved preferences into UI controls.
     * Called once on create.
     */
    private void loadSettings() {

        // ── Privacy switches ──
        setSwitchSilently(
            switchAdBlock,
            prefs.getBoolean(Constants.PREF_ADBLOCK_ENABLED, true)
        );
        setSwitchSilently(
            switchBlockThirdParty,
            prefs.getBoolean(Constants.PREF_BLOCK_THIRD_PARTY, true)
        );
        setSwitchSilently(
            switchDoNotTrack,
            prefs.getBoolean(Constants.PREF_DO_NOT_TRACK, true)
        );
        setSwitchSilently(
            switchPrivateDefault,
            prefs.getBoolean(Constants.PREF_PRIVATE_MODE, false)
        );
        setSwitchSilently(
            switchSaveHistory,
            prefs.getBoolean(Constants.PREF_SAVE_HISTORY, true)
        );

        // ── Proxy status ──
        updateProxyStatusUI();

        // ── Browser switches ──
        setSwitchSilently(
            switchJavaScript,
            prefs.getBoolean(Constants.PREF_JS_ENABLED, true)
        );
        setSwitchSilently(
            switchDarkMode,
            prefs.getBoolean(Constants.PREF_DARK_MODE, false)
        );

        // ── Search engine ──
        updateSearchEngineLabel();

        // ── Home page ──
        String homeUrl = prefs.getString(
            Constants.PREF_HOME_URL,
            Constants.DEFAULT_HOME_URL
        );
        if (tvHomePage != null) tvHomePage.setText(homeUrl);

        // ── App version ──
        if (tvAppVersion != null) {
            tvAppVersion.setText(
                "Version " + Constants.APP_VERSION
            );
        }

        // ── AdBlock stats ──
        if (tvAdBlockStats != null) {
            tvAdBlockStats.setText(
                "Rules: "
                + adBlockEngine.getStatsMap().get("domain_rules")
                + " domains"
            );
        }
    }

    // ═════════════════════════════════════════════
    // PRIVACY & SECURITY SECTION
    // ═════════════════════════════════════════════

    private void setupPrivacySection() {

        // ── AdBlock toggle ──
        switchAdBlock.setOnCheckedChangeListener(
            (btn, isChecked) -> {
                savePref(Constants.PREF_ADBLOCK_ENABLED, isChecked);
                adBlockEngine.setEnabled(isChecked);
                showToast(isChecked
                    ? "Ad blocking enabled ✅"
                    : "Ad blocking disabled"
                );
                refreshStats();
            }
        );

        // ── Block third-party cookies ──
        switchBlockThirdParty.setOnCheckedChangeListener(
            (btn, isChecked) -> {
                savePref(Constants.PREF_BLOCK_THIRD_PARTY, isChecked);
                showToast(
                    isChecked
                    ? "Third-party cookies blocked 🍪"
                    : "Third-party cookies allowed"
                );
            }
        );

        // ── Do Not Track ──
        switchDoNotTrack.setOnCheckedChangeListener(
            (btn, isChecked) -> {
                savePref(Constants.PREF_DO_NOT_TRACK, isChecked);
                showToast(
                    isChecked ? "DNT header enabled" : "DNT header disabled"
                );
            }
        );

        // ── Default private mode ──
        switchPrivateDefault.setOnCheckedChangeListener(
            (btn, isChecked) -> {
                savePref(Constants.PREF_PRIVATE_MODE, isChecked);
                showToast(
                    isChecked
                    ? "🕵️ Private mode set as default"
                    : "Normal mode set as default"
                );
            }
        );

        // ── Save history ──
        switchSaveHistory.setOnCheckedChangeListener(
            (btn, isChecked) -> {
                savePref(Constants.PREF_SAVE_HISTORY, isChecked);
                historyManager.setSaveHistory(isChecked);
                showToast(
                    isChecked ? "History will be saved"
                              : "History will NOT be saved"
                );
            }
        );
    }

    // ═════════════════════════════════════════════
    // PROXY SECTION
    // ═════════════════════════════════════════════

    private void setupProxySection() {

        // ── Proxy OFF ──
        if (rowProxyOff != null) {
            rowProxyOff.setOnClickListener(v -> {
                savePref(Constants.PREF_PROXY_TYPE, Constants.PROXY_TYPE_NONE);
                proxyManager.disableProxy(null);
                updateProxyStatusUI();
                showToast("Proxy disabled — direct connection");
            });
        }

        // ── HTTP Proxy (Orbot port 8118) ──
        if (rowProxyHttp != null) {
            rowProxyHttp.setOnClickListener(v -> {
                checkOrbotAndApply(Constants.PROXY_TYPE_HTTP);
            });
        }

        // ── SOCKS5 Proxy (Orbot port 9050) ──
        if (rowProxySocks5 != null) {
            rowProxySocks5.setOnClickListener(v -> {
                checkOrbotAndApply(Constants.PROXY_TYPE_SOCKS5);
            });
        }

        // ── Custom Proxy ──
        if (rowProxyCustom != null) {
            rowProxyCustom.setOnClickListener(v ->
                showCustomProxyDialog()
            );
        }

        // ── Test proxy connection ──
        if (rowTestProxy != null) {
            rowTestProxy.setOnClickListener(v -> testProxyConnection());
        }

        // ── Open Orbot ──
        if (rowOpenOrbot != null) {
            rowOpenOrbot.setOnClickListener(v -> {
                proxyManager.openOrStartOrbot();
                showToast("Opening Orbot...");
            });
        }

        // Listen for proxy events
        proxyManager.setProxyStateListener(new ProxyManager.ProxyStateListener() {
            @Override
            public void onProxyEnabled(String type, String host, int port) {
                uiHandler.post(() -> {
                    updateProxyStatusUI();
                    showToast("Proxy enabled: "
                        + type + " → " + host + ":" + port);
                });
            }

            @Override
            public void onProxyDisabled() {
                uiHandler.post(SettingsActivity.this::updateProxyStatusUI);
            }

            @Override
            public void onProxyError(String reason) {
                uiHandler.post(() -> showToast("⚠️ " + reason));
            }

            @Override
            public void onProxyTestResult(boolean reachable, String message) {
                uiHandler.post(() -> {
                    if (tvProxyTestResult != null) {
                        tvProxyTestResult.setVisibility(View.VISIBLE);
                        tvProxyTestResult.setText(message);
                        tvProxyTestResult.setTextColor(
                            reachable
                            ? Color.parseColor("#4CAF50") // Green
                            : Color.parseColor("#f44336") // Red
                        );
                    }
                    showToast(reachable
                        ? "✅ Proxy reachable"
                        : "❌ Proxy unreachable"
                    );
                });
            }
        });
    }

    /**
     * Checks if Orbot is installed before applying proxy.
     * If not installed, shows install prompt.
     */
    private void checkOrbotAndApply(String proxyType) {
        if (!proxyManager.isOrbotInstalled()) {
            // Show install Orbot dialog
            new AlertDialog.Builder(this)
                .setTitle("Orbot Required")
                .setMessage(
                    "Tor-like routing requires Orbot app.\n\n"
                    + "Orbot routes your traffic through the "
                    + "Tor network for privacy.\n\n"
                    + "Install Orbot from Play Store?"
                )
                .setPositiveButton("Install Orbot", (d, w) -> {
                    proxyManager.openOrStartOrbot();
                })
                .setNegativeButton("Cancel", null)
                .show();
            return;
        }

        // Orbot installed — apply proxy
        savePref(Constants.PREF_PROXY_TYPE, proxyType);

        if (proxyType.equals(Constants.PROXY_TYPE_HTTP)) {
            savePref(
                Constants.PREF_PROXY_PORT,
                String.valueOf(Constants.PROXY_PORT_HTTP)
            );
            showToast("Enabling HTTP proxy via Orbot...");
        } else {
            savePref(
                Constants.PREF_PROXY_PORT,
                String.valueOf(Constants.PROXY_PORT_SOCKS5)
            );
            showToast("Enabling SOCKS5 proxy via Orbot...");
        }

        proxyManager.enableOrbotProxy(null);
        updateProxyStatusUI();
    }

    /**
     * Shows custom proxy configuration dialog.
     * Allows manual host:port entry.
     */
    private void showCustomProxyDialog() {
        View dialogView = getLayoutInflater()
            .inflate(R.layout.dialog_custom_proxy, null);

        android.widget.EditText etHost =
            dialogView.findViewById(R.id.et_proxy_host);
        android.widget.EditText etPort =
            dialogView.findViewById(R.id.et_proxy_port);
        android.widget.RadioGroup rgType =
            dialogView.findViewById(R.id.rg_proxy_type);

        // Pre-fill with saved values
        etHost.setText(prefs.getString(
            Constants.PREF_PROXY_HOST,
            Constants.PROXY_HOST_DEFAULT
        ));
        etPort.setText(prefs.getString(
            Constants.PREF_PROXY_PORT,
            String.valueOf(Constants.PROXY_PORT_HTTP)
        ));

        new AlertDialog.Builder(this)
            .setTitle("Custom Proxy")
            .setView(dialogView)
            .setPositiveButton("Apply", (dialog, which) -> {
                String host = etHost.getText().toString().trim();
                String portStr = etPort.getText().toString().trim();

                if (host.isEmpty() || portStr.isEmpty()) {
                    showToast("Host and port cannot be empty");
                    return;
                }

                try {
                    int port = Integer.parseInt(portStr);

                    // Determine proxy type from radio
                    String type = Constants.PROXY_TYPE_HTTP;
                    if (rgType != null) {
                        int selectedId = rgType.getCheckedRadioButtonId();
                        if (selectedId == R.id.rb_socks5) {
                            type = Constants.PROXY_TYPE_SOCKS5;
                        }
                    }

                    // Save and apply
                    savePref(Constants.PREF_PROXY_HOST, host);
                    savePref(Constants.PREF_PROXY_PORT, portStr);
                    savePref(Constants.PREF_PROXY_TYPE, type);

                    ProxyManager.ProxyConfig config =
                        new ProxyManager.ProxyConfig.Builder()
                            .type(type)
                            .host(host)
                            .port(port)
                            .build();

                    proxyManager.applyConfig(config, null);
                    updateProxyStatusUI();

                } catch (NumberFormatException e) {
                    showToast("Invalid port number");
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * Tests current proxy in background thread.
     */
    private void testProxyConnection() {
        if (tvProxyTestResult != null) {
            tvProxyTestResult.setVisibility(View.VISIBLE);
            tvProxyTestResult.setText("Testing proxy connection...");
            tvProxyTestResult.setTextColor(
                Color.parseColor("#ffaa00")
            );
        }
        showToast("Testing proxy...");
        proxyManager.testCurrentProxy();
    }

    /**
     * Updates proxy status label in UI.
     */
    private void updateProxyStatusUI() {
        if (tvProxyStatus == null) return;

        String status = proxyManager.getStatusString();
        tvProxyStatus.setText(status);

        boolean isActive = proxyManager.isActive();
        tvProxyStatus.setTextColor(
            isActive
            ? Color.parseColor("#4CAF50")  // Green when active
            : Color.parseColor("#888888")   // Grey when off
        );

        // Highlight active proxy row
        highlightActiveProxyRow();
    }

    /**
     * Visually highlights the currently active proxy mode row.
     */
    private void highlightActiveProxyRow() {
        // Reset all rows
        int normalColor = Color.parseColor("#1e1e1e");
        int activeColor = Color.parseColor("#1a2a1a");

        if (rowProxyOff    != null) rowProxyOff.setBackgroundColor(normalColor);
        if (rowProxyHttp   != null) rowProxyHttp.setBackgroundColor(normalColor);
        if (rowProxySocks5 != null) rowProxySocks5.setBackgroundColor(normalColor);
        if (rowProxyCustom != null) rowProxyCustom.setBackgroundColor(normalColor);

        // Highlight active one
        String type = proxyManager.getProxyType();
        switch (type) {
            case Constants.PROXY_TYPE_HTTP:
                if (rowProxyHttp != null)
                    rowProxyHttp.setBackgroundColor(activeColor);
                break;
            case Constants.PROXY_TYPE_SOCKS5:
                if (rowProxySocks5 != null)
                    rowProxySocks5.setBackgroundColor(activeColor);
                break;
            case Constants.PROXY_TYPE_NONE:
            default:
                if (rowProxyOff != null)
                    rowProxyOff.setBackgroundColor(activeColor);
                break;
        }
    }

    // ═════════════════════════════════════════════
    // BROWSER SETTINGS SECTION
    // ═════════════════════════════════════════════

    private void setupBrowserSection() {

        // ── JavaScript toggle ──
        switchJavaScript.setOnCheckedChangeListener(
            (btn, isChecked) -> {
                savePref(Constants.PREF_JS_ENABLED, isChecked);
                showToast(
                    isChecked
                    ? "JavaScript enabled"
                    : "⚠️ JavaScript disabled — some sites may break"
                );
            }
        );

        // ── Dark mode toggle ──
        switchDarkMode.setOnCheckedChangeListener(
            (btn, isChecked) -> {
                savePref(Constants.PREF_DARK_MODE, isChecked);
                showToast(
                    isChecked ? "🌙 Dark mode enabled"
                              : "☀️ Dark mode disabled"
                );
            }
        );

        // ── Search engine selector ──
        if (tvSearchEngine != null) {
            tvSearchEngine.setOnClickListener(v ->
                showSearchEngineDialog()
            );
        }

        // ── Home page editor ──
        if (tvHomePage != null) {
            tvHomePage.setOnClickListener(v ->
                showHomePageDialog()
            );
        }
    }

    /**
     * Shows search engine selection dialog.
     */
    private void showSearchEngineDialog() {
        String[] engines = {
            "DuckDuckGo (Recommended)",
            "Google",
            "Brave Search",
            "Startpage"
        };
        String[] urls = {
            Constants.SEARCH_DUCKDUCKGO,
            Constants.SEARCH_GOOGLE,
            Constants.SEARCH_BRAVE,
            Constants.SEARCH_STARTPAGE
        };

        // Find current selection
        String current = prefs.getString(
            Constants.PREF_SEARCH_ENGINE,
            Constants.SEARCH_DUCKDUCKGO
        );
        int currentIndex = 0;
        for (int i = 0; i < urls.length; i++) {
            if (urls[i].equals(current)) {
                currentIndex = i;
                break;
            }
        }

        final int[] selected = {currentIndex};

        new AlertDialog.Builder(this)
            .setTitle("Search Engine")
            .setSingleChoiceItems(engines, currentIndex,
                (dialog, which) -> selected[0] = which
            )
            .setPositiveButton("Apply", (dialog, which) -> {
                savePref(
                    Constants.PREF_SEARCH_ENGINE,
                    urls[selected[0]]
                );
                updateSearchEngineLabel();
                showToast("Search engine: " + engines[selected[0]]);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * Shows home page editor dialog.
     */
    private void showHomePageDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(
            prefs.getString(Constants.PREF_HOME_URL, Constants.DEFAULT_HOME_URL)
        );
        input.setInputType(
            android.text.InputType.TYPE_TEXT_VARIATION_URI
        );
        input.setPadding(40, 20, 40, 20);

        new AlertDialog.Builder(this)
            .setTitle("Home Page")
            .setMessage("Enter home page URL:")
            .setView(input)
            .setPositiveButton("Save", (dialog, which) -> {
                String url = input.getText().toString().trim();
                if (!url.isEmpty()) {
                    if (!url.startsWith("http")) url = "https://" + url;
                    savePref(Constants.PREF_HOME_URL, url);
                    if (tvHomePage != null) tvHomePage.setText(url);
                    showToast("Home page updated");
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * Updates search engine label text.
     */
    private void updateSearchEngineLabel() {
        if (tvSearchEngine == null) return;

        String current = prefs.getString(
            Constants.PREF_SEARCH_ENGINE,
            Constants.SEARCH_DUCKDUCKGO
        );

        if (current.contains("duckduckgo"))       tvSearchEngine.setText("DuckDuckGo");
        else if (current.contains("google"))      tvSearchEngine.setText("Google");
        else if (current.contains("brave"))       tvSearchEngine.setText("Brave Search");
        else if (current.contains("startpage"))   tvSearchEngine.setText("Startpage");
        else                                       tvSearchEngine.setText("Custom");
    }

    // ═════════════════════════════════════════════
    // DATA MANAGEMENT SECTION
    // ═════════════════════════════════════════════

    private void setupDataSection() {

        // ── Clear History ──
        if (rowClearHistory != null) {
            rowClearHistory.setOnClickListener(v ->
                showConfirmDialog(
                    "Clear History",
                    "Delete all browsing history?",
                    () -> {
                        historyManager.clearAllHistory();
                        refreshStats();
                        showToast("✅ History cleared");
                    }
                )
            );
        }

        // ── Clear Cookies ──
        if (rowClearCookies != null) {
            rowClearCookies.setOnClickListener(v ->
                showConfirmDialog(
                    "Clear Cookies",
                    "Delete all cookies? You may be logged out of sites.",
                    () -> {
                        android.webkit.CookieManager.getInstance()
                            .removeAllCookies(null);
                        android.webkit.CookieManager.getInstance().flush();
                        showToast("✅ Cookies cleared");
                    }
                )
            );
        }

        // ── Clear Cache ──
        if (rowClearCache != null) {
            rowClearCache.setOnClickListener(v ->
                showConfirmDialog(
                    "Clear Cache",
                    "Delete all cached web content?",
                    () -> {
                        // WebView cache cleared by BrowserEngine
                        // We clear app cache directory here
                        clearAppCache();
                        showToast("✅ Cache cleared");
                    }
                )
            );
        }

        // ── Clear ALL Data ──
        if (rowClearAll != null) {
            rowClearAll.setOnClickListener(v ->
                showConfirmDialog(
                    "Clear All Data",
                    "⚠️ This will delete ALL browsing data:\n\n"
                    + "• History\n• Cookies\n• Cache\n"
                    + "• Saved passwords\n• Site data\n\n"
                    + "This cannot be undone.",
                    () -> {
                        clearAllData();
                        showToast("✅ All data cleared");
                    }
                )
            );
        }
    }

    /**
     * Clears app cache directory.
     */
    private void clearAppCache() {
        try {
            java.io.File cacheDir = getCacheDir();
            deleteDir(cacheDir);
        } catch (Exception e) {
            android.util.Log.e(TAG, "Cache clear failed: " + e.getMessage());
        }
    }

    /**
     * Recursively deletes a directory.
     */
    private boolean deleteDir(java.io.File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    boolean success = deleteDir(
                        new java.io.File(dir, child)
                    );
                    if (!success) return false;
                }
            }
        }
        return dir != null && dir.delete();
    }

    /**
     * Clears all browsing data completely.
     */
    private void clearAllData() {
        // History
        historyManager.clearAllHistory();

        // Cookies
        android.webkit.CookieManager.getInstance().removeAllCookies(null);
        android.webkit.CookieManager.getInstance().flush();

        // Cache
        clearAppCache();

        // WebView data
        android.webkit.WebStorage.getInstance().deleteAllData();

        // Refresh stats
        refreshStats();

        android.util.Log.d(TAG, "All browsing data cleared");
    }

    // ═════════════════════════════════════════════
    // STATS SECTION
    // ═════════════════════════════════════════════

    /**
     * Refreshes all stats values.
     * Runs DB queries in background thread.
     */
    private void refreshStats() {
        executor.execute(() -> {

            // AdBlock stats
            java.util.Map<String, String> adStats =
                adBlockEngine.getStatsMap();
            int blocked = adBlockEngine.getTotalBlocked();

            // History count
            int histCount = historyManager.getHistoryCount();

            // Connection type
            String connType = NetworkUtils.getConnectionType(this);

            uiHandler.post(() -> {
                if (tvAdsBlockedCount != null) {
                    tvAdsBlockedCount.setText(
                        blocked + " ads blocked"
                    );
                }
                if (tvHistoryEntries != null) {
                    tvHistoryEntries.setText(
                        histCount + " entries"
                    );
                }
                if (tvHistoryCount != null) {
                    tvHistoryCount.setText(
                        histCount + " items"
                    );
                }
                if (tvConnectionType != null) {
                    tvConnectionType.setText(connType);
                }
                if (tvAdBlockStats != null) {
                    tvAdBlockStats.setText(
                        "Rules: " + adStats.get("domain_rules")
                        + " | Blocked: " + adStats.get("blocked")
                    );
                }
            });
        });
    }

    // ═════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════

    /**
     * Saves a boolean preference.
     */
    private void savePref(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    /**
     * Saves a String preference.
     */
    private void savePref(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    /**
     * Sets a Switch without triggering its listener.
     * Prevents recursive pref saves on load.
     */
    private void setSwitchSilently(Switch sw, boolean value) {
        if (sw == null) return;
        CompoundButton.OnCheckedChangeListener listener =
            (CompoundButton.OnCheckedChangeListener)
            sw.getTag();
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener(listener);
    }

    /**
     * Shows a confirmation dialog before destructive actions.
     */
    private void showConfirmDialog(
            String title, String message, Runnable onConfirm) {

        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Confirm", (d, w) -> onConfirm.run())
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * Shows a short Toast on UI thread.
     */
    private void showToast(String message) {
        uiHandler.post(() ->
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        );
    }
          }
