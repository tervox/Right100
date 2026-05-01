package com.goodwy.gallery.dialogs

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.gallery.R
import com.goodwy.gallery.extensions.config

class ManageSelectionBarDialog(val activity: BaseSimpleActivity, val callback: () -> Unit) {

    // ID fixo por botão
    companion object {
        const val ID_COPY   = 1
        const val ID_MOVE   = 2
        const val ID_DELETE = 3
        const val ID_SHARE  = 4
    }

    data class BarItem(val id: Int, val label: String)

    private val defaultOrder = listOf(
        BarItem(ID_COPY,   activity.getString(com.goodwy.commons.R.string.copy_to)),
        BarItem(ID_MOVE,   activity.getString(com.goodwy.commons.R.string.move_to)),
        BarItem(ID_DELETE, activity.getString(com.goodwy.commons.R.string.delete)),
        BarItem(ID_SHARE,  activity.getString(com.goodwy.commons.R.string.share))
    )

    private val items: ArrayList<BarItem>
    private lateinit var itemTouchHelper: ItemTouchHelper

    init {
        val saved = activity.config.selectionBarOrder
        items = if (saved.isNotEmpty()) {
            val ids = saved.split(",").mapNotNull { it.trim().toIntOrNull() }
            val ordered = ids.mapNotNull { id -> defaultOrder.find { it.id == id } }
            val missing = defaultOrder.filter { d -> ids.none { it == d.id } }
            ArrayList(ordered + missing)
        } else {
            ArrayList(defaultOrder)
        }

        val recyclerView = RecyclerView(activity)
        recyclerView.layoutManager = LinearLayoutManager(activity)
        val adapter = BarAdapter()
        recyclerView.adapter = adapter

        val touchCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean {
                val from = vh.adapterPosition
                val to = t.adapterPosition
                val item = items.removeAt(from)
                items.add(to, item)
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
        }
        itemTouchHelper = ItemTouchHelper(touchCallback)
        itemTouchHelper.attachToRecyclerView(recyclerView)

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.ok) { _, _ ->
                activity.config.selectionBarOrder = items.joinToString(",") { it.id.toString() }
                callback()
            }
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(recyclerView, this, titleId = R.string.selection_bar_order)
            }
    }

    inner class BarAdapter : RecyclerView.Adapter<BarAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val label: TextView = view.findViewById(R.id.action_label)
            val drag: ImageView = view.findViewById(R.id.action_drag_handle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_selection_bar_row, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.label.text = items[position].label
            holder.drag.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) itemTouchHelper.startDrag(holder)
                false
            }
        }
    }
}
