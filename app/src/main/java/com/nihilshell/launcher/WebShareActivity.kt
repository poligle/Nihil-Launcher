package com.nihilshell.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.nihilshell.launcher.core.Prefs
import com.nihilshell.launcher.core.WebAddress
import com.nihilshell.launcher.ui.WebDialog

/**
 * "Add to NIHIL" in the share sheet: the web shared (a browser shares the page's address,
 * and its title as the subject) goes to the drawer among the apps, its name looked at
 * first; over whatever was on screen.
 */
class WebShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = WebAddress.find(text) ?: WebAddress.normalize(text)
        if (url == null) {
            Toast.makeText(this, R.string.deck_no_url, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val title = intent?.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().trim().take(40)
        WebDialog.show(this, null, address = url, name = title, onDone = { finish() }) { web ->
            Prefs.get(this).saveWeb(web)
            Toast.makeText(this, getString(R.string.web_added, web.label), Toast.LENGTH_SHORT).show()
        }
    }
}
