package com.nihilshell.launcher.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import com.nihilshell.launcher.R
import com.nihilshell.launcher.core.WebAddress
import com.nihilshell.launcher.core.WebEntry

/**
 * A web's address and name, to add it or change it, as the deck asks for its webs: the
 * address as a browser shows it, with or without https://; the name, left empty, is the
 * address's host. [onDone] once it closes, kept or not.
 */
object WebDialog {
    fun show(
        context: Context,
        web: WebEntry?,
        address: String = web?.url.orEmpty(),
        name: String = web?.label.orEmpty(),
        onDone: () -> Unit = {},
        onSave: (WebEntry) -> Unit,
    ) {
        val addressField = EditText(context).apply {
            setText(address)
            hint = context.getString(R.string.deck_add_web_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val nameField = EditText(context).apply {
            setText(name)
            hint = context.getString(R.string.web_name_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val fields = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(22), context.dp(8), context.dp(22), 0)
            addView(addressField)
            addView(nameField)
        }
        val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (web == null) R.string.web_add else R.string.web_edit)
            .setView(fields)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { onDone() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = WebAddress.normalize(addressField.text.toString())
                if (url == null) {
                    addressField.error = context.getString(R.string.web_invalid)
                } else {
                    onSave(WebEntry(url, nameField.text.toString()))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
