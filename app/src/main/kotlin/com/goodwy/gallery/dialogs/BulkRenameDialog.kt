package com.goodwy.gallery.dialogs

import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.toast
import com.goodwy.gallery.R
import java.io.File

/**
 * Dialog de renomear em massa com numeração sequencial.
 * Estilo: <prefixo><número><sufixo>.<extensão original>
 * Exemplo: "Foto_001.jpg", "Foto_002.mp4", ...
 */
class BulkRenameDialog(
    private val activity: BaseSimpleActivity,
    private val paths: List<String>,
    private val callback: (renamedCount: Int) -> Unit
) {
    private val binding: View = LayoutInflater.from(activity).inflate(R.layout.dialog_bulk_rename, null)

    private val prefixInput   get() = binding.findViewById<EditText>(R.id.bulk_rename_prefix)
    private val suffixInput   get() = binding.findViewById<EditText>(R.id.bulk_rename_suffix)
    private val startNumInput get() = binding.findViewById<EditText>(R.id.bulk_rename_start_number)
    private val paddingInput  get() = binding.findViewById<EditText>(R.id.bulk_rename_padding)
    private val previewText   get() = binding.findViewById<TextView>(R.id.bulk_rename_preview)
    private val keepExtCheck  get() = binding.findViewById<CheckBox>(R.id.bulk_rename_keep_extension)

    init {
        setupPreviewListener()
        keepExtCheck.isChecked = true
        keepExtCheck.setOnCheckedChangeListener { _, _ -> updatePreview() }
        updatePreview()

        activity.getAlertDialogBuilder()
            .setTitle(activity.getString(R.string.bulk_rename))
            .setView(binding)
            .setPositiveButton(com.goodwy.commons.R.string.ok) { _, _ -> doRename() }
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .show()
    }

    private fun setupPreviewListener() {
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updatePreview()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        prefixInput.addTextChangedListener(watcher)
        suffixInput.addTextChangedListener(watcher)
        startNumInput.addTextChangedListener(watcher)
        paddingInput.addTextChangedListener(watcher)
    }

    private fun updatePreview() {
        val prefix = prefixInput.text.toString()
        val suffix = suffixInput.text.toString()
        val start = startNumInput.text.toString().toIntOrNull() ?: 1
        val padding = paddingInput.text.toString().toIntOrNull()?.coerceIn(1, 9) ?: 3
        val keepExt = keepExtCheck.isChecked

        val firstPath = paths.firstOrNull() ?: return
        val ext = if (keepExt) ".${firstPath.substringAfterLast('.', "")}" else ""
        val num = start.toString().padStart(padding, '0')
        previewText.text = "$prefix$num$suffix$ext"
    }

    private fun doRename() {
        val prefix = prefixInput.text.toString()
        val suffix = suffixInput.text.toString()
        val start = startNumInput.text.toString().toIntOrNull() ?: 1
        val padding = paddingInput.text.toString().toIntOrNull()?.coerceIn(1, 9) ?: 3
        val keepExt = keepExtCheck.isChecked

        Thread {
            var renamed = 0
            paths.forEachIndexed { index, path ->
                val file = File(path)
                val ext = if (keepExt) ".${path.substringAfterLast('.', "")}" else ""
                val num = (start + index).toString().padStart(padding, '0')
                val newName = "$prefix$num$suffix$ext"
                val newFile = File(file.parent, newName)
                if (!newFile.exists()) {
                    try {
                        if (file.renameTo(newFile)) renamed++
                    } catch (_: Exception) {}
                }
            }
            val count = renamed
            activity.runOnUiThread {
                activity.toast(activity.getString(R.string.bulk_rename_done, count))
                callback(count)
            }
        }.start()
    }
}
