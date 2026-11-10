package com.nihilshell.launcher.ui

import android.graphics.Bitmap
import android.widget.ImageView
import com.nihilshell.launcher.core.AppEntry
import com.nihilshell.launcher.core.Apps
import com.nihilshell.launcher.core.Entry
import com.nihilshell.launcher.core.IconStyle
import com.nihilshell.launcher.core.WebEntry
import com.nihilshell.launcher.core.Webs

/** [entry]'s icon here: at once when it is cached, or once painted, if this still shows it. */
fun ImageView.bindIcon(entry: Entry, size: Int, style: IconStyle) {
    tag = entry.key
    setImageDrawable(null)
    val ready: (Bitmap) -> Unit = { bitmap -> if (tag == entry.key) setImageBitmap(bitmap) }
    val cached = when (entry) {
        is AppEntry -> Apps.icon(entry, size, style, ready)
        is WebEntry -> Webs.icon(context, entry, size, style, ready)
    }
    if (cached != null) setImageBitmap(cached)
}
