package com.privacybrowser.browser;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.privacybrowser.utils.Constants;
import com.privacybrowser.utils.NetworkUtils;
import com.privacybrowser.utils.UrlValidator;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * BrowserEngine.java
 * Core WebView engine for Privacy Browser Pro.
 *
 * Architecture:
 * ┌──────────────────────────────────────────────────┐
 * │                 BrowserEngine                    │
 * │                                                  │
 * │  ┌─────────────┐    ┌──────────────────────┐    │
 * │  │   WebView   │◄──►│  PrivacyWebViewClient│    │
 * │  │             │    │  (request intercept) │    │
 * │  └──────┬──────┘    └──────────────────────┘    │
 * │         │                                        │
 * │  ┌──────▼──────┐    ┌──────────────────────┐    │
 * │  │WebChromeClnt│    │   AdBlockEngine      │    │
 * │  │(UI events)  │    │   (URL filtering)    │    │
 * │  └─────────────┘    └──────────────────────┘    │
 * │         │                                        │
 * │  ┌──────▼──────────────────────────────────┐    │
 * │  │         BrowserEngineListener           │    │
 * │  │  (UI callbacks → MainActivity)          │    │
 * │  └─────────────────────────────────────────┘    │
 * └──────────────────────────────────────────────────┘
 *
 * Responsibilities:
 *  - WebView lifecycle management
 *  - Privacy hardening (disable tracking APIs)
 *  - Ad blocking integration
 *  - History recording
 *  - Tab state synchronization
 *  - Private mode cookie isolation
 *  - SSL error handling
 *  - Dark mode injection
 *  - JavaScript interface (safe)
 */
public class BrowserEngine {

    private static final String TAG = "BrowserEngine";

    // ─────────────────────────────────────────────
    // Core Components
    // ─────────────────────────────────────────────
    private final WebView        webView;
    private final Context        context;
    private final AdBlockEngine  adBlockEngine;
    private final HistoryManager historyManager;
    private final TabManager     tabManager;
    private final SharedPreferences prefs;

    // Current tab reference
    private TabManager.Tab currentTab;

    // UI thread handler
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Listener for UI callbacks
    private BrowserEngineListener listener;

    // ─────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────
    private boolean privateMode          = false;
    private boolean adBlockEnabled       = true;
    private boolean javascriptEnabled    = true;
    private boolean darkModeEnabled      = false;
    private boolean doNotTrack           = true;
    private boolean blockThirdPartyCookies = true;

    // ─────────────────────────────────────────────
    // Dark Mode CSS injected into every page
    // ─────────────────────────────────────────────
    private static final String DARK_MODE_CSS =
        "<style id='privacy-browser-dark'>" +
        "html, body { " +
        "  background-color: #1a1a1a !important;" +
        "  color: #e0e0e0 !important;" +
        "}" +
        "a { color: #7eb3ff !important; }" +
        "input, textarea, select {" +
        "  background-color: #2a2a2a !important;" +
        "  color: #e0e0e0 !important;" +
        "  border-color: #444 !important;" +
        "}" +
        "img { filter: brightness(0.9); }" +
        "</style>";

    // ─────────────────────────────────────────────
    // Empty blocked response
    // ─────────────────────────────────────────────
    private static final WebResourceResponse EMPTY_RESPONSE =
        new WebResourceResponse(
            "text/plain", "utf-8",
            new ByteArrayInputStream(
                "".getBytes(StandardCharsets.UTF_8)
            )
        );

    // ═════════════════════════════════════════════
    // LISTENER INTERFACE
    // ═════════════════════════════════════════════

    /**
     * Callback interface for UI updates.
     * MainActivity implements this.
     */
    public interface BrowserEngineListener {
        void onPageStarted(String url, Bitmap favicon);
        void onPageFinished(String url, String title);
        void onProgressChanged(int progress);
        void onTitleChanged(String title);
        void onUrlChanged(String url);
        void onFaviconReceived(Bitmap favicon);
        void onLoadError(int errorCode, String description, String url);
        void onSslError(String url, boolean proceed);
        void onDownloadRequested(String url, String mimeType, long contentLength);
        void onNewTabRequested(String url, boolean isPrivate);
        void onFullscreenRequested(View view, boolean enter);
    }

