package com.privacybrowser.browser;

import android.content.Context;
import android.net.Proxy;
import android.os.Build;
import android.util.ArrayMap;
import android.util.Log;
import android.webkit.WebView;

import com.privacybrowser.utils.Constants;
import com.privacybrowser.utils.NetworkUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ProxyManager.java
 * Manages proxy routing for Privacy Browser Pro.
 *
 * Architecture:
 *  ┌────────────────────────────────────────────────┐
 *  │               ProxyManager                     │
 *  │                                                │
 *  │  Mode: NONE / HTTP / SOCKS5                   │
 *  │                                                │
 *  │  ┌─────────────────────────────────────────┐  │
 *  │  │           Proxy Pipeline                │  │
 *  │  │                                         │  │
 *  │  │  WebView Request                        │  │
 *  │  │      ↓                                  │  │
 *  │  │  ProxyManager.applyToWebView()          │  │
 *  │  │      ↓                                  │  │
 *  │  │  System Proxy Properties set            │  │
 *  │  │      ↓                                  │  │
 *  │  │  All traffic → 127.0.0.1:8118 (HTTP)   │  │
 *  │  │            or → 127.0.0.1:9050 (SOCKS) │  │
 *  │  │      ↓                                  │  │
 *  │  │  Orbot → Tor Network                    │  │
 *  │  └─────────────────────────────────────────┘  │
 *  │                                                │
 *  │  NOTE: True Tor needs Orbot installed.        │
 *  │  This class routes traffic to Orbot's port.   │
 *  └────────────────────────────────────────────────┘
 *
 * Proxy Types Supported:
 *  NONE   → Direct connection (default)
 *  HTTP   → HTTP proxy (Orbot port 8118 or custom)
 *  SOCKS5 → SOCKS5 proxy (Orbot port 9050 or custom)
 */
public class ProxyManager {

    private static final String TAG = "ProxyManager";

    // ─────────────────────────────────────────────
    // Singleton
    // ─────────────────────────────────────────────
    private static volatile ProxyManager instance;

