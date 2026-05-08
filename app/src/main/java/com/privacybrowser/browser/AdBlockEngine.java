package com.privacybrowser.browser;

import android.content.Context;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import com.privacybrowser.utils.Constants;
import com.privacybrowser.utils.NetworkUtils;
import com.privacybrowser.utils.UrlValidator;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AdBlockEngine.java
 * Core ad and tracker blocking engine for Privacy Browser Pro.
 *
 * Architecture:
 *  ┌─────────────────────────────────────────┐
 *  │           AdBlockEngine                 │
 *  │                                         │
 *  │  ┌──────────────┐  ┌─────────────────┐  │
 *  │  │ Domain Rules │  │  Pattern Rules  │  │
 *  │  │ (HashSet)    │  │  (List<String>) │  │
 *  │  └──────────────┘  └─────────────────┘  │
 *  │                                         │
 *  │  shouldBlock(url, pageUrl)              │
 *  │      ↓                                  │
 *  │  1. Whitelist check                     │
 *  │  2. Domain exact match (fast O(1))      │
 *  │  3. Pattern contains match              │
 *  │  4. Third-party script check            │
 *  └─────────────────────────────────────────┘
 *
 * Filter Rule Format (EasyList compatible):
 *  ||ads.example.com^        → Block domain
 *  @@||safe.example.com^     → Whitelist (exception)
 *  /banner/                  → Block URL containing pattern
 *  ##.ad-class               → CSS cosmetic (parsed, not applied here)
 */
public class AdBlockEngine {

    private static final String TAG = "AdBlockEngine";

    // ─────────────────────────────────────────────
    // Singleton pattern — one engine for whole app
    // ─────────────────────────────────────────────
    private static volatile AdBlockEngine instance;