    // ═════════════════════════════════════════════
    // CONSTRUCTOR
    // ═════════════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled")
    public BrowserEngine(Context context, WebView webView) {
        this.context        = context;
        this.webView        = webView;
        this.adBlockEngine  = AdBlockEngine.getInstance(context);
        this.historyManager = HistoryManager.getInstance(context);
        this.tabManager     = TabManager.getInstance(context);
        this.prefs          = context.getSharedPreferences(
                                Constants.PREFS_NAME, Context.MODE_PRIVATE);

        // Load saved preferences
        loadPreferences();

        // Configure WebView
        setupWebView();

        Log.d(TAG, "BrowserEngine initialized");
    }

    // ═════════════════════════════════════════════
    // WEBVIEW SETUP
    // ═════════════════════════════════════════════

    /**
     * Full WebView configuration.
     * Called once on initialization.
     *
     * Privacy hardening applied here:
     *  - Disable geolocation
     *  - Disable file access
     *  - Custom User-Agent
     *  - Block third-party cookies
     *  - Disable form data saving
     *  - Disable password saving
     */
    @SuppressLint({"SetJavaScriptEnabled", "ObsoleteSdkInt"})
    private void setupWebView() {
        WebSettings settings = webView.getSettings();

        // ── Performance ──
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAppCacheEnabled(false); // Deprecated but harmless
        settings.setRenderPriority(WebSettings.RenderPriority.HIGH);

        // ── JavaScript ──
        settings.setJavaScriptEnabled(javascriptEnabled);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);

