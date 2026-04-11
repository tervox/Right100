package com.goodwy.gallery.dialogs

import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.toast
import com.goodwy.gallery.R
import java.io.File

class BulkRenameDialog(
    private val activity: BaseSimpleActivity,
    private val paths: List<String>,
    private val callback: (renamedCount: Int) -> Unit
) {
    private val view: View = LayoutInflater.from(activity).inflate(R.layout.dialog_bulk_rename, null)

    private val prefixInput   get() = view.findViewById<EditText>(R.id.bulk_rename_prefix)
    private val suffixInput   get() = view.findViewById<EditText>(R.id.bulk_rename_suffix)
    private val startNumInput get() = view.findViewById<EditText>(R.id.bulk_rename_start_number)
    private val paddingInput  get() = view.findViewById<EditText>(R.id.bulk_rename_padding)
    private val keepExtCheck  get() = view.findViewById<CheckBox>(R.id.bulk_rename_keep_extension)
    private val listView      get() = view.findViewById<ListView>(R.id.bulk_rename_list)

    init {
        keepExtCheck.isChecked = true
        keepExtCheck.setOnCheckedChangeListener { _, _ -> updateList() }
        setupTextWatcher()
        updateList()

        activity.getAlertDialogBuilder()
            .setTitle(activity.getString(R.string.bulk_rename))
            .setView(view)
            .setPositiveButton(com.goodwy.commons.R.string.ok) { _, _ -> doRename() }
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .show()
    }

    private fun setupTextWatcher() {
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateList()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        prefixInput.addTextChangedListener(watcher)
        suffixInput.addTextChangedListener(watcher)
        startNumInput.addTextChangedListener(watcher)
        paddingInput.addTextChangedListener(watcher)
    }

    private fun buildNewName(index: Int): String {
        val prefix = prefixInput.text.toString()
        val suffix = suffixInput.text.toString()
        val start = startNumInput.text.toString().toIntOrNull() ?: 1
        val padding = paddingInput.text.toString().toIntOrNull()?.coerceIn(1, 9) ?: 1
        val keepExt = keepExtCheck.isChecked
        val path = paths[index]
        val ext = if (keepExt && path.contains('.')) ".${path.substringAfterLast('.')}" else ""
        val num = (start + index).toString().padStart(padding, '0')
        return "$prefix$num$suffix$ext"
    }

    private fun updateList() {
        val items = paths.mapIndexed { index, path ->
            val oldName = File(path).name
            val newName = buildNewName(index)
            val conflict = File(File(path).parent, newName).exists() && newName != oldName
            Triple(oldName, newName, conflict)
        }

        val adapter = object : ArrayAdapter<Triple<String, String, Boolean>>(
            activity, R.layout.item_bulk_rename_row, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_bulk_rename_row, parent, false)
                val item = items[position]
                row.findViewById<TextView>(R.id.bulk_rename_old_name).text = item.first
                val newNameView = row.findViewById<TextView>(R.id.bulk_rename_new_name)
                newNameView.text = item.second
                newNameView.setTextColor(
                    if (item.third) 0xFFFF4444.toInt() else 0xFF4CAF50.toInt()
                )
                row.findViewById<TextView>(R.id.bulk_rename_conflict_warn).visibility =
                    if (item.third) View.VISIBLE else View.GONE
                return row
            }
        }
        listView.adapter = adapter
    }

    private fun doRename() {
        val conflicts = paths.mapIndexedNotNull { index, path ->
            val newName = buildNewName(index)
            val newFile = File(File(path).parent, newName)
            if (newFile.exists() && newFile.absolutePath != path) newName else null
        }

        if (conflicts.isNotEmpty()) {
            val conflictList = conflicts.take(5).joinToString("\n") { "• $it" }
            val extra = if (conflicts.size > 5) "\n...e mais ${conflicts.size - 5}" else ""
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.bulk_rename_conflict_title))
                .setMessage(activity.getString(R.string.bulk_rename_conflict_msg) + "\n\n" + conflictList + extra)
                .setPositiveButton(activity.getString(R.string.bulk_rename_skip_conflicts)) { _, _ ->
                    executeRename(skipConflicts = true)
                }
                .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
                .show()
        } else {
            executeRename(skipConflicts = false)
        }
    }

    private fun executeRename(skipConflicts: Boolean) {
        Thread {
            var renamed = 0
            paths.forEachIndexed { index, path ->
                val file = File(path)
                val newName = buildNewName(index)
                val newFile = File(file.parent, newName)
                if (newFile.exists() && skipConflicts) return@forEachIndexed
                try {
                    if (file.renameTo(newFile)) renamed++
                } catch (_: Exception) {}
            }
            val count = renamed
            activity.runOnUiThread {
                activity.toast(activity.getString(R.string.bulk_rename_done, count))
                callback(count)
            }
        }.start()
    }
}
