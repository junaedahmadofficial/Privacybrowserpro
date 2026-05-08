package com.privacybrowser.browser;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.privacybrowser.utils.Constants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * TabManager.java
 * Manages all browser tabs — creation, switching, closing.
 *
 * Architecture:
 *  ┌─────────────────────────────────────────────┐
 *  │               TabManager                    │
 *  │                                             │
 *  │  tabs: List<Tab>  ←→  activeTab: Tab        │
 *  │                                             │
 *  │  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐      │
 *  │  │ Tab1 │ │ Tab2 │ │ Tab3*│ │ Tab4 │      │
 *  │  │Normal│ │Normal│ │Privt │ │Normal│      │
 *  │  └──────┘ └──────┘ └──────┘ └──────┘      │
 *  │                      ↑ active               │
 *  │                                             │
 *  │  Memory Strategy:                           │
 *  │   Active tab   → WebView alive              │
 *  │   Background   → WebView frozen             │
 *  │   Max 10 tabs  → Oldest auto-closed         │
 *  └─────────────────────────────────────────────┘
 *
 * Memory Safety:
 *  WebView is only ACTIVE for the current tab.
 *  Background tabs are suspended to save RAM.
 *  Private tabs leave ZERO trace on close.
 */
public class TabManager {

    private static final String TAG = "TabManager";

    // ─────────────────────────────────────────────
    // Singleton
    // ─────────────────────────────────────────────
    private static volatile TabManager instance;

