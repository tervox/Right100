package com.goodwy.gallery.dialogs

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.gallery.R
import com.goodwy.gallery.extensions.config
import com.goodwy.gallery.helpers.*

class ManageBottomActionsDialog(val activity: BaseSimpleActivity, val callback: (result: Int) -> Unit) {

    data class ActionItem(val id: Int, val labelRes: Int, var isChecked: Boolean)

    private val allActions = mutableListOf(
        ActionItem(BOTTOM_ACTION_SHARE, com.goodwy.commons.R.string.share, false),
        ActionItem(BOTTOM_ACTION_TOGGLE_FAVORITE, R.string.toggle_favorite, false),
        ActionItem(BOTTOM_ACTION_PLAY_PAUSE, R.string.playpause, false),
        ActionItem(BOTTOM_ACTION_MUTE, R.string.volume, false),
        ActionItem(BOTTOM_ACTION_PROPERTIES, R.string.properties, false),
        ActionItem(BOTTOM_ACTION_DELETE, com.goodwy.commons.R.string.delete, false),
        ActionItem(BOTTOM_ACTION_EDIT, R.string.edit, false),
        ActionItem(BOTTOM_ACTION_ROTATE, R.string.rotate, false),
        ActionItem(BOTTOM_ACTION_CHANGE_ORIENTATION, R.string.change_orientation, false),
        ActionItem(BOTTOM_ACTION_SLIDESHOW, R.string.slideshow, false),
        ActionItem(BOTTOM_ACTION_SHOW_ON_MAP, R.string.show_on_map, false),
        ActionItem(BOTTOM_ACTION_TOGGLE_VISIBILITY, R.string.toggle_file_visibility, false),
        ActionItem(BOTTOM_ACTION_RENAME, R.string.rename, false),
        ActionItem(BOTTOM_ACTION_SET_AS, R.string.set_as, false),
        ActionItem(BOTTOM_ACTION_COPY, com.goodwy.commons.R.string.copy, false),
        ActionItem(BOTTOM_ACTION_MOVE, com.goodwy.commons.R.string.move, false),
        ActionItem(BOTTOM_ACTION_RESIZE, com.goodwy.commons.R.string.resize, false),
    )

    init {
        val actions = activity.config.visibleBottomActions
        val savedOrder = activity.config.bottomActionsOrder

        // Marca os ativos
        allActions.forEach { it.isChecked = actions and it.id != 0 }

        // Reordena conforme ordem salva
        if (savedOrder.isNotBlank()) {
            val orderIds = savedOrder.split(",").mapNotNull { it.toIntOrNull() }
            val ordered = mutableListOf<ActionItem>()
            orderIds.forEach { id -> allActions.find { it.id == id }?.let { ordered.add(it) } }
            allActions.filter { item -> !ordered.any { it.id == item.id } }.forEach { ordered.add(it) }
            allActions.clear()
            allActions.addAll(ordered)
        }

        val recyclerView = RecyclerView(activity)
        recyclerView.layoutManager = LinearLayoutManager(activity)
        val adapter = ActionAdapter(allActions)
        recyclerView.adapter = adapter

        // Drag para reordenar
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.adapterPosition
                val to = target.adapterPosition
                val item = allActions.removeAt(from)
                allActions.add(to, item)
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(recyclerView)
        adapter.touchHelper = touchHelper

        val padding = activity.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.normal_margin)
        recyclerView.setPadding(padding, 0, padding, 0)

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.ok) { _, _ -> dialogConfirmed() }
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(recyclerView, this, titleId = R.string.manage_bottom_actions)
            }
    }

    private fun dialogConfirmed() {
        var result = 0
        allActions.forEach { if (it.isChecked) result += it.id }
        activity.config.visibleBottomActions = result
        // Salva a ordem atual
        activity.config.bottomActionsOrder = allActions.joinToString(",") { it.id.toString() }
        callback(result)
    }

    inner class ActionAdapter(val items: MutableList<ActionItem>) :
        RecyclerView.Adapter<ActionAdapter.ViewHolder>() {

        var touchHelper: ItemTouchHelper? = null

        inner class ViewHolder(view: View, val checkbox: CheckBox, val dragHandle: ImageButton) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val row = LayoutInflater.from(parent.context)
                .inflate(R.layout.dialog_bottom_action_item, parent, false)
            val checkbox = row.findViewById<CheckBox>(R.id.action_checkbox)
            val dragHandle = row.findViewById<ImageButton>(R.id.action_drag_handle)
            return ViewHolder(row, checkbox, dragHandle)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.checkbox.text = activity.getString(item.labelRes)
            holder.checkbox.isChecked = item.isChecked
            holder.checkbox.setOnCheckedChangeListener { _, checked -> item.isChecked = checked }
            holder.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    touchHelper?.startDrag(holder)
                }
                false
            }
        }

        override fun getItemCount() = items.size
    }
}
