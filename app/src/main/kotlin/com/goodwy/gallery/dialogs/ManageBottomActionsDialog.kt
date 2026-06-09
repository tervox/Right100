package com.goodwy.gallery.dialogs

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
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

    data class ActionItem(val id: Int, val label: String, var isChecked: Boolean)

    private val allActions: MutableList<ActionItem>

    init {
        val ctx = activity
        val actions = ctx.config.visibleBottomActions
        val savedOrder = ctx.config.bottomActionsOrder

        // Labels usando getString para evitar problemas de namespace R vs commons.R
        val rawActions = mutableListOf(
            ActionItem(BOTTOM_ACTION_SHARE, ctx.getString(com.goodwy.commons.R.string.share), actions and BOTTOM_ACTION_SHARE != 0),
            ActionItem(BOTTOM_ACTION_TOGGLE_FAVORITE, ctx.getString(R.string.toggle_favorite), actions and BOTTOM_ACTION_TOGGLE_FAVORITE != 0),
            ActionItem(BOTTOM_ACTION_PLAY_PAUSE, "Play / Pause", actions and BOTTOM_ACTION_PLAY_PAUSE != 0),
            ActionItem(BOTTOM_ACTION_MUTE, "Mudo", actions and BOTTOM_ACTION_MUTE != 0),
            ActionItem(BOTTOM_ACTION_PROPERTIES, ctx.getString(com.goodwy.commons.R.string.properties), actions and BOTTOM_ACTION_PROPERTIES != 0),
            ActionItem(BOTTOM_ACTION_DELETE, ctx.getString(com.goodwy.commons.R.string.delete), actions and BOTTOM_ACTION_DELETE != 0),
            ActionItem(BOTTOM_ACTION_EDIT, ctx.getString(R.string.edit), actions and BOTTOM_ACTION_EDIT != 0),
            ActionItem(BOTTOM_ACTION_ROTATE, ctx.getString(R.string.rotate), actions and BOTTOM_ACTION_ROTATE != 0),
            ActionItem(BOTTOM_ACTION_CHANGE_ORIENTATION, ctx.getString(R.string.change_orientation), actions and BOTTOM_ACTION_CHANGE_ORIENTATION != 0),
            ActionItem(BOTTOM_ACTION_SLIDESHOW, ctx.getString(R.string.slideshow), actions and BOTTOM_ACTION_SLIDESHOW != 0),
            ActionItem(BOTTOM_ACTION_SHOW_ON_MAP, ctx.getString(R.string.show_on_map), actions and BOTTOM_ACTION_SHOW_ON_MAP != 0),
            ActionItem(BOTTOM_ACTION_TOGGLE_VISIBILITY, ctx.getString(R.string.toggle_file_visibility), actions and BOTTOM_ACTION_TOGGLE_VISIBILITY != 0),
            ActionItem(BOTTOM_ACTION_RENAME, ctx.getString(com.goodwy.commons.R.string.rename), actions and BOTTOM_ACTION_RENAME != 0),
            ActionItem(BOTTOM_ACTION_SET_AS, ctx.getString(com.goodwy.commons.R.string.set_as), actions and BOTTOM_ACTION_SET_AS != 0),
            ActionItem(BOTTOM_ACTION_COPY, ctx.getString(com.goodwy.commons.R.string.copy), actions and BOTTOM_ACTION_COPY != 0),
            ActionItem(BOTTOM_ACTION_MOVE, ctx.getString(com.goodwy.commons.R.string.move), actions and BOTTOM_ACTION_MOVE != 0),
            ActionItem(BOTTOM_ACTION_EXTRACT_TEXT, "Extrair texto", actions and BOTTOM_ACTION_EXTRACT_TEXT != 0),
            ActionItem(BOTTOM_ACTION_RESIZE, ctx.getString(com.goodwy.commons.R.string.resize), actions and BOTTOM_ACTION_RESIZE != 0),
        )

        // Reordena conforme ordem salva
        if (savedOrder.isNotBlank()) {
            val orderIds = savedOrder.split(",").mapNotNull { it.toIntOrNull() }
            val ordered = mutableListOf<ActionItem>()
            orderIds.forEach { id -> rawActions.find { it.id == id }?.let { ordered.add(it) } }
            rawActions.filter { item -> !ordered.any { it.id == item.id } }.forEach { ordered.add(it) }
            allActions = ordered
        } else {
            allActions = rawActions
        }

        val recyclerView = RecyclerView(activity)
        recyclerView.layoutManager = LinearLayoutManager(activity)
        val adapter = ActionAdapter(allActions)
        recyclerView.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.adapterPosition
                val to = target.adapterPosition
                if (from < 0 || to < 0) return false
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
        activity.config.bottomActionsOrder = allActions.joinToString(",") { it.id.toString() }
        callback(result)
    }

    inner class ActionAdapter(val items: MutableList<ActionItem>) :
        RecyclerView.Adapter<ActionAdapter.ViewHolder>() {

        var touchHelper: ItemTouchHelper? = null

        inner class ViewHolder(view: View, val checkbox: CheckBox, val dragHandle: ImageButton) :
            RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val row = LayoutInflater.from(parent.context)
                .inflate(R.layout.dialog_bottom_action_item, parent, false)
            val checkbox = row.findViewById<CheckBox>(R.id.action_checkbox)
            val dragHandle = row.findViewById<ImageButton>(R.id.action_drag_handle)
            return ViewHolder(row, checkbox, dragHandle)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.checkbox.text = item.label
            holder.checkbox.isChecked = item.isChecked
            holder.checkbox.setOnCheckedChangeListener { _, checked -> item.isChecked = checked }
            holder.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchHelper?.startDrag(holder)
                }
                false
            }
        }

        override fun getItemCount() = items.size
    }
}
