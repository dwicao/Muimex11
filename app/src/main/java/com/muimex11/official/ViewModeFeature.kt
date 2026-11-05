package com.muimex11.official

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * List <-> 2-column grid toggle for the tweak cards. The device-info header
 * always stays full-width on top; every other card reflows into rows of two.
 * Visibility flags (filter chips) survive the re-parenting untouched.
 */
internal fun MainActivity.isGridMode(): Boolean =
    getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
        .getString(Prefs.KEY_VIEW_MODE, "list") == "grid"

internal fun MainActivity.setupViewToggle() {
    val btn = findViewById<ImageView>(R.id.btnViewToggle) ?: return
    syncViewToggleIcon(isGridMode())
    btn.setOnClickListener {
        val grid = !isGridMode()
        getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
            .edit().putString(Prefs.KEY_VIEW_MODE, if (grid) "grid" else "list").apply()
        syncViewToggleIcon(grid)
        applyViewMode(grid)
    }
    applyViewMode(isGridMode())
}

private fun MainActivity.syncViewToggleIcon(grid: Boolean) {
    val btn = findViewById<ImageView>(R.id.btnViewToggle) ?: return
    // The icon shows what tapping switches TO.
    btn.setImageResource(if (grid) R.drawable.ic_view_list else R.drawable.ic_view_grid)
    btn.contentDescription = getString(if (grid) R.string.cd_view_list else R.string.cd_view_grid)
}

internal fun MainActivity.applyViewMode(grid: Boolean) {
    val container = findViewById<LinearLayout>(R.id.cardsContainer) ?: return
    val header = findViewById<View>(R.id.cardDeviceInfo)
    val cards = tweakCardIds().mapNotNull { findViewById<View>(it) }

    // Suppress transition bursts while re-parenting; chips animate on their own.
    val transition = container.layoutTransition
    container.layoutTransition = null
    try {
        // Detach EVERYTHING first: in grid mode the cards live inside row
        // layouts, so container.removeAllViews() alone leaves them parented
        // and re-adding would throw IllegalStateException. This was the
        // grid->list crash.
        header?.let { (it.parent as? ViewGroup)?.removeView(it) }
        cards.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        container.removeAllViews()
        header?.let { container.addView(it, listParams(isHeader = true)) }
        if (!grid) {
            cards.forEach { container.addView(it, listParams(isHeader = false)) }
        } else {
            cards.chunked(2).forEach { row ->
                val rowLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                if (row.size == 1) {
                    rowLayout.addView(row[0], listParams(isHeader = false))
                } else {
                    val (left, right) = row
                    rowLayout.addView(left, gridParams(isFirst = true))
                    rowLayout.addView(right, gridParams(isFirst = false))
                }
                container.addView(rowLayout)
            }
        }
    } finally {
        container.layoutTransition = transition
    }
}

private fun MainActivity.listParams(isHeader: Boolean): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(if (isHeader) 16 else 12) }

private fun MainActivity.gridParams(isFirst: Boolean): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f
    ).apply {
        bottomMargin = dp(12)
        if (isFirst) marginEnd = dp(6) else marginStart = dp(6)
    }
