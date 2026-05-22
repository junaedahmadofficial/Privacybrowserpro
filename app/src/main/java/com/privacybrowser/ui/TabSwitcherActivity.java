package com.privacybrowser.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.privacybrowser.R;
import com.privacybrowser.browser.TabManager;
import com.privacybrowser.utils.Constants;

import java.util.List;

/**
 * TabSwitcherActivity.java
 * Grid view of all open tabs.
 * User can tap to switch, swipe to close,
 * or open new tab from here.
 */
public class TabSwitcherActivity extends Activity
        implements TabAdapter.TabAdapterListener {

    private TabManager  tabManager;
    private TabAdapter  tabAdapter;
    private RecyclerView recyclerView;

    // Tab type toggle
    private boolean showingPrivate = false;
    private TextView tabNormalLabel;
    private TextView tabPrivateLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tab_switcher);

        tabManager = TabManager.getInstance(this);

        bindViews();
        setupRecyclerView();
        setupButtons();
        loadNormalTabs();
    }

    // ─────────────────────────────────────────────
    // SETUP
    // ─────────────────────────────────────────────

    private void bindViews() {
        recyclerView    = findViewById(R.id.tab_recycler_view);
        tabNormalLabel  = findViewById(R.id.tab_normal_label);
        tabPrivateLabel = findViewById(R.id.tab_private_label);
    }

    private void setupRecyclerView() {
        // 2-column grid
        GridLayoutManager layoutManager =
            new GridLayoutManager(this, 2);
        recyclerView.setLayoutManager(layoutManager);

        // Initialize adapter with normal tabs
        String activeId = tabManager.getActiveTab() != null
            ? tabManager.getActiveTab().id : "";

        tabAdapter = new TabAdapter(
            this,
            tabManager.getNormalTabs(),
            activeId
        );
        tabAdapter.setListener(this);
        recyclerView.setAdapter(tabAdapter);

        // Attach swipe-to-close
        TabAdapter.attachSwipeToClose(recyclerView, tabAdapter);
    }

    private void setupButtons() {

        // ── Done button ──
        View btnDone = findViewById(R.id.btn_done);
        if (btnDone != null) {
            btnDone.setOnClickListener(v -> {
                setResult(RESULT_CANCELED);
                finish();
            });
        }

        // ── Close All button ──
        View btnCloseAll = findViewById(R.id.btn_close_all);
        if (btnCloseAll != null) {
            btnCloseAll.setOnClickListener(v -> {
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Close All Tabs")
                    .setMessage(showingPrivate
                        ? "Close all private tabs?"
                        : "Close all normal tabs?")
                    .setPositiveButton("Close All", (d, w) -> {
                        if (showingPrivate) {
                            tabManager.closeAllPrivateTabs();
                            loadPrivateTabs();
                        } else {
                            tabManager.closeAllTabs();
                            // MainActivity will open new tab
                            Intent result = new Intent();
                            result.putExtra("new_tab", true);
                            result.putExtra(
                                Constants.INTENT_PRIVATE_MODE, false
                            );
                            setResult(RESULT_OK, result);
                            finish();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            });
        }

        // ── Normal Tabs toggle ──
        if (tabNormalLabel != null) {
            tabNormalLabel.setOnClickListener(v -> {
                showingPrivate = false;
                loadNormalTabs();
                updateTabTypeUI();
            });
        }

        // ── Private Tabs toggle ──
        if (tabPrivateLabel != null) {
            tabPrivateLabel.setOnClickListener(v -> {
                showingPrivate = true;
                loadPrivateTabs();
                updateTabTypeUI();
            });
        }

        // ── New Tab button ──
        LinearLayout btnNewTab =
            findViewById(R.id.btn_new_tab);
        if (btnNewTab != null) {
            btnNewTab.setOnClickListener(v -> {
                Intent result = new Intent();
                result.putExtra("new_tab", true);
                result.putExtra(
                    Constants.INTENT_PRIVATE_MODE, false
                );
                setResult(RESULT_OK, result);
                finish();
            });
        }

        // ── New Private Tab button ──
        LinearLayout btnNewPrivate =
            findViewById(R.id.btn_new_private_tab);
        if (btnNewPrivate != null) {
            btnNewPrivate.setOnClickListener(v -> {
                Intent result = new Intent();
                result.putExtra("new_tab", true);
                result.putExtra(
                    Constants.INTENT_PRIVATE_MODE, true
                );
                setResult(RESULT_OK, result);
                finish();
            });
        }
    }

    // ─────────────────────────────────────────────
    // TAB LOADING
    // ─────────────────────────────────────────────

    private void loadNormalTabs() {
        List<TabManager.Tab> normalTabs =
            tabManager.getNormalTabs();
        String activeId = tabManager.getActiveTab() != null
            ? tabManager.getActiveTab().id : "";
        tabAdapter.updateTabs(normalTabs, activeId);
    }

    private void loadPrivateTabs() {
        List<TabManager.Tab> privateTabs =
            tabManager.getPrivateTabs();
        String activeId = tabManager.getActiveTab() != null
            ? tabManager.getActiveTab().id : "";
        tabAdapter.updateTabs(privateTabs, activeId);
    }

    private void updateTabTypeUI() {
        if (tabNormalLabel == null || tabPrivateLabel == null)
            return;

        if (showingPrivate) {
            tabNormalLabel.setTextColor(
                android.graphics.Color.parseColor("#888888")
            );
            tabNormalLabel.setBackgroundColor(
                android.graphics.Color.parseColor("#181818")
            );
            tabPrivateLabel.setTextColor(
                android.graphics.Color.parseColor("#9c27b0")
            );
            tabPrivateLabel.setBackgroundColor(
                android.graphics.Color.parseColor("#1e1e1e")
            );
        } else {
            tabNormalLabel.setTextColor(
                android.graphics.Color.parseColor("#2979ff")
            );
            tabNormalLabel.setBackgroundColor(
                android.graphics.Color.parseColor("#1e1e1e")
            );
            tabPrivateLabel.setTextColor(
                android.graphics.Color.parseColor("#888888")
            );
            tabPrivateLabel.setBackgroundColor(
                android.graphics.Color.parseColor("#181818")
            );
        }
    }

    // ─────────────────────────────────────────────
    // TAB ADAPTER LISTENER
    // ─────────────────────────────────────────────

    /**
     * User tapped a tab — switch to it and close switcher.
     */
    @Override
    public void onTabSelected(TabManager.Tab tab) {
        Intent result = new Intent();
        result.putExtra(Constants.INTENT_TAB_ID, tab.id);
        result.putExtra(
            Constants.INTENT_PRIVATE_MODE, tab.isPrivate
        );
        setResult(RESULT_OK, result);
        finish();
    }

    /**
     * User closed a tab (button or swipe).
     */
    @Override
    public void onTabClosed(TabManager.Tab tab, int position) {
        tabManager.closeTab(tab.id);
        tabAdapter.removeTab(tab.id);

        // If no tabs left — go back to main
        if (tabAdapter.getTabCount() == 0) {
            if (!showingPrivate) {
                Intent result = new Intent();
                result.putExtra("new_tab", true);
                result.putExtra(
                    Constants.INTENT_PRIVATE_MODE, false
                );
                setResult(RESULT_OK, result);
                finish();
            }
        }
    }

    @Override
    public void onNewTabRequested(boolean isPrivate) {
        Intent result = new Intent();
        result.putExtra("new_tab", true);
        result.putExtra(Constants.INTENT_PRIVATE_MODE, isPrivate);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }
          }