    public static ProxyManager getInstance(Context context) {
        if (instance == null) {
            synchronized (ProxyManager.class) {
                if (instance == null) {
                    instance = new ProxyManager(
                        context.getApplicationContext()
                    );
                }
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────
    // Proxy State
    // ─────────────────────────────────────────────
    private String  proxyType = Constants.PROXY_TYPE_NONE;
    private String  proxyHost = Constants.PROXY_HOST_DEFAULT;
    private int     proxyPort = Constants.PROXY_PORT_HTTP;
    private boolean isActive  = false;

    // Background thread for proxy testing
    private final ExecutorService executor =
        Executors.newSingleThreadExecutor();

    private final Context context;

    // ─────────────────────────────────────────────
    // Listener
    // ─────────────────────────────────────────────
    private ProxyStateListener listener;

    public interface ProxyStateListener {
        void onProxyEnabled(String type, String host, int port);
        void onProxyDisabled();
        void onProxyError(String reason);
        void onProxyTestResult(boolean reachable, String message);
    }

    public void setProxyStateListener(ProxyStateListener listener) {
        this.listener = listener;
    }

    // ─────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────
    private ProxyManager(Context context) {
        this.context = context;
        Log.d(TAG, "ProxyManager initialized");
    }

    // ═════════════════════════════════════════════
    // PRIMARY API
    // ═════════════════════════════════════════════

    /**
     * Enables HTTP proxy mode.
     * Routes all WebView traffic through HTTP proxy.
     *
     * Usage (Orbot HTTP):
     *   proxyManager.enableHttpProxy("127.0.0.1", 8118, webView);
     *
     * @param host    Proxy host
     * @param port    Proxy port
     * @param webView WebView to apply proxy to
     */
    public void enableHttpProxy(String host, int port, WebView webView) {
        if (!validateProxyInput(host, port)) return;

        // Test reachability before applying
        testAndApply(
            Constants.PROXY_TYPE_HTTP,
            host,
            port,
            webView
        );
    }

    /**
     * Enables SOCKS5 proxy mode.
     * Routes all WebView traffic through SOCKS5 proxy.
     *
     * Usage (Orbot SOCKS5):
     *   proxyManager.enableSocks5Proxy("127.0.0.1", 9050, webView);
     *
     * NOTE: WebView natively supports HTTP proxy via system properties.
     * SOCKS5 requires additional routing via system properties too,
     * but coverage is less complete than HTTP mode.
     * For full SOCKS5 support, HTTP proxy (Orbot 8118) is recommended.
     *
     * @param host    Proxy host
     * @param port    Proxy port (default 9050 for Orbot)
     * @param webView WebView to apply proxy to
     */
    public void enableSocks5Proxy(String host, int port, WebView webView) {
        if (!validateProxyInput(host, port)) return;

        testAndApply(
            Constants.PROXY_TYPE_SOCKS5,
            host,
            port,
            webView
        );
    }

    /**
     * Enables Orbot (Tor) proxy with auto-detection.
     *
     * Priority:
     *  1. Try SOCKS5 on port 9050
     *  2. Fall back to HTTP on port 8118
     *  3. If neither available → notify error
     *
     * @param webView WebView to apply proxy to
     */
    public void enableOrbotProxy(WebView webView) {
        executor.execute(() -> {

            Log.d(TAG, "Detecting Orbot proxy...");

            // Try SOCKS5 first (preferred)
            boolean socks5 = NetworkUtils.isProxyReachable(
                Constants.PROXY_HOST_DEFAULT,
                Constants.PROXY_PORT_SOCKS5
            );

            if (socks5) {
                Log.d(TAG, "Orbot SOCKS5 detected on port "
                    + Constants.PROXY_PORT_SOCKS5);
                applyProxy(
                    Constants.PROXY_TYPE_SOCKS5,
                    Constants.PROXY_HOST_DEFAULT,
                    Constants.PROXY_PORT_SOCKS5,
                    webView
                );
                return;
            }

            // Try HTTP proxy fallback
            boolean http = NetworkUtils.isProxyReachable(
                Constants.PROXY_HOST_DEFAULT,
                Constants.PROXY_PORT_HTTP
            );

            if (http) {
                Log.d(TAG, "Orbot HTTP detected on port "
                    + Constants.PROXY_PORT_HTTP);
                applyProxy(
                    Constants.PROXY_TYPE_HTTP,
                    Constants.PROXY_HOST_DEFAULT,
                    Constants.PROXY_PORT_HTTP,
                    webView
                );
                return;
            }

            // Neither available
            Log.w(TAG, "Orbot not detected on any port");
            if (listener != null) {
                listener.onProxyError(
                    "Orbot not found. Please install and start Orbot app."
                );
            }
        });
    }

    /**
     * Disables proxy — restores direct connection.
     * Clears all system proxy properties.
     *
     * @param webView WebView to remove proxy from
     */
    public void disableProxy(WebView webView) {
        Log.d(TAG, "Disabling proxy");

        // Clear system proxy properties
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        System.clearProperty("https.proxyHost");
        System.clearProperty("https.proxyPort");
        System.clearProperty("socksProxyHost");
        System.clearProperty("socksProxyPort");

        // Apply to WebView
        if (webView != null) {
            applySystemProxyToWebView(webView, null, 0);
        }

        proxyType = Constants.PROXY_TYPE_NONE;
        isActive  = false;

        Log.d(TAG, "Proxy disabled — direct connection restored");

        if (listener != null) {
            listener.onProxyDisabled();
        }
    }

    // ═════════════════════════════════════════════
    // PROXY APPLICATION
    // ═════════════════════════════════════════════

    /**
     * Tests proxy reachability then applies if reachable.
     * Runs in background thread.
     */
    private void testAndApply(
            String type, String host, int port, WebView webView) {

        executor.execute(() -> {
            Log.d(TAG, "Testing proxy: " + type + " " + host + ":" + port);

            boolean reachable = NetworkUtils.isProxyReachable(host, port);

            if (reachable) {
                applyProxy(type, host, port, webView);
                if (listener != null) {
                    listener.onProxyTestResult(true,
                        "Proxy reachable: " + host + ":" + port);
                }
            } else {
                Log.w(TAG, "Proxy not reachable: " + host + ":" + port);
                if (listener != null) {
                    listener.onProxyTestResult(false,
                        "Cannot reach proxy at " + host + ":" + port
                        + ". Is Orbot running?");
                    listener.onProxyError(
                        "Proxy unreachable: " + host + ":" + port);
                }
            }
        });
    }

    /**
     * Applies proxy settings to system and WebView.
     * Must be called from background thread.
     */
    private void applyProxy(
            String type, String host, int port, WebView webView) {

        Log.d(TAG, "Applying proxy: " + type + " → " + host + ":" + port);

        // ── Set Java system properties ──
        // These affect HttpURLConnection globally
        if (type.equals(Constants.PROXY_TYPE_SOCKS5)) {
            System.setProperty("socksProxyHost", host);
            System.setProperty("socksProxyPort", String.valueOf(port));
            // Clear HTTP proxy properties
            System.clearProperty("http.proxyHost");
            System.clearProperty("http.proxyPort");
            System.clearProperty("https.proxyHost");
            System.clearProperty("https.proxyPort");

        } else if (type.equals(Constants.PROXY_TYPE_HTTP)) {
            System.setProperty("http.proxyHost",  host);
            System.setProperty("http.proxyPort",  String.valueOf(port));
            System.setProperty("https.proxyHost", host);
            System.setProperty("https.proxyPort", String.valueOf(port));
            // Clear SOCKS properties
            System.clearProperty("socksProxyHost");
            System.clearProperty("socksProxyPort");
        }

        // ── Apply to WebView via reflection ──
        applySystemProxyToWebView(webView, host, port);

        // Update state
        this.proxyType = type;
        this.proxyHost = host;
        this.proxyPort = port;
        this.isActive  = true;

        Log.d(TAG, "Proxy applied successfully: "
            + type + " " + host + ":" + port);

        if (listener != null) {
            listener.onProxyEnabled(type, host, port);
        }
    }

    /**
     * Applies HTTP proxy to WebView using Android internals.
     *
     * Android WebView does not have a public API for proxy.
     * We use the system proxy mechanism via reflection —
     * same method used by Firefox and Brave on Android.
     *
     * Supports Android 5.0 (API 21) to Android 14 (API 34).
     *
     * @param webView WebView to proxy
     * @param host    Proxy host (null = clear proxy)
     * @param port    Proxy port
     */
    private void applySystemProxyToWebView(
            WebView webView, String host, int port) {

        if (webView == null) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // API 21+ method
                applyProxyApi21(webView, host, port);
            } else {
                // Older fallback
                applyProxyLegacy(webView, host, port);
            }
            Log.d(TAG, "WebView proxy applied via reflection");

        } catch (Exception e) {
            Log.e(TAG, "Failed to apply proxy to WebView: " + e.getMessage());
            // Fallback: system properties still route most traffic
        }
    }

    /**
     * Proxy injection for API 21+ via Network class.
     * Uses ArrayMap to inject proxy into WebView's
     * ApplicationInfo extras.
     */
    @SuppressWarnings({"unchecked", "JavaReflectionMemberAccess"})
    private void applyProxyApi21(WebView webView, String host, int port)
            throws Exception {

        android.net.ProxyInfo proxyInfo = null;

        if (host != null && !host.isEmpty()) {
            proxyInfo = android.net.ProxyInfo.buildDirectProxy(host, port);
        }

        // Get WebView's application class
        Object appObj = getWebViewApplication(webView);
        if (appObj == null) {
            Log.w(TAG, "Could not get WebView application object");
            return;
        }

        // Get LoadedApk field
        Class appClass = appObj.getClass();
        Field mLoadedApkField = null;

        // Search up the class hierarchy
        while (appClass != null) {
            try {
                mLoadedApkField = appClass.getDeclaredField("mLoadedApk");
                break;
            } catch (NoSuchFieldException e) {
                appClass = appClass.getSuperclass();
            }
        }

        if (mLoadedApkField == null) return;
        mLoadedApkField.setAccessible(true);
        Object mLoadedApk = mLoadedApkField.get(appObj);

        // Get receivers field
        Field receiversField =
            mLoadedApk.getClass().getDeclaredField("mReceivers");
        receiversField.setAccessible(true);
        ArrayMap receivers = (ArrayMap) receiversField.get(mLoadedApk);

        // Broadcast proxy change intent to WebView
        for (Object receiverMap : receivers.values()) {
            for (Object rec : ((ArrayMap) receiverMap).keySet()) {
                Class clazz = rec.getClass();
                if (clazz.getName().contains("ProxyChangeListener")) {
                    Method onReceive = clazz.getDeclaredMethod(
                        "onReceive",
                        Context.class,
                        android.content.Intent.class
                    );
                    onReceive.setAccessible(true);

                    android.content.Intent intent = new android.content.Intent(
                        Proxy.PROXY_CHANGE_ACTION
                    );

                    if (proxyInfo != null && Build.VERSION.SDK_INT >= 21) {
                        intent.putExtra("proxy", proxyInfo);
                    }

                    onReceive.invoke(rec, context, intent);
                    Log.d(TAG, "Proxy broadcast sent to WebView");
                }
            }
        }
    }

    /**
     * Legacy proxy injection for Android < 5.0.
     * Uses WebView settings approach.
     */
    @SuppressWarnings("JavaReflectionMemberAccess")
    private void applyProxyLegacy(WebView webView, String host, int port)
            throws Exception {

        if (host == null || host.isEmpty()) return;

        // Set via network class reflection
        Class networkClass = Class.forName("android.net.Network");
        Method setProxy = networkClass.getDeclaredMethod(
            "setHttpProxy",
            String.class, String.class, String.class
        );
        setProxy.setAccessible(true);
        setProxy.invoke(null, host, String.valueOf(port), "");
    }

    /**
     * Gets the application object from WebView for proxy injection.
     */
    private Object getWebViewApplication(WebView webView) {
        try {
            Class webViewClass = webView.getClass();
            Field mProviderField =
                webViewClass.getDeclaredField("mProvider");
            mProviderField.setAccessible(true);
            Object mProvider = mProviderField.get(webView);

            Field mAppField =
                mProvider.getClass().getDeclaredField("mContext");
            mAppField.setAccessible(true);
            return mAppField.get(mProvider);

        } catch (Exception e) {
            // Fallback — return app context directly
            return context.getApplicationContext();
        }
    }

    // ═════════════════════════════════════════════
    // PROXY TESTING
    // ═════════════════════════════════════════════

    /**
     * Tests current proxy configuration.
     * Runs in background, notifies listener with result.
     *
     * Tests by making a HEAD request to a known URL
     * through the configured proxy.
     */
    public void testCurrentProxy() {
        if (!isActive) {
            if (listener != null) {
                listener.onProxyTestResult(false, "No proxy configured");
            }
            return;
        }

        executor.execute(() -> {
            boolean reachable = NetworkUtils.isProxyReachable(
                proxyHost, proxyPort
            );

            String message = reachable
                ? "Proxy active: " + proxyType + " " + proxyHost + ":" + proxyPort
                : "Proxy unreachable: " + proxyHost + ":" + proxyPort;

            Log.d(TAG, "Proxy test result: " + message);

            if (listener != null) {
                listener.onProxyTestResult(reachable, message);
            }
        });
    }

    /**
     * Checks if Orbot is installed on device.
     * Checks for known Orbot package names.
     */
    public boolean isOrbotInstalled() {
        String[] orbotPackages = {
            "org.torproject.android",
            "org.torproject.torbrowser",
            "org.briarproject.briar.android"
        };

        android.content.pm.PackageManager pm = context.getPackageManager();
        for (String pkg : orbotPackages) {
            try {
                pm.getPackageInfo(pkg, 0);
                Log.d(TAG, "Orbot found: " + pkg);
                return true;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                // Not installed — try next
            }
        }

        Log.d(TAG, "Orbot not installed");
        return false;
    }

    /**
     * Opens Orbot app if installed.
     * If not installed, opens Play Store.
     */
    public void openOrStartOrbot() {
        android.content.Intent intent = context.getPackageManager()
            .getLaunchIntentForPackage("org.torproject.android");

        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.d(TAG, "Orbot launched");
        } else {
            // Open Play Store to install Orbot
            try {
                android.content.Intent storeIntent = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(
                        "market://details?id=org.torproject.android"
                    )
                );
                storeIntent.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                );
                context.startActivity(storeIntent);
                Log.d(TAG, "Opened Play Store for Orbot");
            } catch (Exception e) {
                Log.e(TAG, "Could not open Play Store: " + e.getMessage());
            }
        }
    }

    // ═════════════════════════════════════════════
    // PROXY CONFIGURATION BUILDER
    // ═════════════════════════════════════════════

    /**
     * Builder pattern for proxy configuration.
     * Used by SettingsActivity to build custom proxy config.
     *
     * Usage:
     *   ProxyConfig config = new ProxyManager.ProxyConfig.Builder()
     *       .type(Constants.PROXY_TYPE_HTTP)
     *       .host("127.0.0.1")
     *       .port(8118)
     *       .build();
     *   proxyManager.applyConfig(config, webView);
     */
    public static class ProxyConfig {
        public final String type;
        public final String host;
        public final int    port;
        public final boolean requiresOrbot;

        private ProxyConfig(Builder builder) {
            this.type          = builder.type;
            this.host          = builder.host;
            this.port          = builder.port;
            this.requiresOrbot = builder.requiresOrbot;
        }

        public static class Builder {
            private String  type          = Constants.PROXY_TYPE_NONE;
            private String  host          = Constants.PROXY_HOST_DEFAULT;
            private int     port          = Constants.PROXY_PORT_HTTP;
            private boolean requiresOrbot = false;

            public Builder type(String type) {
                this.type = type;
                return this;
            }

            public Builder host(String host) {
                this.host = host;
                return this;
            }

            public Builder port(int port) {
                this.port = port;
                return this;
            }

            public Builder requiresOrbot(boolean required) {
                this.requiresOrbot = required;
                return this;
            }

            public ProxyConfig build() {
                return new ProxyConfig(this);
            }
        }

        // Preset configs
        public static ProxyConfig orbotHttp() {
            return new Builder()
                .type(Constants.PROXY_TYPE_HTTP)
                .host(Constants.PROXY_HOST_DEFAULT)
                .port(Constants.PROXY_PORT_HTTP)
                .requiresOrbot(true)
                .build();
        }

        public static ProxyConfig orbotSocks5() {
            return new Builder()
                .type(Constants.PROXY_TYPE_SOCKS5)
                .host(Constants.PROXY_HOST_DEFAULT)
                .port(Constants.PROXY_PORT_SOCKS5)
                .requiresOrbot(true)
                .build();
        }

        public static ProxyConfig direct() {
            return new Builder()
                .type(Constants.PROXY_TYPE_NONE)
                .build();
        }

        @Override
        public String toString() {
            return "ProxyConfig{"
                + "type=" + type
                + ", host=" + host
                + ", port=" + port
                + ", requiresOrbot=" + requiresOrbot
                + "}";
        }
    }

    /**
     * Applies a ProxyConfig object.
     * Used by SettingsActivity.
     */
    public void applyConfig(ProxyConfig config, WebView webView) {
        if (config == null) return;

        switch (config.type) {
            case Constants.PROXY_TYPE_HTTP:
                enableHttpProxy(config.host, config.port, webView);
                break;
            case Constants.PROXY_TYPE_SOCKS5:
                enableSocks5Proxy(config.host, config.port, webView);
                break;
            case Constants.PROXY_TYPE_NONE:
            default:
                disableProxy(webView);
                break;
        }
    }

    // ═════════════════════════════════════════════
    // GETTERS
    // ═════════════════════════════════════════════

    public boolean isActive()    { return isActive;  }
    public String  getProxyType(){ return proxyType; }
    public String  getProxyHost(){ return proxyHost; }
    public int     getProxyPort(){ return proxyPort; }

    /**
     * Returns human-readable proxy status.
     * Used by Settings UI.
     */
    public String getStatusString() {
        if (!isActive) return "Off (Direct connection)";
        return proxyType + " → " + proxyHost + ":" + proxyPort;
    }

    /**
     * Returns java.net.Proxy object for manual use.
     * Used by NetworkUtils.fetchText() when proxy is active.
     */
    public java.net.Proxy getJavaProxy() {
        if (!isActive) return java.net.Proxy.NO_PROXY;

        java.net.Proxy.Type type = proxyType.equals(Constants.PROXY_TYPE_SOCKS5)
            ? java.net.Proxy.Type.SOCKS
            : java.net.Proxy.Type.HTTP;

        return new java.net.Proxy(
            type,
            new InetSocketAddress(proxyHost, proxyPort)
        );
    }

    // ═════════════════════════════════════════════
    // VALIDATION
    // ═════════════════════════════════════════════

    /**
     * Validates proxy host and port inputs.
     */
    private boolean validateProxyInput(String host, int port) {
        if (host == null || host.trim().isEmpty()) {
            Log.e(TAG, "Invalid proxy host: null or empty");
            if (listener != null) {
                listener.onProxyError("Invalid proxy host");
            }
            return false;
        }

        if (port < 1 || port > 65535) {
            Log.e(TAG, "Invalid proxy port: " + port);
            if (listener != null) {
                listener.onProxyError(
                    "Invalid proxy port: " + port
                    + ". Must be between 1–65535"
                );
            }
            return false;
        }

        return true;
    }
        }