    public static TabManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TabManager.class) {
                if (instance == null) {
                    instance = new TabManager(
                        context.getApplicationContext()
                    );
                }
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────
    // Tab Model
    // ─────────────────────────────────────────────

    /**
     * Represents a single browser tab.
     * Each tab holds its own state — URL, title,
     * thumbnail, private flag, and load progress.
     */
    public static class Tab {

        // Unique ID — survives activity recreations
        public final String  id;

        // Display state
        public String  url;
        public String  title;
        public Bitmap  thumbnail;   // Tab switcher preview
        public int     progress;    // 0–100 load progress
        public boolean isLoading;
        public boolean hasError;
        public boolean isPrivate;

        // Timestamps
        public long    createdAt;
        public long    lastAccessedAt;

        // WebView state bundle — saved when tab goes background
        // android.os.Bundle webViewState;  ← added in BrowserEngine

        // Constructor
        public Tab(boolean isPrivate) {
            this.id              = UUID.randomUUID().toString();
            this.url             = Constants.BLANK_PAGE;
            this.title           = isPrivate
                                    ? Constants.PRIVATE_MODE_LABEL
                                    : "New Tab";
            this.isPrivate       = isPrivate;
            this.progress        = 0;
            this.isLoading       = false;
            this.hasError        = false;
            this.createdAt       = System.currentTimeMillis();
            this.lastAccessedAt  = System.currentTimeMillis();
        }

        /**
         * Updates last accessed time.
         * Called when tab is switched to.
         */
        public void touch() {
            this.lastAccessedAt = System.currentTimeMillis();
        }

        /**
         * Returns display title — falls back to URL,
         * then to "New Tab" if both empty.
         */
        public String getDisplayTitle() {
            if (title != null && !title.isEmpty()) return title;
            if (url  != null && !url.equals(Constants.BLANK_PAGE)) {
                // Show just the host as title
                try {
                    java.net.URI uri = new java.net.URI(url);
                    String host = uri.getHost();
                    if (host != null) return host;
                } catch (Exception ignored) {}
                return url;
            }
            return isPrivate ? Constants.PRIVATE_MODE_LABEL : "New Tab";
        }

        /**
         * Returns true if tab is on a blank/new tab page.
         */
        public boolean isBlank() {
            return url == null
                || url.isEmpty()
                || url.equals(Constants.BLANK_PAGE)
                || url.equals("about:newtab");
        }

        @Override
        public String toString() {
            return "Tab{"
                + "id=" + id.substring(0, 8)
                + ", title='" + title + "'"
                + ", url='" + url + "'"
                + ", private=" + isPrivate
                + ", progress=" + progress
                + "}";
        }
    }

    // ─────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────
    private final List<Tab> tabs;
    private Tab activeTab;
    private final Context context;

    // Listener — UI updates on tab changes
    private TabChangeListener listener;

    // ─────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────
    private TabManager(Context context) {
        this.context = context;
        this.tabs    = Collections.synchronizedList(new ArrayList<>());

        // Open one default tab on start
        Tab defaultTab = new Tab(false);
        tabs.add(defaultTab);
        activeTab = defaultTab;

        Log.d(TAG, "TabManager initialized with default tab");
    }

    // ═════════════════════════════════════════════
    // LISTENER INTERFACE
    // ═════════════════════════════════════════════

    /**
     * Callback interface for tab events.
     * MainActivity / BrowserUI implements this.
     */
    public interface TabChangeListener {
        void onTabAdded(Tab tab, int position);
        void onTabClosed(Tab tab, int position);
        void onTabSwitched(Tab tab, int position);
        void onTabUpdated(Tab tab, int position);
        void onAllTabsClosed();
    }

    public void setTabChangeListener(TabChangeListener listener) {
        this.listener = listener;
    }

    // ═════════════════════════════════════════════
    // TAB CREATION
    // ═════════════════════════════════════════════

    /**
     * Opens a new normal tab.
     * Returns the new Tab object.
     */
    public Tab openNewTab() {
        return openNewTab(false, null);
    }

    /**
     * Opens a new private tab.
     */
    public Tab openNewPrivateTab() {
        return openNewTab(true, null);
    }

    /**
     * Opens a new tab and immediately loads a URL.
     *
     * @param isPrivate Whether this is a private tab
     * @param url       URL to load (null = blank page)
     * @return          The newly created Tab
     */
    public Tab openNewTab(boolean isPrivate, String url) {

        // ── Enforce tab limit ──
        if (tabs.size() >= Constants.MAX_TABS) {
            Log.w(TAG, "Max tabs reached (" + Constants.MAX_TABS + "). "
                + "Closing oldest tab.");
            closeOldestBackgroundTab();
        }

        // ── Create new tab ──
        Tab newTab = new Tab(isPrivate);
        if (url != null && !url.isEmpty()) {
            newTab.url = url;
        }

        // ── Add to list ──
        synchronized (tabs) {
            tabs.add(newTab);
        }

        int position = tabs.indexOf(newTab);
        Log.d(TAG, "New tab opened: " + newTab.id
            + " | Private: " + isPrivate
            + " | Total tabs: " + tabs.size());

        // Notify UI
        if (listener != null) {
            listener.onTabAdded(newTab, position);
        }

        // Auto-switch to new tab
        switchToTab(newTab.id);

        return newTab;
    }

    // ═════════════════════════════════════════════
    // TAB SWITCHING
    // ═════════════════════════════════════════════

    /**
     * Switches active tab by tab ID.
     * Suspends the previous tab's WebView.
     * Resumes the new tab's WebView.
     *
     * @param tabId ID of tab to switch to
     * @return true if switch was successful
     */
    public boolean switchToTab(String tabId) {
        Tab target = findById(tabId);
        if (target == null) {
            Log.w(TAG, "switchToTab: tab not found — " + tabId);
            return false;
        }

        // Already active
        if (activeTab != null && activeTab.id.equals(tabId)) {
            return true;
        }

        // Suspend current active tab
        if (activeTab != null) {
            suspendTab(activeTab);
        }

        // Switch
        activeTab = target;
        activeTab.touch();

        int position = tabs.indexOf(activeTab);
        Log.d(TAG, "Switched to tab: " + activeTab.id
            + " | pos: " + position);

        // Notify UI
        if (listener != null) {
            listener.onTabSwitched(activeTab, position);
        }

        return true;
    }

    /**
     * Switches to tab by index position.
     */
    public boolean switchToTab(int position) {
        synchronized (tabs) {
            if (position < 0 || position >= tabs.size()) return false;
            return switchToTab(tabs.get(position).id);
        }
    }

    /**
     * Switches to next tab (wraps around).
     */
    public void switchToNextTab() {
        int current = getActiveTabIndex();
        int next    = (current + 1) % tabs.size();
        switchToTab(next);
    }

    /**
     * Switches to previous tab (wraps around).
     */
    public void switchToPreviousTab() {
        int current  = getActiveTabIndex();
        int previous = (current - 1 + tabs.size()) % tabs.size();
        switchToTab(previous);
    }

    // ═════════════════════════════════════════════
    // TAB CLOSING
    // ═════════════════════════════════════════════

    /**
     * Closes a tab by ID.
     *
     * Private tab close:
     *   → Wipes WebView cookies/cache for that session
     *   → Calls HistoryManager.destroyPrivateSession()
     *
     * After close:
     *   → Switches to adjacent tab
     *   → If no tabs remain, opens a fresh default tab
     *
     * @param tabId ID of tab to close
     */
    public void closeTab(String tabId) {
        Tab target = findById(tabId);
        if (target == null) {
            Log.w(TAG, "closeTab: tab not found — " + tabId);
            return;
        }

        int position = tabs.indexOf(target);

        // ── Private tab cleanup ──
        if (target.isPrivate) {
            cleanupPrivateTab(target);
        }

        // ── Remove thumbnail from memory ──
        if (target.thumbnail != null) {
            target.thumbnail.recycle();
            target.thumbnail = null;
        }

        // ── Remove from list ──
        synchronized (tabs) {
            tabs.remove(target);
        }

        Log.d(TAG, "Tab closed: " + target.id
            + " | Remaining: " + tabs.size());

        // Notify UI
        if (listener != null) {
            listener.onTabClosed(target, position);
        }

        // ── Handle active tab closed ──
        if (activeTab != null && activeTab.id.equals(tabId)) {
            activeTab = null;
            handleActiveTabClosed(position);
        }
    }

    /**
     * Closes the currently active tab.
     */
    public void closeActiveTab() {
        if (activeTab != null) {
            closeTab(activeTab.id);
        }
    }

    /**
     * Closes all tabs and opens a fresh default tab.
     * Called from Settings → "Close All Tabs"
     */
    public void closeAllTabs() {
        // Cleanup all private tabs first
        synchronized (tabs) {
            for (Tab tab : tabs) {
                if (tab.isPrivate) {
                    cleanupPrivateTab(tab);
                }
                if (tab.thumbnail != null) {
                    tab.thumbnail.recycle();
                    tab.thumbnail = null;
                }
            }
            tabs.clear();
        }

        activeTab = null;

        Log.d(TAG, "All tabs closed");

        if (listener != null) {
            listener.onAllTabsClosed();
        }

        // Open fresh tab
        openNewTab();
    }

    /**
     * Closes all private tabs only.
     * Normal tabs remain untouched.
     */
    public void closeAllPrivateTabs() {
        List<String> privateTabIds = new ArrayList<>();

        synchronized (tabs) {
            for (Tab tab : tabs) {
                if (tab.isPrivate) {
                    privateTabIds.add(tab.id);
                }
            }
        }

        for (String id : privateTabIds) {
            closeTab(id);
        }

        Log.d(TAG, "Closed " + privateTabIds.size() + " private tabs");
    }

    // ═════════════════════════════════════════════
    // TAB STATE UPDATES
    // ═════════════════════════════════════════════

    /**
     * Updates a tab's URL.
     * Called by BrowserEngine when URL changes.
     */
    public void updateTabUrl(String tabId, String url) {
        Tab tab = findById(tabId);
        if (tab == null) return;
        tab.url = url;
        notifyTabUpdated(tab);
    }

    /**
     * Updates a tab's title.
     * Called by BrowserEngine.onReceivedTitle()
     */
    public void updateTabTitle(String tabId, String title) {
        Tab tab = findById(tabId);
        if (tab == null) return;
        tab.title = title;
        notifyTabUpdated(tab);
    }

    /**
     * Updates a tab's load progress (0–100).
     * Called by BrowserEngine.onProgressChanged()
     */
    public void updateTabProgress(String tabId, int progress) {
        Tab tab = findById(tabId);
        if (tab == null) return;
        tab.progress  = progress;
        tab.isLoading = (progress < Constants.PROGRESS_COMPLETE);
        notifyTabUpdated(tab);
    }

    /**
     * Saves a thumbnail screenshot for tab switcher UI.
     * Called after page finishes loading.
     *
     * @param tabId     Tab ID
     * @param thumbnail Bitmap snapshot (scaled down)
     */
    public void updateTabThumbnail(String tabId, Bitmap thumbnail) {
        Tab tab = findById(tabId);
        if (tab == null) return;

        // Recycle old thumbnail to free memory
        if (tab.thumbnail != null && !tab.thumbnail.isRecycled()) {
            tab.thumbnail.recycle();
        }

        // Scale down to save RAM
        if (thumbnail != null) {
            tab.thumbnail = Bitmap.createScaledBitmap(
                thumbnail,
                Constants.TAB_THUMBNAIL_WIDTH,
                Constants.TAB_THUMBNAIL_HEIGHT,
                true
            );
        }

        notifyTabUpdated(tab);
    }

    /**
     * Marks a tab as having a load error.
     */
    public void setTabError(String tabId, boolean hasError) {
        Tab tab = findById(tabId);
        if (tab == null) return;
        tab.hasError  = hasError;
        tab.isLoading = false;
        notifyTabUpdated(tab);
    }

    // ═════════════════════════════════════════════
    // GETTERS
    // ═════════════════════════════════════════════

    /**
     * Returns the currently active tab.
     */
    public Tab getActiveTab() {
        return activeTab;
    }

    /**
     * Returns all tabs (read-only copy).
     */
    public List<Tab> getAllTabs() {
        synchronized (tabs) {
            return new ArrayList<>(tabs);
        }
    }

    /**
     * Returns only normal (non-private) tabs.
     */
    public List<Tab> getNormalTabs() {
        List<Tab> normal = new ArrayList<>();
        synchronized (tabs) {
            for (Tab tab : tabs) {
                if (!tab.isPrivate) normal.add(tab);
            }
        }
        return normal;
    }

    /**
     * Returns only private tabs.
     */
    public List<Tab> getPrivateTabs() {
        List<Tab> privateTabs = new ArrayList<>();
        synchronized (tabs) {
            for (Tab tab : tabs) {
                if (tab.isPrivate) privateTabs.add(tab);
            }
        }
        return privateTabs;
    }

    /**
     * Returns total tab count.
     */
    public int getTabCount() {
        return tabs.size();
    }

    /**
     * Returns private tab count.
     */
    public int getPrivateTabCount() {
        int count = 0;
        synchronized (tabs) {
            for (Tab tab : tabs) {
                if (tab.isPrivate) count++;
            }
        }
        return count;
    }

    /**
     * Returns index of active tab.
     */
    public int getActiveTabIndex() {
        if (activeTab == null) return -1;
        return tabs.indexOf(activeTab);
    }

    /**
     * Returns true if any private tab is open.
     */
    public boolean hasPrivateTabs() {
        return getPrivateTabCount() > 0;
    }

    /**
     * Returns true if current active tab is private.
     */
    public boolean isActiveTabPrivate() {
        return activeTab != null && activeTab.isPrivate;
    }

    /**
     * Finds a tab by its ID.
     * Returns null if not found.
     */
    public Tab findById(String tabId) {
        if (tabId == null) return null;
        synchronized (tabs) {
            for (Tab tab : tabs) {
                if (tab.id.equals(tabId)) return tab;
            }
        }
        return null;
    }

    // ═════════════════════════════════════════════
    // MEMORY MANAGEMENT
    // ═════════════════════════════════════════════

    /**
     * Suspends a tab going to background.
     * Freezes WebView rendering to save CPU/RAM.
     * BrowserEngine handles actual WebView pause.
     */
    private void suspendTab(Tab tab) {
        if (tab == null) return;
        tab.isLoading = false;
        Log.d(TAG, "Tab suspended: " + tab.id);
    }

    /**
     * Closes the oldest non-active background tab.
     * Called when MAX_TABS limit is hit.
     *
     * Strategy:
     *  Find background tab with oldest lastAccessedAt.
     *  Private tabs are preferred for closure (privacy).
     */
    private void closeOldestBackgroundTab() {
        Tab oldest = null;
        long oldestTime = Long.MAX_VALUE;

        synchronized (tabs) {
            for (Tab tab : tabs) {
                // Skip active tab
                if (activeTab != null && tab.id.equals(activeTab.id)) continue;

                // Prefer closing private tabs first
                if (tab.isPrivate) {
                    oldest = tab;
                    break;
                }

                // Otherwise find oldest by access time
                if (tab.lastAccessedAt < oldestTime) {
                    oldestTime = tab.lastAccessedAt;
                    oldest     = tab;
                }
            }
        }

        if (oldest != null) {
            Log.d(TAG, "Auto-closing oldest tab: " + oldest.id);
            closeTab(oldest.id);
        }
    }

    /**
     * After active tab is closed, determine which
     * tab to switch to next.
     *
     * Strategy:
     *  Try tab at same position → else previous → else first
     */
    private void handleActiveTabClosed(int closedPosition) {
        synchronized (tabs) {
            if (tabs.isEmpty()) {
                // No tabs left — open fresh one
                if (listener != null) listener.onAllTabsClosed();
                openNewTab();
                return;
            }

            // Switch to tab at same position (or last)
            int targetPos = Math.min(closedPosition, tabs.size() - 1);
            switchToTab(targetPos);
        }
    }

    /**
     * Cleans up a private tab on close.
     * Wipes all traces — cookies, cache, history.
     */
    private void cleanupPrivateTab(Tab tab) {
        Log.d(TAG, "Cleaning up private tab: " + tab.id);

        // Wipe private session history from RAM
        HistoryManager.getInstance(context).destroyPrivateSession();

        // WebView cookie/cache wipe is handled in BrowserEngine
        // when it detects a private tab is being closed
        Log.d(TAG, "Private tab cleanup complete: " + tab.id);
    }

    // ═════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════

    /**
     * Notifies listener of tab state update.
     */
    private void notifyTabUpdated(Tab tab) {
        if (listener == null || tab == null) return;
        int position = tabs.indexOf(tab);
        if (position >= 0) {
            listener.onTabUpdated(tab, position);
        }
    }

    /**
     * Debug dump — prints all tab states to log.
     */
    public void debugDump() {
        Log.d(TAG, "═══ TabManager State ═══");
        Log.d(TAG, "Total tabs: " + tabs.size());
        Log.d(TAG, "Active: " + (activeTab != null ? activeTab.id : "none"));
        synchronized (tabs) {
            for (int i = 0; i < tabs.size(); i++) {
                Tab t = tabs.get(i);
                Log.d(TAG, "  [" + i + "] " + t.toString()
                    + (t == activeTab ? " ← ACTIVE" : ""));
            }
        }
        Log.d(TAG, "════════════════════════");
    }
                       }