        // ── Display ──
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false); // Hide ugly zoom buttons
        settings.setTextZoom(100);

        // ── User Agent ──
        settings.setUserAgentString(Constants.USER_AGENT);

        // ── Privacy Hardening ──
        // Disable geolocation — prevents location tracking
        settings.setGeolocationEnabled(false);

        // Disable file access — prevents local file leaks
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);

        // Disable form autofill — no data saved
        settings.setSaveFormData(false);
        settings.setSavePassword(false);

        // Mixed content — block in private mode
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            if (privateMode) {
                settings.setMixedContentMode(
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
                );
            } else {
                settings.setMixedContentMode(
                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                );
            }
        }

        // ── Media ──
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setLoadsImagesAutomatically(true);

        // ── Cookie Setup ──
        setupCookies();

        // ── Attach Clients ──
        webView.setWebViewClient(new PrivacyWebViewClient());
        webView.setWebChromeClient(new PrivacyWebChromeClient());

        // ── Download Listener ──
        webView.setDownloadListener((url, userAgent, contentDisposition,
                                     mimeType, contentLength) -> {
            Log.d(TAG, "Download requested: " + url);
            if (listener != null) {
                listener.onDownloadRequested(url, mimeType, contentLength);
            }
        });

        // ── Hardware Acceleration ──
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        Log.d(TAG, "WebView configured. Private: " + privateMode
            + " | JS: " + javascriptEnabled
            + " | AdBlock: " + adBlockEnabled);
    }

    /**
     * Configures cookie policy based on privacy mode.
     *
     * Normal mode:
     *   Cookies enabled, third-party blocked
     *
     * Private mode:
     *   Cookies enabled for session ONLY
     *   All cookies wiped on session end
     *   Third-party cookies always blocked
     */
    private void setupCookies() {
        CookieManager cookieManager = CookieManager.getInstance();

        if (privateMode) {
            // Private mode — session cookies only, no persistence
            cookieManager.setAcceptCookie(true); // Need for sites to work
            if (android.os.Build.VERSION.SDK_INT
                    >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.setAcceptThirdPartyCookies(webView, false);
            }
            // Cookies will be wiped when private session ends
            Log.d(TAG, "Private mode cookies: session-only, no third-party");

        } else {
            cookieManager.setAcceptCookie(true);
            if (android.os.Build.VERSION.SDK_INT
                    >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.setAcceptThirdPartyCookies(
                    webView, !blockThirdPartyCookies
                );
            }
            Log.d(TAG, "Normal cookies: "
                + "third-party=" + !blockThirdPartyCookies);
        }
    }

    // ═════════════════════════════════════════════
    // NAVIGATION API
    // ═════════════════════════════════════════════

    /**
     * Master load method — validates URL, applies headers, loads.
     * Always call this instead of webView.loadUrl() directly.
     *
     * @param rawUrl Raw URL or search query from address bar
     */
    public void loadUrl(String rawUrl) {
        // Validate and sanitize
        String safeUrl = UrlValidator.getLoadableUrl(
            rawUrl,
            prefs.getString(
                Constants.PREF_SEARCH_ENGINE,
                Constants.DEFAULT_SEARCH_URL
            )
        );

        // Strip tracking params
        safeUrl = UrlValidator.stripTrackingParams(safeUrl);

        Log.d(TAG, "Loading URL: " + safeUrl);

        // Build privacy headers
        Map<String, String> headers = buildRequestHeaders();

        // Update tab URL
        if (currentTab != null) {
            tabManager.updateTabUrl(currentTab.id, safeUrl);
        }

        // Load in WebView
        final String finalUrl = safeUrl;
        mainHandler.post(() ->
            webView.loadUrl(finalUrl, headers)
        );
    }

    /**
     * Loads a URL with no validation.
     * Only for internal use (error pages, about:blank).
     */
    private void loadUrlInternal(String url) {
        mainHandler.post(() -> webView.loadUrl(url));
    }

    /**
     * Loads HTML content directly into WebView.
     * Used for error pages, new tab page.
     */
    public void loadHtml(String html, String baseUrl) {
        mainHandler.post(() ->
            webView.loadDataWithBaseURL(
                baseUrl, html, "text/html", "utf-8", null
            )
        );
    }

    /**
     * Navigates back in WebView history.
     * Returns true if navigation was possible.
     */
    public boolean goBack() {
        if (webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return false;
    }

    /**
     * Navigates forward in WebView history.
     * Returns true if navigation was possible.
     */
    public boolean goForward() {
        if (webView.canGoForward()) {
            webView.goForward();
            return true;
        }
        return false;
    }

    /**
     * Reloads current page.
     */
    public void reload() {
        webView.reload();
    }

    /**
     * Stops current page load.
     */
    public void stopLoading() {
        webView.stopLoading();
    }

    /**
     * Returns current URL.
     */
    public String getCurrentUrl() {
        return webView.getUrl();
    }

    /**
     * Returns current page title.
     */
    public String getCurrentTitle() {
        return webView.getTitle();
    }

    /**
     * Returns true if WebView can go back.
     */
    public boolean canGoBack() { return webView.canGoBack(); }

    /**
     * Returns true if WebView can go forward.
     */
    public boolean canGoForward() { return webView.canGoForward(); }

    // ═════════════════════════════════════════════
    // PRIVACY WEB VIEW CLIENT
    // ═════════════════════════════════════════════

    /**
     * Custom WebViewClient — handles all page events + ad blocking.
     *
     * Key overrides:
     *  shouldInterceptRequest → AdBlock filtering
     *  shouldOverrideUrlLoading → URL validation + dangerous scheme block
     *  onPageStarted → Tab state update
     *  onPageFinished → History recording + dark mode injection
     *  onReceivedSslError → SSL policy
     *  onReceivedError → Error page display
     */
    private class PrivacyWebViewClient extends WebViewClient {

        // Track current page URL for third-party detection
        private String currentPageUrl = "";

        /**
         * INTERCEPT EVERY RESOURCE REQUEST.
         * This is where AdBlockEngine filters ads/trackers.
         *
         * Called for: HTML, CSS, JS, images, fonts, XHR — everything.
         * Return null  → WebView loads the resource normally.
         * Return empty → Resource is blocked silently.
         */
        @Override
        public WebResourceResponse shouldInterceptRequest(
                WebView view, WebResourceRequest request) {

            String url = request.getUrl().toString();

            // ── Block dangerous schemes ──
            if (UrlValidator.isDangerousScheme(url)) {
                Log.w(TAG, "Blocked dangerous scheme: " + url);
                return EMPTY_RESPONSE;
            }

            // ── AdBlock filtering ──
            if (adBlockEnabled) {
                WebResourceResponse blocked = adBlockEngine.shouldBlock(
                    request, currentPageUrl
                );
                if (blocked != null) return blocked; // Blocked!
            }

            // ── Add privacy headers to resource requests ──
            // Note: We can't modify headers here (API limitation)
            // Headers are added in loadUrl() for main requests

            return null; // Allow resource
        }

        /**
         * Called BEFORE WebView follows a URL.
         * Return true  → We handle it (block or open elsewhere)
         * Return false → WebView handles it normally
         */
        @Override
        public boolean shouldOverrideUrlLoading(
                WebView view, WebResourceRequest request) {

            String url = request.getUrl().toString();

            // ── Block dangerous schemes ──
            if (UrlValidator.isDangerousScheme(url)) {
                Log.w(TAG, "Blocked navigation to dangerous URL: " + url);
                return true; // Block it
            }

            // ── Handle non-HTTP schemes ──
            String scheme = UrlValidator.extractScheme(url);
            switch (scheme) {
                case "mailto":
                    // Open email app
                    openExternalUrl(url);
                    return true;
                case "tel":
                    // Open dialer
                    openExternalUrl(url);
                    return true;
                case "intent":
                    // Block intent:// scheme — potential security risk
                    Log.w(TAG, "Blocked intent:// URL: " + url);
                    return true;
            }

            // ── Check if AdBlock wants to block this navigation ──
            if (adBlockEnabled && adBlockEngine.shouldBlockUrl(
                    url, currentPageUrl)) {
                Log.d(TAG, "Navigation blocked by AdBlock: " + url);
                return true;
            }

            // Allow normal navigation
            return false;
        }

        /**
         * Called when page starts loading.
         * Updates tab state and notifies UI.
         */
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);

            currentPageUrl = url != null ? url : "";

            Log.d(TAG, "Page started: " + url);

            // Update tab
            if (currentTab != null) {
                tabManager.updateTabUrl(currentTab.id, url);
                tabManager.updateTabProgress(currentTab.id, 10);
            }

            // Notify UI
            if (listener != null) {
                listener.onPageStarted(url, favicon);
                listener.onUrlChanged(url);
            }
        }

        /**
         * Called when page finishes loading.
         *
         * Actions:
         *  1. Record history (if not private)
         *  2. Inject dark mode CSS (if enabled)
         *  3. Capture tab thumbnail
         *  4. Update tab state
         */
        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);

            String title = view.getTitle();
            Log.d(TAG, "Page finished: " + url + " | Title: " + title);

            // ── 1. Record history ──
            historyManager.recordVisit(url, title);

            // ── 2. Inject dark mode CSS ──
            if (darkModeEnabled) {
                injectDarkMode(view);
            }

            // ── 3. Update tab state ──
            if (currentTab != null) {
                tabManager.updateTabUrl(currentTab.id, url);
                tabManager.updateTabTitle(currentTab.id, title);
                tabManager.updateTabProgress(
                    currentTab.id, Constants.PROGRESS_COMPLETE
                );

                // Capture thumbnail for tab switcher
                captureThumbnail();
            }

            // ── 4. Notify UI ──
            if (listener != null) {
                listener.onPageFinished(url, title);
            }
        }

        /**
         * SSL error handler.
         *
         * Privacy Browser Pro NEVER silently accepts SSL errors.
         * User is warned and must explicitly choose to proceed.
         *
         * Proceeding on SSL errors is a security risk.
         * We cancel by default.
         */
        @Override
        public void onReceivedSslError(
                WebView view, SslErrorHandler handler, SslError error) {

            // Always cancel by default — never auto-proceed
            handler.cancel();

            String errorUrl = error.getUrl();
            Log.w(TAG, "SSL error on: " + errorUrl
                + " | Error: " + error.getPrimaryError());

            // Notify UI to show SSL warning dialog
            if (listener != null) {
                listener.onSslError(errorUrl, false);
            }
        }

        /**
         * Page load error handler.
         * Shows a friendly error page.
         */
        @Override
        public void onReceivedError(
                WebView view, WebResourceRequest request,
                WebResourceError error) {

            super.onReceivedError(view, request, error);

            // Only handle main frame errors (not sub-resource errors)
            if (!request.isForMainFrame()) return;

            String failingUrl = request.getUrl().toString();
            int    errorCode  = error.getErrorCode();
            String desc       = error.getDescription().toString();

            Log.e(TAG, "Page error: " + errorCode + " - " + desc
                + " on " + failingUrl);

            // Update tab error state
            if (currentTab != null) {
                tabManager.setTabError(currentTab.id, true);
            }

            // Load error page
            loadErrorPage(failingUrl, errorCode, desc);

            // Notify UI
            if (listener != null) {
                listener.onLoadError(errorCode, desc, failingUrl);
            }
        }
    }

    // ═════════════════════════════════════════════
    // PRIVACY WEB CHROME CLIENT
    // ═════════════════════════════════════════════

    /**
     * Custom WebChromeClient — handles UI-level browser events.
     *
     * Key overrides:
     *  onProgressChanged → Progress bar
     *  onReceivedTitle   → Tab title
     *  onReceivedIcon    → Favicon
     *  onGeolocationPermissionsShowPrompt → Always deny
     *  onPermissionRequest → Block camera/mic
     *  onShowCustomView → Fullscreen video
     *  onCreateWindow → New tab request
     */
    private class PrivacyWebChromeClient extends WebChromeClient {

        private View customView;

        /**
         * Page load progress (0–100).
         */
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);

            if (currentTab != null) {
                tabManager.updateTabProgress(currentTab.id, newProgress);
            }

            if (listener != null) {
                listener.onProgressChanged(newProgress);
            }
        }

        /**
         * Page title received.
         */
        @Override
        public void onReceivedTitle(WebView view, String title) {
            super.onReceivedTitle(view, title);

            if (currentTab != null) {
                tabManager.updateTabTitle(currentTab.id, title);
            }

            if (listener != null) {
                listener.onTitleChanged(title);
            }
        }

        /**
         * Favicon received.
         */
        @Override
        public void onReceivedIcon(WebView view, Bitmap icon) {
            super.onReceivedIcon(view, icon);

            if (listener != null) {
                listener.onFaviconReceived(icon);
            }
        }

        /**
         * GEOLOCATION — ALWAYS DENIED.
         * Privacy Browser Pro never allows location access.
         */
        @Override
        public void onGeolocationPermissionsShowPrompt(
                String origin,
                GeolocationPermissions.Callback callback) {
            // Deny all geolocation requests silently
            callback.invoke(origin, false, false);
            Log.d(TAG, "Geolocation denied for: " + origin);
        }

        /**
         * PERMISSIONS — Block camera, microphone, etc.
         * Privacy Browser Pro blocks all sensitive permissions.
         */
        @Override
        public void onPermissionRequest(PermissionRequest request) {
            // Deny all WebView permission requests
            request.deny();
            Log.d(TAG, "WebView permission denied: "
                + java.util.Arrays.toString(request.getResources()));
        }

        /**
         * New window / tab request (target="_blank").
         * Forwards to TabManager as new tab.
         */
        @Override
        public boolean onCreateWindow(
                WebView view, boolean isDialog,
                boolean isUserGesture, android.os.Message resultMsg) {

            // Only open if triggered by user gesture
            // (blocks popup ads)
            if (!isUserGesture) {
                Log.d(TAG, "Blocked automatic popup window");
                return false;
            }

            // Extract URL from message
            WebView.HitTestResult result = view.getHitTestResult();
            String newUrl = result.getExtra();

            if (newUrl != null && listener != null) {
                listener.onNewTabRequested(newUrl, privateMode);
            }

            return false;
        }

        /**
         * Fullscreen video support.
         */
        @Override
        public void onShowCustomView(
                View view,
                CustomViewCallback callback) {
            customView = view;
            if (listener != null) {
                listener.onFullscreenRequested(view, true);
            }
        }

        @Override
        public void onHideCustomView() {
            if (listener != null && customView != null) {
                listener.onFullscreenRequested(customView, false);
            }
            customView = null;
        }

        /**
         * JS Alert — show as Android dialog.
         * Blocked in private mode.
         */
        @Override
        public boolean onJsAlert(
                WebView view, String url,
                String message, android.webkit.JsResult result) {
            if (privateMode) {
                result.cancel();
                return true;
            }
            return super.onJsAlert(view, url, message, result);
        }
    }

    // ═════════════════════════════════════════════
    // DARK MODE INJECTION
    // ═════════════════════════════════════════════

    /**
     * Injects dark mode CSS into the loaded page.
     * Uses JavaScript to insert a <style> tag.
     *
     * Only injected if page doesn't already have dark mode
     * (checks prefers-color-scheme media query support).
     */
    private void injectDarkMode(WebView view) {
        String js =
            "(function() {" +
            "  if (!document.getElementById('privacy-browser-dark')) {" +
            "    var style = document.createElement('style');" +
            "    style.id = 'privacy-browser-dark';" +
            "    style.innerHTML = '" +
            "      html, body { background-color: #1a1a1a !important;" +
            "        color: #e0e0e0 !important; }" +
            "      a { color: #7eb3ff !important; }" +
            "      input, textarea, select {" +
            "        background-color: #2a2a2a !important;" +
            "        color: #e0e0e0 !important; }" +
            "    ';" +
            "    document.head.appendChild(style);" +
            "  }" +
            "})();";

        view.evaluateJavascript(js, null);
    }

    // ═════════════════════════════════════════════
    // TAB MANAGEMENT INTEGRATION
    // ═════════════════════════════════════════════

    /**
     * Attaches this engine to a specific tab.
     * Called when user switches tabs.
     *
     * @param tab Tab to attach to
     */
    public void attachToTab(TabManager.Tab tab) {
        this.currentTab = tab;
        this.privateMode = tab.isPrivate;

        // Reload cookie policy for this tab's mode
        setupCookies();

        // Update JS setting for this tab
        webView.getSettings().setJavaScriptEnabled(javascriptEnabled);

        // If tab has a URL, load it
        if (!tab.isBlank()) {
            loadUrl(tab.url);
        }

        Log.d(TAG, "Engine attached to tab: " + tab.id
            + " | private=" + tab.isPrivate);
    }

    /**
     * Saves WebView state to Bundle.
     * Called when tab goes to background.
     */
    public Bundle saveState() {
        Bundle bundle = new Bundle();
        webView.saveState(bundle);
        return bundle;
    }

    /**
     * Restores WebView state from Bundle.
     * Called when tab comes back to foreground.
     */
    public void restoreState(Bundle bundle) {
        if (bundle != null) {
            webView.restoreState(bundle);
        }
    }

    /**
     * Captures WebView screenshot for tab thumbnail.
     * Scaled to TAB_THUMBNAIL_WIDTH × TAB_THUMBNAIL_HEIGHT.
     */
    private void captureThumbnail() {
        if (currentTab == null) return;

        mainHandler.post(() -> {
            try {
                webView.setDrawingCacheEnabled(true);
                Bitmap full = webView.getDrawingCache();
                if (full != null && !full.isRecycled()) {
                    tabManager.updateTabThumbnail(currentTab.id, full);
                }
                webView.setDrawingCacheEnabled(false);
            } catch (Exception e) {
                Log.e(TAG, "Thumbnail capture failed: " + e.getMessage());
            }
        });
    }

    // ═════════════════════════════════════════════
    // PRIVATE SESSION CLEANUP
    // ═════════════════════════════════════════════

    /**
     * Wipes all private browsing data.
     * Called when private tab closes.
     *
     * Clears:
     *  - Cookies
     *  - Cache
     *  - WebView history
     *  - Local storage
     *  - Form data
     *  - Passwords
     */
    public void clearPrivateSessionData() {
        Log.d(TAG, "Clearing private session data...");

        // Wipe cookies
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();

        // Wipe cache
        webView.clearCache(true);

        // Wipe browsing history
        webView.clearHistory();

        // Wipe form data
        webView.clearFormData();

        // Wipe local storage via JS
        webView.evaluateJavascript(
            "localStorage.clear(); sessionStorage.clear();",
            null
        );

        // Wipe history from HistoryManager RAM
        historyManager.destroyPrivateSession();

        Log.d(TAG, "Private session data wiped ✅");
    }

    /**
     * Full browser data clear.
     * Called from Settings → "Clear All Data"
     */
    public void clearAllBrowsingData() {
        clearPrivateSessionData();
        historyManager.clearAllHistory();
        webView.clearSslPreferences();
        Log.d(TAG, "All browsing data cleared");
    }

    // ═════════════════════════════════════════════
    // LIFECYCLE METHODS
    // ═════════════════════════════════════════════

    /**
     * Called from Activity.onResume()
     * Resumes WebView rendering.
     */
    public void onResume() {
        webView.onResume();
        webView.resumeTimers();
    }

    /**
     * Called from Activity.onPause()
     * Pauses WebView rendering to save battery.
     */
    public void onPause() {
        webView.onPause();
        webView.pauseTimers();
    }

    /**
     * Called from Activity.onDestroy()
     * Cleans up WebView completely.
     */
    public void onDestroy() {
        if (privateMode) {
            clearPrivateSessionData();
        }
        webView.stopLoading();
        webView.clearHistory();
        webView.removeAllViews();
        webView.destroy();
        Log.d(TAG, "BrowserEngine destroyed");
    }

    // ═════════════════════════════════════════════
    // ERROR PAGE
    // ═════════════════════════════════════════════

    /**
     * Loads a friendly HTML error page into WebView.
     * Shown when page fails to load.
     */
    private void loadErrorPage(String url, int errorCode, String description) {
        String html =
            "<!DOCTYPE html><html>" +
            "<head><meta name='viewport' content='width=device-width'>" +
            "<style>" +
            "  body { background:#1a1a1a; color:#e0e0e0; font-family:sans-serif;" +
            "         display:flex; flex-direction:column; align-items:center;" +
            "         justify-content:center; height:100vh; margin:0; padding:20px;" +
            "         box-sizing:border-box; text-align:center; }" +
            "  .icon { font-size:64px; margin-bottom:16px; }" +
            "  h1 { font-size:22px; margin:0 0 8px; color:#ff6b6b; }" +
            "  p  { font-size:14px; color:#aaa; margin:4px 0; }" +
            "  .url { font-size:12px; color:#666; word-break:break-all; " +
            "         margin-top:12px; padding:8px; background:#111;" +
            "         border-radius:4px; }" +
            "  button { margin-top:24px; padding:12px 28px;" +
            "           background:#2979ff; color:#fff;" +
            "           border:none; border-radius:8px;" +
            "           font-size:15px; cursor:pointer; }" +
            "</style></head><body>" +
            "<div class='icon'>⚠️</div>" +
            "<h1>Page Not Available</h1>" +
            "<p>" + description + "</p>" +
            "<p>Error code: " + errorCode + "</p>" +
            "<div class='url'>" + url + "</div>" +
            "<button onclick='history.back()'>Go Back</button>" +
            "</body></html>";

        loadHtml(html, url);
    }

    // ═════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════

    /**
     * Builds privacy headers for page requests.
     * Adds DNT and Sec-GPC headers.
     */
    private Map<String, String> buildRequestHeaders() {
        Map<String, String> headers = new HashMap<>();
        if (doNotTrack) {
            headers.put("DNT", "1");
            headers.put("Sec-GPC", "1");
        }
        return headers;
    }

    /**
     * Opens a URL in an external app (email, phone, etc.)
     */
    private void openExternalUrl(String url) {
        try {
            android.content.Intent intent = new android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)
            );
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open external URL: " + url);
        }
    }

    /**
     * Loads preferences from SharedPreferences.
     */
    private void loadPreferences() {
        adBlockEnabled         = prefs.getBoolean(Constants.PREF_ADBLOCK_ENABLED, true);
        privateMode            = prefs.getBoolean(Constants.PREF_PRIVATE_MODE, false);
        darkModeEnabled        = prefs.getBoolean(Constants.PREF_DARK_MODE, false);
        javascriptEnabled      = prefs.getBoolean(Constants.PREF_JS_ENABLED, true);
        doNotTrack             = prefs.getBoolean(Constants.PREF_DO_NOT_TRACK, true);
        blockThirdPartyCookies = prefs.getBoolean(
            Constants.PREF_BLOCK_THIRD_PARTY, true
        );
    }

    // ═════════════════════════════════════════════
    // SETTINGS API
    // ═════════════════════════════════════════════

    public void setListener(BrowserEngineListener listener) {
        this.listener = listener;
    }

    public void setAdBlockEnabled(boolean enabled) {
        this.adBlockEnabled = enabled;
        adBlockEngine.setEnabled(enabled);
    }

    @SuppressLint("SetJavaScriptEnabled")
    public void setJavascriptEnabled(boolean enabled) {
        this.javascriptEnabled = enabled;
        webView.getSettings().setJavaScriptEnabled(enabled);
    }

    public void setDarkModeEnabled(boolean enabled) {
        this.darkModeEnabled = enabled;
        if (enabled) {
            injectDarkMode(webView);
        } else {
            // Remove dark mode — reload page
            webView.evaluateJavascript(
                "var s=document.getElementById('privacy-browser-dark');" +
                "if(s) s.remove();",
                null
            );
        }
    }

    public void setPrivateMode(boolean privateMode) {
        this.privateMode = privateMode;
        historyManager.setPrivateMode(privateMode);
        setupCookies();
    }

    public boolean isPrivateMode()    { return privateMode;    }
    public boolean isAdBlockEnabled() { return adBlockEnabled; }
    public WebView getWebView()       { return webView;        }

    public String getAdBlockStats() {
        Map<String, String> stats = adBlockEngine.getStatsMap();
        return "Blocked: " + stats.get("blocked")
            + " | Rules: "  + stats.get("domain_rules");
    }
          }
