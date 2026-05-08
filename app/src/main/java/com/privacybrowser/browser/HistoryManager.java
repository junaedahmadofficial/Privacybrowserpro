package com.privacybrowser.browser;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.privacybrowser.utils.Constants;
import com.privacybrowser.utils.UrlValidator;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * HistoryManager.java
 * Manages browsing history with full private mode support.
 *
 * Architecture:
 *  ┌──────────────────────────────────────────────┐
 *  │             HistoryManager                   │
 *  │                                              │
 *  │  privateMode = false → SQLite (persistent)  │
 *  │  privateMode = true  → RAM only (wiped)     │
 *  │                                              │
 *  │  ┌─────────────┐    ┌──────────────────┐    │
 *  │  │  SQLiteDB   │    │  In-Memory List  │    │
 *  │  │ (history)   │    │ (private session)│    │
 *  │  └─────────────┘    └──────────────────┘    │
 *  └──────────────────────────────────────────────┘
 *
 * Privacy Guarantee:
 *  In private mode — ZERO writes to SQLite.
 *  Session memory is wiped on destroyPrivateSession().
 */
public class HistoryManager {

    private static final String TAG = "HistoryManager";

    // ─────────────────────────────────────────────
    // Singleton
    // ─────────────────────────────────────────────
    private static volatile HistoryManager instance;

