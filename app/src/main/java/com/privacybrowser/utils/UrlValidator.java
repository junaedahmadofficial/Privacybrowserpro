package com.privacybrowser.utils;

import android.text.TextUtils;
import android.util.Patterns;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * UrlValidator.java
 * Handles all URL validation, sanitization, and classification.
 * Used by BrowserEngine before loading any URL into WebView.
 *
 * Security Rules:
 *  - Block dangerous schemes (javascript:, vbscript:, data:text/html)
 *  - Auto-fix missing scheme (google.com → https://google.com)
 *  - Convert search queries to search engine URL
 *  - Validate structure before passing to WebView
 */
public final class UrlValidator {

    // ─────────────────────────────────────────────
    // Prevent instantiation
    // ─────────────────────────────────────────────
    private UrlValidator() {}

    // ─────────────────────────────────────────────
    // Known safe internal pages
    // ─────────────────────────────────────────────
    private static final Set<String> INTERNAL_PAGES = new HashSet<>(Arrays.asList(
        "about:blank",
        "about:newtab"
    ));

    // ─────────────────────────────────────────────
    // Dangerous schemes — block completely
    // ─────────────────────────────────────────────
    private static final String[] DANGEROUS_SCHEMES = {
        "javascript:",
        "vbscript:",
        "data:text/html",
        "data:application",
        "jar:",
        "ws:",     // Unencrypted WebSocket
        "intent:"  // Android intent hijacking
    };

    // ─────────────────────────────────────────────
    // File scheme — restricted (local file access)
    // ─────────────────────────────────────────────
    private static final String FILE_SCHEME = "file://";

    // ─────────────────────────────────────────────
    // Valid TLD check — catch garbage input like "hello world"
    // ─────────────────────────────────────────────
    private static final Set<String> COMMON_TLDS = new HashSet<>(Arrays.asList(
        "com", "org", "net", "edu", "gov", "io", "co",
        "info", "biz", "app", "dev", "me", "uk", "us",
        "ca", "de", "fr", "jp", "au", "in", "bd"
    ));

    // ═════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════

    /**
     * Master method — call this from BrowserEngine.
     * Input:  Raw text from address bar (e.g. "google.com", "what is AI", "https://...")
     * Output: Safe, loadable URL string
     *
     * Flow:
     *   1. Trim & null check
     *   2. Internal page check
     *   3. Dangerous scheme block
     *   4. If looks like URL → fix scheme → validate
     *   5. Else → convert to search query
     */
    public static String getLoadableUrl(String rawInput, String searchEngineUrl) {

        // ── Step 1: Null / empty check ──
        if (TextUtils.isEmpty(rawInput)) {
            return Constants.DEFAULT_HOME_URL;
        }
        String input = rawInput.trim();

        // ── Step 2: Internal pages ──
        if (INTERNAL_PAGES.contains(input.toLowerCase())) {
            return input;
        }

        // ── Step 3: Block dangerous schemes ──
        if (isDangerousScheme(input)) {
            return Constants.BLANK_PAGE; // Silently block
        }

        // ── Step 4: Detect if it's a URL or a search query ──
        if (looksLikeUrl(input)) {
            return fixAndValidate(input);
        }

        // ── Step 5: Treat as search query ──
        return buildSearchUrl(input, searchEngineUrl);
    }

    /**
     * Quick overload using default DuckDuckGo search.
     */
    public static String getLoadableUrl(String rawInput) {
        return getLoadableUrl(rawInput, Constants.DEFAULT_SEARCH_URL);
    }

    /**
     * Returns true if URL uses HTTPS.
     */
    public static boolean isSecure(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.toLowerCase().startsWith("https://");
    }

    /**
     * Returns true if the URL is dangerous.
     * BrowserEngine calls this before every page load.
     */
    public static boolean isDangerousScheme(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase().trim();
        for (String scheme : DANGEROUS_SCHEMES) {
            if (lower.startsWith(scheme)) return true;
        }
        return false;
    }

    /**
     * Returns true if URL uses file:// scheme.
     * BrowserEngine restricts file:// access.
     */
    public static boolean isFileScheme(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.toLowerCase().trim().startsWith(FILE_SCHEME);
    }

    /**
     * Extracts hostname from a full URL.
     * Example: "https://www.google.com/search?q=hi" → "google.com"
     */
    public static String extractHost(String url) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) return "";
            // Strip leading "www."
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            return host.toLowerCase();
        } catch (URISyntaxException e) {
            return "";
        }
    }

    /**
     * Extracts scheme from URL.
     * Example: "https://google.com" → "https"
     */
    public static String extractScheme(String url) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            return scheme != null ? scheme.toLowerCase() : "";
        } catch (URISyntaxException e) {
            return "";
        }
    }

    /**
     * Checks if two URLs share the same host.
     * Used by AdBlockEngine for third-party request detection.
     */
    public static boolean isSameHost(String urlA, String urlB) {
        return extractHost(urlA).equals(extractHost(urlB));
    }

    /**
     * Strips all tracking parameters from URL.
     * Example: ?utm_source=google&utm_medium=cpc → removed
     *
     * Common trackers cleaned:
     *   utm_*, fbclid, gclid, mc_eid, ref, source
     */
    public static String stripTrackingParams(String url) {
        if (TextUtils.isEmpty(url)) return url;

        try {
            URI original = new URI(url);
            String query = original.getQuery();

            if (TextUtils.isEmpty(query)) return url;

            StringBuilder cleanQuery = new StringBuilder();
            String[] params = query.split("&");

            for (String param : params) {
                String paramLower = param.toLowerCase();

                // Skip tracking parameters
                if (paramLower.startsWith("utm_")
                    || paramLower.startsWith("fbclid")
                    || paramLower.startsWith("gclid")
                    || paramLower.startsWith("mc_eid")
                    || paramLower.startsWith("ref=")
                    || paramLower.startsWith("source=")
                    || paramLower.startsWith("_ga")
                    || paramLower.startsWith("msclkid")) {
                    continue; // Strip it
                }

                if (cleanQuery.length() > 0) cleanQuery.append("&");
                cleanQuery.append(param);
            }

            // Rebuild URI without tracking params
            URI clean = new URI(
                original.getScheme(),
                original.getAuthority(),
                original.getPath(),
                cleanQuery.length() > 0 ? cleanQuery.toString() : null,
                original.getFragment()
            );
            return clean.toString();

        } catch (URISyntaxException e) {
            return url; // Return original if parsing fails
        }
    }

    /**
     * Normalizes URL for display in address bar.
     * Strips scheme, trailing slash for cleaner look.
     * Example: "https://www.google.com/" → "google.com"
     */
    public static String toDisplayUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        if (url.equals(Constants.BLANK_PAGE)) return "";

        String display = url;

        // Strip scheme
        if (display.startsWith("https://")) display = display.substring(8);
        else if (display.startsWith("http://")) display = display.substring(7);

        // Strip leading www.
        if (display.startsWith("www.")) display = display.substring(4);

        // Strip trailing slash
        if (display.endsWith("/")) display = display.substring(0, display.length() - 1);

        return display;
    }

    /**
     * Returns true if URL is valid and reachable in structure.
     * Does NOT make a network call.
     */
    public static boolean isValidUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        try {
            new URL(url);
            return Patterns.WEB_URL.matcher(url).matches();
        } catch (MalformedURLException e) {
            return false;
        }
    }

    // ═════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═════════════════════════════════════════════

    /**
     * Detects if raw input looks like a URL vs a search query.
     *
     * Logic:
     *  - Has http:// or https:// prefix → URL
     *  - Has no spaces + has a dot + valid TLD → URL
     *  - Otherwise → search query
     */
    private static boolean looksLikeUrl(String input) {
        // Explicit scheme
        String lower = input.toLowerCase();
        if (lower.startsWith("http://")
            || lower.startsWith("https://")
            || lower.startsWith("ftp://")) {
            return true;
        }

        // No spaces — might be domain
        if (input.contains(" ")) return false;

        // Has dot — check TLD
        if (input.contains(".")) {
            String[] parts = input.split("\\.");
            if (parts.length >= 2) {
                String tld = parts[parts.length - 1]
                    .toLowerCase()
                    .replaceAll("[^a-z]", ""); // strip port/path
                return COMMON_TLDS.contains(tld) || tld.length() == 2; // 2-letter = country TLD
            }
        }

        // Localhost / IP address
        if (lower.startsWith("localhost") || lower.matches("\\d+\\.\\d+\\.\\d+\\.\\d+.*")) {
            return true;
        }

        return false;
    }

    /**
     * Adds https:// if missing, then validates the URL.
     * Falls back to search if URL is structurally broken.
     */
    private static String fixAndValidate(String input) {
        String url = input;

        // Add scheme if missing
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://")
            && !lower.startsWith("https://")
            && !lower.startsWith("ftp://")) {
            url = "https://" + url;
        }

        // Validate structure
        if (isValidUrl(url)) {
            return url;
        }

        // Structural failure → treat as search
        return buildSearchUrl(input, Constants.DEFAULT_SEARCH_URL);
    }

    /**
     * Encodes raw text as a search query URL.
     * Example: "what is AI" → "https://duckduckgo.com/?q=what+is+AI"
     */
    private static String buildSearchUrl(String query, String searchEngineUrl) {
        try {
            // URI encoding — replace spaces with +
            String encoded = new URI(
                null, null, query, null
            ).toASCIIString();
            // URI encodes spaces as %20, search engines prefer +
            encoded = encoded.replace("%20", "+");
            return searchEngineUrl + encoded;
        } catch (URISyntaxException e) {
            // Fallback — basic replace
            return searchEngineUrl + query.replace(" ", "+");
        }
    }
}