    public static AdBlockEngine getInstance(Context context) {
        if (instance == null) {
            synchronized (AdBlockEngine.class) {
                if (instance == null) {
                    instance = new AdBlockEngine(
                        context.getApplicationContext()
                    );
                }
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────
    // Rule Storage
    // ─────────────────────────────────────────────

    // Fast O(1) domain lookup — "ads.google.com" → blocked
    private final Set<String> blockedDomains;

    // Pattern rules — URL contains substring → blocked
    // e.g. "/advertisement/", "tracking.js", "pixel.gif"
    private final List<String> blockPatterns;

    // Whitelist — these are NEVER blocked (@@|| rules)
    private final Set<String> whitelistDomains;

    // Custom user rules (added via Settings)
    private final Set<String> customBlockedDomains;
    private final Set<String> customWhitelistDomains;

    // ─────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────
    private boolean enabled = true;
    private boolean blockThirdPartyScripts = true;
    private boolean isLoaded = false;

    // Statistics
    private final AtomicInteger totalBlocked = new AtomicInteger(0);
    private final AtomicInteger totalAllowed  = new AtomicInteger(0);

    // Background loader
    private final ExecutorService loader =
        Executors.newSingleThreadExecutor();

    private final Context context;

    // ─────────────────────────────────────────────
    // Empty response — returned for blocked requests
    // ─────────────────────────────────────────────
    private static final WebResourceResponse BLOCKED_RESPONSE =
        new WebResourceResponse(
            "text/plain",
            "utf-8",
            new ByteArrayInputStream(
                "".getBytes(StandardCharsets.UTF_8)
            )
        );

    // ═════════════════════════════════════════════
    // CONSTRUCTOR
    // ═════════════════════════════════════════════

    private AdBlockEngine(Context context) {
        this.context = context;

        // Thread-safe collections
        this.blockedDomains       = Collections.synchronizedSet(new HashSet<>());
        this.whitelistDomains     = Collections.synchronizedSet(new HashSet<>());
        this.customBlockedDomains = Collections.synchronizedSet(new HashSet<>());
        this.customWhitelistDomains = Collections.synchronizedSet(new HashSet<>());
        this.blockPatterns        = Collections.synchronizedList(new ArrayList<>());

        // Load built-in hardcoded list immediately (zero I/O wait)
        loadBuiltInRules();

        // Load filter files from assets in background
        loadFilterFilesAsync();
    }

    // ═════════════════════════════════════════════
    // PRIMARY BLOCKING API
    // ═════════════════════════════════════════════

    /**
     * Master method — called by BrowserEngine for EVERY resource request.
     *
     * BrowserEngine usage:
     *   WebResourceResponse response =
     *       adBlockEngine.shouldBlock(request, currentPageUrl);
     *   if (response != null) return response; // Blocked!
     *   return null; // Allow
     *
     * @param request    WebView's resource request
     * @param pageUrl    The URL of the page making the request
     * @return           BLOCKED_RESPONSE if blocked, null if allowed
     */
    public WebResourceResponse shouldBlock(
            WebResourceRequest request,
            String pageUrl) {

        if (!enabled || request == null) return null;

        String requestUrl = request.getUrl().toString();
        if (requestUrl == null || requestUrl.isEmpty()) return null;

        // Run blocking check
        if (isBlocked(requestUrl, pageUrl)) {
            totalBlocked.incrementAndGet();
            Log.d(TAG, "BLOCKED: " + requestUrl);
            return BLOCKED_RESPONSE;
        }

        totalAllowed.incrementAndGet();
        return null; // Allow request
    }

    /**
     * Overload for simple URL string check.
     * Used by BrowserEngine.shouldOverrideUrlLoading()
     */
    public boolean shouldBlockUrl(String url, String pageUrl) {
        if (!enabled) return false;
        return isBlocked(url, pageUrl);
    }

    // ═════════════════════════════════════════════
    // CORE BLOCKING LOGIC
    // ═════════════════════════════════════════════

    /**
     * Full blocking decision pipeline.
     *
     * Priority order:
     *  1. Whitelist → NEVER block (highest priority)
     *  2. Custom user whitelist → NEVER block
     *  3. Domain exact match → BLOCK
     *  4. Custom user block → BLOCK
     *  5. Pattern match → BLOCK
     *  6. Third-party script check → BLOCK
     *  7. Dangerous scheme → BLOCK
     *  8. Default → ALLOW
     */
    private boolean isBlocked(String requestUrl, String pageUrl) {

        // Extract host from requested URL
        String requestHost = UrlValidator.extractHost(requestUrl);

        // ── 1. Global whitelist check ──
        if (whitelistDomains.contains(requestHost)) return false;
        if (customWhitelistDomains.contains(requestHost)) return false;

        // ── 2. Dangerous scheme ──
        if (UrlValidator.isDangerousScheme(requestUrl)) return true;

        // ── 3. Exact domain match (O(1) HashSet lookup) ──
        if (blockedDomains.contains(requestHost)) return true;

        // Also check with www. prefix stripped
        if (requestHost.startsWith("www.")) {
            String bare = requestHost.substring(4);
            if (blockedDomains.contains(bare)) return true;
        }

        // ── 4. Custom user block rules ──
        if (customBlockedDomains.contains(requestHost)) return true;

        // ── 5. Pattern matching ──
        String lowerUrl = requestUrl.toLowerCase();
        synchronized (blockPatterns) {
            for (String pattern : blockPatterns) {
                if (lowerUrl.contains(pattern)) return true;
            }
        }

        // ── 6. Third-party script blocking ──
        if (blockThirdPartyScripts && pageUrl != null) {
            if (isThirdPartyScript(requestUrl, pageUrl)) return true;
        }

        return false; // ALLOW
    }

    /**
     * Detects third-party script requests.
     * If page is "news.com" but script loads from "tracking.io" → block.
     */
    private boolean isThirdPartyScript(String requestUrl, String pageUrl) {
        // Only applies to JS files
        if (!requestUrl.toLowerCase().contains(".js")) return false;

        // Same host = first party = allow
        if (UrlValidator.isSameHost(requestUrl, pageUrl)) return false;

        String requestHost = UrlValidator.extractHost(requestUrl);

        // Check if it's a known CDN (allow)
        for (String cdn : TRUSTED_CDNS) {
            if (requestHost.contains(cdn)) return false;
        }

        // Third-party JS from unknown source → block
        return true;
    }

    // ═════════════════════════════════════════════
    // RULE LOADERS
    // ═════════════════════════════════════════════

    /**
     * Loads hardcoded built-in block rules.
     * These load instantly — no file I/O.
     * Covers the most common ad/tracker domains.
     */
    private void loadBuiltInRules() {
        // ── Major Ad Networks ──
        String[] adDomains = {
            // Google Ads
            "googlesyndication.com",
            "doubleclick.net",
            "googleadservices.com",
            "googletagservices.com",
            "googletagmanager.com",
            "google-analytics.com",
            "adservice.google.com",

            // Facebook Tracking
            "facebook.net",
            "connect.facebook.net",
            "graph.facebook.com",
            "pixel.facebook.com",

            // Amazon Ads
            "amazon-adsystem.com",
            "assoc-amazon.com",

            // Analytics & Trackers
            "analytics.twitter.com",
            "ads.twitter.com",
            "static.ads-twitter.com",
            "analytics.tiktok.com",
            "ads.tiktok.com",
            "bat.bing.com",
            "ads.linkedin.com",

            // Major Trackers
            "hotjar.com",
            "fullstory.com",
            "mixpanel.com",
            "segment.com",
            "amplitude.com",
            "heap.io",
            "intercom.io",
            "intercomcdn.com",
            "crisp.chat",

            // Ad exchanges
            "rubiconproject.com",
            "openx.net",
            "casalemedia.com",
            "pubmatic.com",
            "appnexus.com",
            "taboola.com",
            "outbrain.com",
            "revcontent.com",
            "sharethrough.com",
            "adroll.com",
            "criteo.com",
            "criteo.net",

            // Fingerprinting & Surveillance
            "scorecardresearch.com",
            "quantserve.com",
            "comscore.com",
            "bluekai.com",
            "demdex.net",
            "adsymptotic.com",
            "moatads.com",
            "parsely.com",

            // Malvertising
            "trafficjunky.net",
            "adnxs.com",
            "adsafeprotected.com",
            "mediamath.com",
        };

        for (String domain : adDomains) {
            blockedDomains.add(domain);
        }

        // ── URL Pattern Rules ──
        String[] patterns = {
            "/ads/",
            "/advertisement/",
            "/advertising/",
            "/advert/",
            "/banner/",
            "/popup/",
            "/popunder/",
            "/tracker/",
            "/tracking/",
            "/analytics/",
            "/pixel/",
            "/beacon/",
            "/telemetry/",
            "ad.js",
            "ads.js",
            "analytics.js",
            "track.js",
            "tracking.js",
            "pixel.js",
            "ga.js",
            "gtag.js",
            "fbevents.js",
            "/pagead/",
            "/adsense/",
            "doubleclick",
            "ad-manager",
            "admanager",
            "adserver",
            "ad_request",
            "adrequest",
        };

        synchronized (blockPatterns) {
            for (String pattern : patterns) {
                blockPatterns.add(pattern.toLowerCase());
            }
        }

        // ── Whitelist (never block) ──
        String[] trusted = {
            "duckduckgo.com",
            "google.com",
            "googleapis.com",
            "gstatic.com",
            "youtube.com",
            "youtu.be",
            "wikipedia.org",
            "wikimedia.org",
            "github.com",
            "githubusercontent.com",
            "cloudflare.com",
            "cloudflare.net",
            "jsdelivr.net",      // CDN
            "unpkg.com",         // CDN
            "bootstrapcdn.com",  // CDN
        };

        for (String domain : trusted) {
            whitelistDomains.add(domain);
        }

        Log.d(TAG, "Built-in rules loaded: "
            + blockedDomains.size() + " domains, "
            + blockPatterns.size() + " patterns");
    }

    /**
     * Loads EasyList + EasyPrivacy from assets/ folder.
     * Runs in background thread — does not block UI.
     *
     * Parses ABP/uBlock filter format:
     *   ||ads.example.com^     → domain block
     *   @@||safe.example.com^  → whitelist
     *   /pattern/              → URL pattern block
     *   ##.selector            → CSS cosmetic (ignored here)
     *   ! Comment              → ignored
     */
    private void loadFilterFilesAsync() {
        loader.execute(() -> {
            int rulesAdded = 0;

            // Load EasyList
            rulesAdded += parseFilterFile(
                Constants.ADBLOCK_ASSET_DIR + "/" + Constants.EASYLIST_FILE,
                false
            );

            // Load EasyPrivacy
            rulesAdded += parseFilterFile(
                Constants.ADBLOCK_ASSET_DIR + "/" + Constants.EASYPRIVACY_FILE,
                false
            );

            isLoaded = true;
            Log.d(TAG, "Filter files loaded. Total new rules: " + rulesAdded);
            Log.d(TAG, "Total domains blocked: " + blockedDomains.size());
            Log.d(TAG, "Total patterns: " + blockPatterns.size());
        });
    }

    /**
     * Parses a single filter file from assets.
     * Returns count of rules successfully added.
     */
    private int parseFilterFile(String assetPath, boolean isCustom) {
        final int[] count = {0};
        final int[] skipped = {0};

        NetworkUtils.loadAssetFileLines(context, assetPath, line -> {

            // Skip empty lines and comments
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) {
                return;
            }

            // Skip CSS cosmetic rules (##, #@#, #?#)
            if (line.contains("##") || line.contains("#@#") || line.contains("#?#")) {
                skipped[0]++;
                return;
            }

            // Skip element hiding rules
            if (line.startsWith("#")) {
                skipped[0]++;
                return;
            }

            // ── Whitelist rule: @@||domain.com^ ──
            if (line.startsWith("@@")) {
                String domain = extractDomainFromRule(line.substring(2));
                if (domain != null) {
                    whitelistDomains.add(domain);
                    count[0]++;
                }
                return;
            }

            // ── Domain rule: ||ads.example.com^ ──
            if (line.startsWith("||")) {
                String domain = extractDomainFromRule(line);
                if (domain != null) {
                    // Respect cache size limit (low-end devices)
                    if (blockedDomains.size() < Constants.ADBLOCK_CACHE_SIZE) {
                        blockedDomains.add(domain);
                        count[0]++;
                    }
                }
                return;
            }

            // ── Pattern rule: /something/ ──
            if (line.startsWith("/") && line.endsWith("/")) {
                String pattern = line.substring(1, line.length() - 1).toLowerCase();
                if (!pattern.isEmpty() && pattern.length() > 3) {
                    synchronized (blockPatterns) {
                        if (blockPatterns.size() < 2000) { // Pattern limit
                            blockPatterns.add(pattern);
                            count[0]++;
                        }
                    }
                }
            }
        });

        Log.d(TAG, assetPath + ": " + count[0] + " rules, "
            + skipped[0] + " cosmetic rules skipped");
        return count[0];
    }

    /**
     * Extracts domain from ABP filter rule.
     *
     * Examples:
     *   "||ads.example.com^"          → "ads.example.com"
     *   "||tracker.net^$third-party"  → "tracker.net"
     *   "@@||safe.com^"               → "safe.com"
     */
    private String extractDomainFromRule(String rule) {
        try {
            // Remove leading ||
            String domain = rule;
            if (domain.startsWith("||")) {
                domain = domain.substring(2);
            }

            // Remove trailing ^ and options after ^
            int caretIndex = domain.indexOf('^');
            if (caretIndex > 0) {
                domain = domain.substring(0, caretIndex);
            }

            // Remove trailing /
            if (domain.endsWith("/")) {
                domain = domain.substring(0, domain.length() - 1);
            }

            // Remove options after $
            int dollarIndex = domain.indexOf('$');
            if (dollarIndex > 0) {
                domain = domain.substring(0, dollarIndex);
            }

            // Validate — must look like a domain
            domain = domain.toLowerCase().trim();
            if (domain.isEmpty() || domain.contains(" ")) return null;
            if (!domain.contains(".")) return null;
            if (domain.length() > 100) return null; // Sanity check

            return domain;

        } catch (Exception e) {
            return null;
        }
    }

    // ═════════════════════════════════════════════
    // CUSTOM RULES API
    // ═════════════════════════════════════════════

    /**
     * Add a custom domain to block list.
     * Called from Settings → "Add custom filter"
     */
    public void addCustomBlock(String domain) {
        if (domain == null || domain.trim().isEmpty()) return;
        String clean = UrlValidator.extractHost(
            domain.startsWith("http") ? domain : "https://" + domain
        );
        if (!clean.isEmpty()) {
            customBlockedDomains.add(clean);
            Log.d(TAG, "Custom block added: " + clean);
        }
    }

    /**
     * Add a custom domain to whitelist.
     */
    public void addCustomWhitelist(String domain) {
        if (domain == null || domain.trim().isEmpty()) return;
        String clean = UrlValidator.extractHost(
            domain.startsWith("http") ? domain : "https://" + domain
        );
        if (!clean.isEmpty()) {
            customWhitelistDomains.add(clean);
            Log.d(TAG, "Custom whitelist added: " + clean);
        }
    }

    /**
     * Remove a custom block rule.
     */
    public void removeCustomBlock(String domain) {
        customBlockedDomains.remove(domain);
    }

    /**
     * Remove a custom whitelist rule.
     */
    public void removeCustomWhitelist(String domain) {
        customWhitelistDomains.remove(domain);
    }

    /**
     * Returns all custom block rules as list.
     */
    public List<String> getCustomBlockList() {
        return new ArrayList<>(customBlockedDomains);
    }

    // ═════════════════════════════════════════════
    // SETTINGS API
    // ═════════════════════════════════════════════

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        Log.d(TAG, "AdBlock " + (enabled ? "enabled" : "disabled"));
    }

    public boolean isEnabled() { return enabled; }

    public void setBlockThirdPartyScripts(boolean block) {
        this.blockThirdPartyScripts = block;
    }

    public boolean isLoaded() { return isLoaded; }

    // ═════════════════════════════════════════════
    // STATISTICS
    // ═════════════════════════════════════════════

    public int getTotalBlocked() { return totalBlocked.get(); }
    public int getTotalAllowed() { return totalAllowed.get(); }

    public void resetStats() {
        totalBlocked.set(0);
        totalAllowed.set(0);
    }

    /**
     * Returns stats as a readable map.
     * Used by BrowserUI to show blocking stats.
     */
    public Map<String, String> getStatsMap() {
        Map<String, String> stats = new HashMap<>();
        stats.put("blocked",       String.valueOf(totalBlocked.get()));
        stats.put("allowed",       String.valueOf(totalAllowed.get()));
        stats.put("domain_rules",  String.valueOf(blockedDomains.size()));
        stats.put("pattern_rules", String.valueOf(blockPatterns.size()));
        stats.put("whitelist",     String.valueOf(whitelistDomains.size()));
        stats.put("custom_block",  String.valueOf(customBlockedDomains.size()));
        stats.put("loaded",        String.valueOf(isLoaded));
        return stats;
    }

    // ═════════════════════════════════════════════
    // TRUSTED CDNs (never block JS from these)
    // ═════════════════════════════════════════════

    private static final String[] TRUSTED_CDNS = {
        "jsdelivr.net",
        "unpkg.com",
        "cdnjs.cloudflare.com",
        "bootstrapcdn.com",
        "jquery.com",
        "cloudflare.com",
        "fastly.net",
        "akamaihd.net",
        "akamai.net",
        "gstatic.com",
        "googleapis.com",
        "github.io",
    };
                  }