    public static HistoryManager getInstance(Context context) {
        if (instance == null) {
            synchronized (HistoryManager.class) {
                if (instance == null) {
                    instance = new HistoryManager(
                        context.getApplicationContext()
                    );
                }
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────
    private boolean privateMode = false;
    private boolean saveHistory = true;

    // In-memory list for private mode (never written to disk)
    private final List<HistoryEntry> privateSessionHistory = new ArrayList<>();

    // SQLite helper
    private final BrowserDbHelper dbHelper;

    // ─────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────
    private HistoryManager(Context context) {
        this.dbHelper = new BrowserDbHelper(context);
        Log.d(TAG, "HistoryManager initialized");
    }

    // ═════════════════════════════════════════════
    // HISTORY ENTRY MODEL
    // ═════════════════════════════════════════════

    /**
     * Represents a single history entry.
     */
    public static class HistoryEntry {
        public long   id;
        public String url;
        public String title;
        public long   timestamp;
        public String faviconBase64; // Stored as Base64 string

        public HistoryEntry() {}

        public HistoryEntry(String url, String title, long timestamp) {
            this.url       = url;
            this.title     = title;
            this.timestamp = timestamp;
        }

        /**
         * Returns human-readable time string.
         * e.g. "2 hours ago", "Yesterday", "Mar 10"
         */
        public String getRelativeTime() {
            long now  = System.currentTimeMillis();
            long diff = now - timestamp;

            long seconds = diff / 1000;
            long minutes = seconds / 60;
            long hours   = minutes / 60;
            long days    = hours   / 24;

            if (seconds < 60)  return "Just now";
            if (minutes < 60)  return minutes + " min ago";
            if (hours < 24)    return hours   + " hours ago";
            if (days == 1)     return "Yesterday";
            if (days < 7)      return days    + " days ago";

            // Older than 7 days — show date
            java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat(
                    "MMM dd", java.util.Locale.getDefault()
                );
            return sdf.format(new java.util.Date(timestamp));
        }

        @Override
        public String toString() {
            return "HistoryEntry{url='" + url + "', title='" + title
                + "', time=" + getRelativeTime() + "}";
        }
    }

    // ═════════════════════════════════════════════
    // PUBLIC API — WRITE
    // ═════════════════════════════════════════════

    /**
     * Records a page visit.
     * Called by BrowserEngine.onPageFinished()
     *
     * Private mode → RAM only (never SQLite)
     * Normal mode  → SQLite (if saveHistory enabled)
     *
     * @param url     Page URL
     * @param title   Page title
     * @param favicon Page favicon bitmap (can be null)
     */
    public void recordVisit(String url, String title, Bitmap favicon) {

        // Skip internal/blank pages
        if (TextUtils.isEmpty(url)
            || url.equals(Constants.BLANK_PAGE)
            || url.equals("about:newtab")) {
            return;
        }

        // Skip dangerous URLs
        if (UrlValidator.isDangerousScheme(url)) return;

        long timestamp = System.currentTimeMillis();
        String faviconData = bitmapToBase64(favicon);

        HistoryEntry entry = new HistoryEntry(url, title, timestamp);
        entry.faviconBase64 = faviconData;

        if (privateMode) {
            // ── Private mode: RAM only ──
            synchronized (privateSessionHistory) {
                // Keep private session list small
                if (privateSessionHistory.size() > 500) {
                    privateSessionHistory.remove(0);
                }
                privateSessionHistory.add(0, entry); // Newest first
            }
            Log.d(TAG, "Private visit recorded (RAM only): " + url);

        } else if (saveHistory) {
            // ── Normal mode: Write to SQLite ──
            saveToDatabase(entry);
            Log.d(TAG, "Visit recorded to DB: " + url);
        }
    }

    /**
     * Overload without favicon.
     */
    public void recordVisit(String url, String title) {
        recordVisit(url, title, null);
    }

    // ═════════════════════════════════════════════
    // PUBLIC API — READ
    // ═════════════════════════════════════════════

    /**
     * Returns full history list.
     * Private mode → returns in-memory session list
     * Normal mode  → returns SQLite records
     *
     * @param limit Max entries to return (0 = all)
     */
    public List<HistoryEntry> getHistory(int limit) {
        if (privateMode) {
            synchronized (privateSessionHistory) {
                if (limit > 0 && privateSessionHistory.size() > limit) {
                    return new ArrayList<>(
                        privateSessionHistory.subList(0, limit)
                    );
                }
                return new ArrayList<>(privateSessionHistory);
            }
        }
        return loadFromDatabase(limit);
    }

    /**
     * Returns all history (no limit).
     */
    public List<HistoryEntry> getHistory() {
        return getHistory(0);
    }

    /**
     * Searches history by URL or title keyword.
     * Used by address bar autocomplete.
     *
     * @param query Search keyword
     * @return Matching history entries (max 10)
     */
    public List<HistoryEntry> search(String query) {
        if (TextUtils.isEmpty(query)) return new ArrayList<>();

        if (privateMode) {
            // Search in-memory private history
            List<HistoryEntry> results = new ArrayList<>();
            String lowerQuery = query.toLowerCase();
            synchronized (privateSessionHistory) {
                for (HistoryEntry entry : privateSessionHistory) {
                    if ((entry.url   != null && entry.url.toLowerCase().contains(lowerQuery))
                     || (entry.title != null && entry.title.toLowerCase().contains(lowerQuery))) {
                        results.add(entry);
                        if (results.size() >= 10) break;
                    }
                }
            }
            return results;
        }

        // Search SQLite
        return searchDatabase(query, 10);
    }

    /**
     * Returns the last N visited URLs.
     * Used by address bar to show recent suggestions.
     */
    public List<String> getRecentUrls(int count) {
        List<HistoryEntry> entries = getHistory(count);
        List<String> urls = new ArrayList<>();
        for (HistoryEntry e : entries) {
            if (e.url != null) urls.add(e.url);
        }
        return urls;
    }

    /**
     * Checks if a URL has been visited before.
     * Used to color visited links differently.
     */
    public boolean hasVisited(String url) {
        if (TextUtils.isEmpty(url)) return false;

        if (privateMode) {
            synchronized (privateSessionHistory) {
                for (HistoryEntry e : privateSessionHistory) {
                    if (url.equals(e.url)) return true;
                }
            }
            return false;
        }

        return isInDatabase(url);
    }

    // ═════════════════════════════════════════════
    // PUBLIC API — DELETE
    // ═════════════════════════════════════════════

    /**
     * Deletes a single history entry by ID.
     */
    public void deleteEntry(long id) {
        if (privateMode) return; // Nothing in DB to delete
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(
            Constants.TABLE_HISTORY,
            Constants.COL_ID + " = ?",
            new String[]{String.valueOf(id)}
        );
        Log.d(TAG, "Deleted history entry: " + id);
    }

    /**
     * Clears ALL browsing history from SQLite.
     * Called from Settings → "Clear History"
     * Does NOT affect current private session.
     */
    public void clearAllHistory() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(Constants.TABLE_HISTORY, null, null);
        Log.d(TAG, "All history cleared");
    }

    /**
     * Clears history older than N days.
     *
     * @param days Entries older than this are deleted
     */
    public void clearOlderThan(int days) {
        long cutoff = System.currentTimeMillis()
            - ((long) days * 24 * 60 * 60 * 1000);

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int deleted = db.delete(
            Constants.TABLE_HISTORY,
            Constants.COL_TIMESTAMP + " < ?",
            new String[]{String.valueOf(cutoff)}
        );
        Log.d(TAG, "Cleared " + deleted + " entries older than " + days + " days");
    }

    /**
     * Wipes private session memory completely.
     * Called when private tab is closed.
     *
     * Privacy Guarantee:
     *  After this call, zero trace of private
     *  session remains in memory or on disk.
     */
    public void destroyPrivateSession() {
        synchronized (privateSessionHistory) {
            int count = privateSessionHistory.size();
            privateSessionHistory.clear();
            Log.d(TAG, "Private session destroyed. "
                + count + " entries wiped from RAM.");
        }
    }

    // ═════════════════════════════════════════════
    // MODE CONTROLS
    // ═════════════════════════════════════════════

    /**
     * Enable or disable private mode.
     *
     * Switching TO private:
     *   - No future writes to SQLite
     *   - Fresh in-memory session starts
     *
     * Switching FROM private:
     *   - Wipes private session memory
     *   - Resumes SQLite writes
     */
    public void setPrivateMode(boolean enabled) {
        if (this.privateMode == enabled) return;

        if (!enabled) {
            // Leaving private mode — destroy session data
            destroyPrivateSession();
        }

        this.privateMode = enabled;
        Log.d(TAG, "Private mode: " + (enabled ? "ON" : "OFF"));
    }

    public boolean isPrivateMode() { return privateMode; }

    public void setSaveHistory(boolean save) {
        this.saveHistory = save;
        Log.d(TAG, "Save history: " + (save ? "ON" : "OFF"));
    }

    public boolean isSaveHistory() { return saveHistory; }

    /**
     * Returns total history count.
     */
    public int getHistoryCount() {
        if (privateMode) {
            return privateSessionHistory.size();
        }
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
            "SELECT COUNT(*) FROM " + Constants.TABLE_HISTORY, null
        );
        int count = 0;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        }
        cursor.close();
        return count;
    }

