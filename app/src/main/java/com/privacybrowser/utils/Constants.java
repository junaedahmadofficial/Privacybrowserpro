package com.privacybrowser.utils;

/**
 * Constants.java
 * Central configuration file for Privacy Browser Pro.
 * All magic numbers, keys, and default values live here.
 */
public final class Constants {

    // ─────────────────────────────────────────────
    // Prevent instantiation
    // ─────────────────────────────────────────────
    private Constants() {}

    // ─────────────────────────────────────────────
    // APP INFO
    // ─────────────────────────────────────────────
    public static final String APP_NAME            = "Privacy Browser Pro";
    public static final String APP_VERSION         = "1.0.0";
    public static final String PACKAGE_NAME        = "com.privacybrowser";

    // ─────────────────────────────────────────────
    // DEFAULT BROWSER SETTINGS
    // ─────────────────────────────────────────────
    public static final String DEFAULT_HOME_URL    = "https://duckduckgo.com";
    public static final String DEFAULT_SEARCH_URL  = "https://duckduckgo.com/?q=";
    public static final String BLANK_PAGE          = "about:blank";
    public static final String USER_AGENT          =
        "Mozilla/5.0 (Linux; Android 11; Mobile) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36";

    // ─────────────────────────────────────────────
    // TAB MANAGEMENT
    // ─────────────────────────────────────────────
    public static final int MAX_TABS               = 10;   // Low-end device limit
    public static final int TAB_THUMBNAIL_WIDTH    = 300;
    public static final int TAB_THUMBNAIL_HEIGHT   = 200;

    // ─────────────────────────────────────────────
    // ADBLOCK ENGINE
    // ─────────────────────────────────────────────
    public static final String ADBLOCK_ASSET_DIR   = "adblock";
    public static final String EASYLIST_FILE       = "easylist.txt";
    public static final String EASYPRIVACY_FILE    = "easyprivacy.txt";
    public static final int    ADBLOCK_CACHE_SIZE  = 5000; // Max rules in memory

    // Blocked resource types (WebView resource type strings)
    public static final String[] BLOCKED_EXTENSIONS = {
        ".jpg", ".png", ".gif", ".webp",   // Image ads
        ".woff", ".woff2",                  // Font trackers
    };

    // ─────────────────────────────────────────────
    // PRIVATE MODE
    // ─────────────────────────────────────────────
    public static final String PRIVATE_MODE_LABEL  = "Private";
    public static final boolean DEFAULT_PRIVATE    = false;

    // ─────────────────────────────────────────────
    // PROXY / TOR-LIKE MODE
    // ─────────────────────────────────────────────
    public static final String PROXY_HOST_DEFAULT  = "127.0.0.1";
    public static final int    PROXY_PORT_SOCKS5   = 9050;  // Orbot SOCKS5
    public static final int    PROXY_PORT_HTTP     = 8118;  // Orbot HTTP
    public static final String PROXY_TYPE_NONE     = "NONE";
    public static final String PROXY_TYPE_HTTP     = "HTTP";
    public static final String PROXY_TYPE_SOCKS5   = "SOCKS5";

    // ─────────────────────────────────────────────
    // HISTORY & STORAGE
    // ─────────────────────────────────────────────
    public static final String DB_NAME             = "browser_data.db";
    public static final int    DB_VERSION          = 1;
    public static final String TABLE_HISTORY       = "history";
    public static final String COL_ID              = "_id";
    public static final String COL_URL             = "url";
    public static final String COL_TITLE           = "title";
    public static final String COL_TIMESTAMP       = "timestamp";
    public static final String COL_FAVICON         = "favicon";
    public static final int    MAX_HISTORY_ENTRIES = 1000;

    // ─────────────────────────────────────────────
    // SHARED PREFERENCES KEYS
    // ─────────────────────────────────────────────
    public static final String PREFS_NAME              = "privacy_browser_prefs";
    public static final String PREF_HOME_URL           = "home_url";
    public static final String PREF_SEARCH_ENGINE      = "search_engine";
    public static final String PREF_ADBLOCK_ENABLED    = "adblock_enabled";
    public static final String PREF_PRIVATE_MODE       = "private_mode";
    public static final String PREF_DARK_MODE          = "dark_mode";
    public static final String PREF_JS_ENABLED         = "javascript_enabled";
    public static final String PREF_PROXY_TYPE         = "proxy_type";
    public static final String PREF_PROXY_HOST         = "proxy_host";
    public static final String PREF_PROXY_PORT         = "proxy_port";
    public static final String PREF_SAVE_HISTORY       = "save_history";
    public static final String PREF_DO_NOT_TRACK       = "do_not_track";
    public static final String PREF_BLOCK_THIRD_PARTY  = "block_third_party_cookies";

    // ─────────────────────────────────────────────
    // SEARCH ENGINES
    // ─────────────────────────────────────────────
    public static final String SEARCH_DUCKDUCKGO   = "https://duckduckgo.com/?q=";
    public static final String SEARCH_GOOGLE       = "https://www.google.com/search?q=";
    public static final String SEARCH_BRAVE        = "https://search.brave.com/search?q=";
    public static final String SEARCH_STARTPAGE    = "https://www.startpage.com/search?q=";

    // ─────────────────────────────────────────────
    // NETWORK
    // ─────────────────────────────────────────────
    public static final int    CONNECT_TIMEOUT_MS  = 15_000;
    public static final int    READ_TIMEOUT_MS     = 30_000;
    public static final String DNT_HEADER          = "DNT";
    public static final String DNT_VALUE           = "1";

    // ─────────────────────────────────────────────
    // UI
    // ─────────────────────────────────────────────
    public static final int    PROGRESS_COMPLETE   = 100;
    public static final int    ANIMATION_DURATION  = 200; // ms
    public static final int    ADDRESS_BAR_HEIGHT  = 56;  // dp

    // ─────────────────────────────────────────────
    // INTENT KEYS (Activity communication)
    // ─────────────────────────────────────────────
    public static final String INTENT_URL          = "intent_url";
    public static final String INTENT_TAB_ID       = "intent_tab_id";
    public static final String INTENT_PRIVATE_MODE = "intent_private_mode";

    // ─────────────────────────────────────────────
    // SECURITY
    // ─────────────────────────────────────────────
    public static final String[] SAFE_SCHEMES = {
        "https://", "http://", "ftp://",
        "about:", "data:", "file://"
    };
    public static final String[] DANGEROUS_SCHEMES = {
        "javascript:", "vbscript:", "data:text/html"
    };
  }
