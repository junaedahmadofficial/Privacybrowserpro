package com.privacybrowser.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * NetworkUtils.java
 * Handles all network-level operations for Privacy Browser Pro.
 *
 * Responsibilities:
 *  - Internet connectivity check
 *  - Connection type detection (WiFi / Mobile / VPN)
 *  - Proxy-aware HTTP requests (for filter list downloads)
 *  - Proxy connectivity test (Orbot alive check)
 *  - Safe asset loading from APK (for adblock lists)
 *
 * NOTE: All network I/O must be called from a background thread.
 * This class never touches the UI thread.
 */
public final class NetworkUtils {

    private static final String TAG = "NetworkUtils";

    // ─────────────────────────────────────────────
    // Prevent instantiation
    // ─────────────────────────────────────────────
    private NetworkUtils() {}

    // ═════════════════════════════════════════════
    // CONNECTIVITY CHECKS
    // ═════════════════════════════════════════════

    /**
     * Returns true if device has an active internet connection.
     * Supports both old API (< 23) and new API (>= 23).
     */
    public static boolean isConnected(Context context) {
        if (context == null) return false;

        ConnectivityManager cm = (ConnectivityManager)
            context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm == null) return false;

        // API 23+ (Android 6.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;

            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return false;

            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }

        // API 21-22 fallback
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    /**
     * Returns true if connected via WiFi.
     */
    public static boolean isWifi(Context context) {
        if (context == null) return false;

        ConnectivityManager cm = (ConnectivityManager)
            context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null
                && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        }

        // Legacy fallback
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null
            && info.getType() == ConnectivityManager.TYPE_WIFI
            && info.isConnected();
    }

    /**
     * Returns true if connected via Mobile Data.
     */
    public static boolean isMobileData(Context context) {
        if (context == null) return false;

        ConnectivityManager cm = (ConnectivityManager)
            context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null
                && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        }

        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null
            && info.getType() == ConnectivityManager.TYPE_MOBILE
            && info.isConnected();
    }

    /**
     * Returns true if a VPN is currently active.
     * Used to detect if Orbot / VPN is running.
     */
    public static boolean isVpnActive(Context context) {
        if (context == null) return false;

        ConnectivityManager cm = (ConnectivityManager)
            context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null
                && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        }

        return false;
    }

    /**
     * Returns human-readable connection type string.
     * Used in Settings UI → "WiFi", "Mobile", "VPN", "None"
     */
    public static String getConnectionType(Context context) {
        if (!isConnected(context))  return "None";
        if (isVpnActive(context))   return "VPN";
        if (isWifi(context))        return "WiFi";
        if (isMobileData(context))  return "Mobile";
        return "Unknown";
    }

    // ═════════════════════════════════════════════
    // PROXY CONNECTIVITY TEST
    // ═════════════════════════════════════════════

    /**
     * Tests if a proxy (Orbot SOCKS5/HTTP) is alive and accepting connections.
     *
     * Call this from a background thread before enabling proxy mode.
     * Returns true if proxy responds within timeout.
     *
     * Usage:
     *   boolean orbotAlive = NetworkUtils.isProxyReachable("127.0.0.1", 9050);
     */
    public static boolean isProxyReachable(String host, int port) {
        try {
            java.net.Socket socket = new java.net.Socket();
            socket.connect(
                new InetSocketAddress(host, port),
                3000 // 3 second timeout
            );
            socket.close();
            Log.d(TAG, "Proxy reachable: " + host + ":" + port);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Proxy not reachable: " + host + ":" + port
                + " — " + e.getMessage());
            return false;
        }
    }

    /**
     * Quick check: Is Orbot SOCKS5 running on default port 9050?
     */
    public static boolean isOrbotSocks5Running() {
        return isProxyReachable(
            Constants.PROXY_HOST_DEFAULT,
            Constants.PROXY_PORT_SOCKS5
        );
    }

    /**
     * Quick check: Is Orbot HTTP proxy running on default port 8118?
     */
    public static boolean isOrbotHttpRunning() {
        return isProxyReachable(
            Constants.PROXY_HOST_DEFAULT,
            Constants.PROXY_PORT_HTTP
        );
    }

    // ═════════════════════════════════════════════
    // HTTP REQUESTS (Proxy-aware)
    // ═════════════════════════════════════════════

    /**
     * Fetches text content from a URL.
     * Used to download adblock filter lists (EasyList etc.)
     *
     * Supports optional HTTP proxy routing.
     * Always called from background thread.
     *
     * @param urlString  Target URL
     * @param proxyHost  Proxy host (null = direct connection)
     * @param proxyPort  Proxy port (ignored if proxyHost is null)
     * @return Response body as String, or null on failure
     */
    public static String fetchText(String urlString, String proxyHost, int proxyPort) {
        HttpURLConnection connection = null;
        BufferedReader reader = null;

        try {
            URL url = new URL(urlString);

            // Build connection — with or without proxy
            if (proxyHost != null && !proxyHost.isEmpty()) {
                Proxy proxy = new Proxy(
                    Proxy.Type.HTTP,
                    new InetSocketAddress(proxyHost, proxyPort)
                );
                connection = (HttpURLConnection) url.openConnection(proxy);
            } else {
                connection = (HttpURLConnection) url.openConnection();
            }

            // Configure connection
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(Constants.CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(Constants.READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", Constants.USER_AGENT);
            connection.setRequestProperty("Accept-Encoding", "identity"); // No gzip
            connection.setInstanceFollowRedirects(true);
            connection.setDoInput(true);

            // Check response code
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP " + responseCode + " for: " + urlString);
                return null;
            }

            // Read response body
            InputStream inputStream = connection.getInputStream();
            reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8)
            );

            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append("\n");
            }

            return result.toString();

        } catch (IOException e) {
            Log.e(TAG, "fetchText failed: " + e.getMessage());
            return null;

        } finally {
            // Always clean up
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Direct fetch without proxy.
     */
    public static String fetchText(String urlString) {
        return fetchText(urlString, null, 0);
    }

    // ═════════════════════════════════════════════
    // ASSET LOADER (APK internal files)
    // ═════════════════════════════════════════════

    /**
     * Reads a text file from the app's assets/ folder.
     * Used to load bundled adblock lists (easylist.txt, easyprivacy.txt).
     *
     * @param context   App context
     * @param assetPath Path inside assets/ folder
     *                  e.g. "adblock/easylist.txt"
     * @return File content as String, or null on failure
     */
    public static String loadAssetFile(Context context, String assetPath) {
        if (context == null || assetPath == null) return null;

        BufferedReader reader = null;
        try {
            InputStream inputStream = context.getAssets().open(assetPath);
            reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8)
            );

            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append("\n");
            }

            Log.d(TAG, "Loaded asset: " + assetPath
                + " (" + result.length() + " chars)");
            return result.toString();

        } catch (IOException e) {
            Log.e(TAG, "Failed to load asset: " + assetPath
                + " — " + e.getMessage());
            return null;

        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Reads asset file line by line — memory efficient.
     * Used by AdBlockEngine to load large filter lists
     * without loading the entire file into a single String.
     *
     * @param context   App context
     * @param assetPath Path inside assets/
     * @param callback  Called for each line
     */
    public static void loadAssetFileLines(
            Context context,
            String assetPath,
            LineCallback callback) {

        if (context == null || assetPath == null || callback == null) return;

        BufferedReader reader = null;
        try {
            InputStream inputStream = context.getAssets().open(assetPath);
            reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8)
            );

            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null) {
                callback.onLine(line);
                lineCount++;
            }

            Log.d(TAG, "Streamed " + lineCount + " lines from: " + assetPath);

        } catch (IOException e) {
            Log.e(TAG, "Failed to stream asset: " + assetPath
                + " — " + e.getMessage());
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ═════════════════════════════════════════════
    // CALLBACK INTERFACE
    // ═════════════════════════════════════════════

    /**
     * Callback for line-by-line asset reading.
     * AdBlockEngine implements this to process filter rules
     * one line at a time — avoids loading 60,000 lines into RAM.
     */
    public interface LineCallback {
        void onLine(String line);
    }

    // ═════════════════════════════════════════════
    // HEADER UTILITIES
    // ═════════════════════════════════════════════

    /**
     * Builds a Do-Not-Track header value map.
     * BrowserEngine injects this into WebView requests.
     */
    public static java.util.Map<String, String> buildPrivacyHeaders(
            boolean doNotTrack) {

        java.util.Map<String, String> headers = new java.util.HashMap<>();

        if (doNotTrack) {
            headers.put(Constants.DNT_HEADER, Constants.DNT_VALUE);
            headers.put("Sec-GPC", "1"); // Global Privacy Control
        }

        return headers;
    }

    /**
     * Checks if a URL's host resolves — basic reachability test.
     * Used to show "No internet" page vs actual server error.
     * Must be called from background thread.
     */
    public static boolean isHostReachable(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection =
                (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("HEAD"); // Lightweight check
            int code = connection.getResponseCode();
            connection.disconnect();
            return (code >= 200 && code < 400);
        } catch (IOException e) {
            return false;
        }
    }
  }