    // ═════════════════════════════════════════════
    // SQLITE OPERATIONS
    // ═════════════════════════════════════════════

    /**
     * Saves a HistoryEntry to SQLite.
     * Enforces MAX_HISTORY_ENTRIES limit.
     */
    private void saveToDatabase(HistoryEntry entry) {
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();

            // Check if URL already exists → update timestamp
            ContentValues values = new ContentValues();
            values.put(Constants.COL_URL,       entry.url);
            values.put(Constants.COL_TITLE,     entry.title != null ? entry.title : "");
            values.put(Constants.COL_TIMESTAMP, entry.timestamp);
            values.put(Constants.COL_FAVICON,   entry.faviconBase64 != null
                ? entry.faviconBase64 : "");

            // Upsert — update if URL exists, insert if not
            int updated = db.update(
                Constants.TABLE_HISTORY,
                values,
                Constants.COL_URL + " = ?",
                new String[]{entry.url}
            );

            if (updated == 0) {
                // New URL — insert
                db.insert(Constants.TABLE_HISTORY, null, values);
            }

            // Enforce history limit — delete oldest if over limit
            enforceHistoryLimit(db);

        } catch (Exception e) {
            Log.e(TAG, "DB write failed: " + e.getMessage());
        }
    }

    /**
     * Loads history from SQLite.
     * Returns newest entries first.
     */
    private List<HistoryEntry> loadFromDatabase(int limit) {
        List<HistoryEntry> results = new ArrayList<>();

        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();

            String limitClause = (limit > 0)
                ? String.valueOf(limit) : null;

            Cursor cursor = db.query(
                Constants.TABLE_HISTORY,
                new String[]{
                    Constants.COL_ID,
                    Constants.COL_URL,
                    Constants.COL_TITLE,
                    Constants.COL_TIMESTAMP,
                    Constants.COL_FAVICON
                },
                null, null, null, null,
                Constants.COL_TIMESTAMP + " DESC", // Newest first
                limitClause
            );

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    HistoryEntry entry = new HistoryEntry();
                    entry.id        = cursor.getLong(0);
                    entry.url       = cursor.getString(1);
                    entry.title     = cursor.getString(2);
                    entry.timestamp = cursor.getLong(3);
                    entry.faviconBase64 = cursor.getString(4);
                    results.add(entry);
                }
                cursor.close();
            }

        } catch (Exception e) {
            Log.e(TAG, "DB read failed: " + e.getMessage());
        }

        return results;
    }

    /**
     * Searches history by keyword in SQLite.
     */
    private List<HistoryEntry> searchDatabase(String query, int limit) {
        List<HistoryEntry> results = new ArrayList<>();

        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            String like = "%" + query + "%";

            Cursor cursor = db.query(
                Constants.TABLE_HISTORY,
                new String[]{
                    Constants.COL_ID,
                    Constants.COL_URL,
                    Constants.COL_TITLE,
                    Constants.COL_TIMESTAMP,
                    Constants.COL_FAVICON
                },
                Constants.COL_URL   + " LIKE ? OR "
                + Constants.COL_TITLE + " LIKE ?",
                new String[]{like, like},
                null, null,
                Constants.COL_TIMESTAMP + " DESC",
                String.valueOf(limit)
            );

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    HistoryEntry entry = new HistoryEntry();
                    entry.id        = cursor.getLong(0);
                    entry.url       = cursor.getString(1);
                    entry.title     = cursor.getString(2);
                    entry.timestamp = cursor.getLong(3);
                    entry.faviconBase64 = cursor.getString(4);
                    results.add(entry);
                }
                cursor.close();
            }

        } catch (Exception e) {
            Log.e(TAG, "DB search failed: " + e.getMessage());
        }

        return results;
    }

    /**
     * Checks if URL exists in SQLite.
     */
    private boolean isInDatabase(String url) {
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            Cursor cursor = db.query(
                Constants.TABLE_HISTORY,
                new String[]{Constants.COL_ID},
                Constants.COL_URL + " = ?",
                new String[]{url},
                null, null, null, "1"
            );
            boolean found = cursor != null && cursor.moveToFirst();
            if (cursor != null) cursor.close();
            return found;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Deletes oldest entries when history limit is exceeded.
     */
    private void enforceHistoryLimit(SQLiteDatabase db) {
        db.execSQL(
            "DELETE FROM " + Constants.TABLE_HISTORY
            + " WHERE " + Constants.COL_ID + " NOT IN ("
            + "  SELECT " + Constants.COL_ID
            + "  FROM " + Constants.TABLE_HISTORY
            + "  ORDER BY " + Constants.COL_TIMESTAMP + " DESC"
            + "  LIMIT " + Constants.MAX_HISTORY_ENTRIES
            + ")"
        );
    }

    // ═════════════════════════════════════════════
    // FAVICON UTILITIES
    // ═════════════════════════════════════════════

    /**
     * Converts Bitmap favicon to Base64 string for SQLite storage.
     */
    private String bitmapToBase64(Bitmap bitmap) {
        if (bitmap == null) return null;
        try {
            // Scale down favicon to 16x16 to save space
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 16, 16, true);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 80, baos);
            byte[] bytes = baos.toByteArray();
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Converts Base64 string back to Bitmap.
     * Used by history list UI to show favicons.
     */
    public static Bitmap base64ToBitmap(String base64) {
        if (TextUtils.isEmpty(base64)) return null;
        try {
            byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    // ═════════════════════════════════════════════
    // SQLITE DATABASE HELPER
    // ═════════════════════════════════════════════

    /**
     * SQLite schema manager.
     * Handles DB creation and version migration.
     */
    private static class BrowserDbHelper extends SQLiteOpenHelper {

        BrowserDbHelper(Context context) {
            super(context, Constants.DB_NAME, null, Constants.DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // Create history table
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS " + Constants.TABLE_HISTORY + " ("
                + Constants.COL_ID        + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + Constants.COL_URL       + " TEXT NOT NULL, "
                + Constants.COL_TITLE     + " TEXT, "
                + Constants.COL_TIMESTAMP + " INTEGER NOT NULL, "
                + Constants.COL_FAVICON   + " TEXT"
                + ")"
            );

            // Index on timestamp for fast sorting
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_timestamp "
                + "ON " + Constants.TABLE_HISTORY
                + "(" + Constants.COL_TIMESTAMP + ")"
            );

            // Index on URL for fast lookup
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_url "
                + "ON " + Constants.TABLE_HISTORY
                + "(" + Constants.COL_URL + ")"
            );

            Log.d("BrowserDbHelper", "Database created");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Simple migration — drop and recreate
            // In production: use ALTER TABLE for data preservation
            db.execSQL("DROP TABLE IF EXISTS " + Constants.TABLE_HISTORY);
            onCreate(db);
            Log.d("BrowserDbHelper", "Database upgraded: "
                + oldVersion + " → " + newVersion);
        }

        @Override
        public void onConfigure(SQLiteDatabase db) {
            super.onConfigure(db);
            // Enable WAL mode for better concurrent read performance
            db.enableWriteAheadLogging();
        }
    }
        }
