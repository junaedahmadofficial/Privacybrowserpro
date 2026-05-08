package com.privacybrowser;

import android.app.Application;
import android.util.Log;
import android.webkit.WebView;

import com.privacybrowser.browser.AdBlockEngine;
import com.privacybrowser.browser.HistoryManager;

/**
 * PrivacyBrowserApp.java
 * Application class — runs once when app process starts.
 *
 * Responsibilities:
 *  - Initialize AdBlockEngine early (loads filter lists)
 *  - Enable WebView debugging in debug builds
 *  - Set WebView data directory suffix (multi-process isolation)
 */
public class PrivacyBrowserApp extends Application {

    private static final String TAG = "PrivacyBrowserApp";

    @Override
    public void onCreate() {
        super.onCreate();

        Log.d(TAG, "App starting...");

        // ── WebView multi-process setup (API 28+) ──
        // Required to avoid crash when multiple processes
        // use WebView with same data directory
        if (android.os.Build.VERSION.SDK_INT
                >= android.os.Build.VERSION_CODES.P) {
            String processName = getProcessName();
            if (!getPackageName().equals(processName)) {
                WebView.setDataDirectorySuffix(processName);
            }
        }

        // ── Enable WebView debugging in debug builds ──
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
            Log.d(TAG, "WebView debugging enabled");
        }

        // ── Pre-initialize AdBlockEngine ──
        // Loads filter rules in background thread
        // By the time user loads first page, rules are ready
        AdBlockEngine.getInstance(this);
        Log.d(TAG, "AdBlockEngine pre-initialized");

        // ── Pre-initialize HistoryManager ──
        HistoryManager.getInstance(this);
        Log.d(TAG, "HistoryManager pre-initialized");

        Log.d(TAG, "App initialized successfully");
    }
            }
