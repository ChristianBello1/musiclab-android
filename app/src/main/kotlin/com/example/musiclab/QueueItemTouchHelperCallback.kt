package com.example.musiclab

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import android.util.Log

class QueueItemTouchHelperCallback(
    private val adapter: QueueAdapter,
    private val onDragCompleted: (fromPosition: Int, toPosition: Int) -> Unit
) : ItemTouchHelper.Callback() {

    private var deleteIcon: Drawable? = null
    private var deleteBackground: ColorDrawable = ColorDrawable(Color.RED)

    // Tieni traccia della posizione iniziale per il drag
    private var dragFromPosition = -1
    private var dragToPosition = -1

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
        val swipeFlags = ItemTouchHelper.START or ItemTouchHelper.END
        return makeMovementFlags(dragFlags, swipeFlags)
    }

    override fun isLongPressDragEnabled(): Boolean = true

    override fun isItemViewSwipeEnabled(): Boolean = true

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val fromPos = viewHolder.adapterPosition
        val toPos = target.adapterPosition

        if (dragFromPosition == -1) {
            dragFromPosition = fromPos
        }
        dragToPosition = toPos

        // Aggiorna solo visivamente
        adapter.onItemMove(fromPos, toPos)
        return true
    }

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        when (actionState) {
            ItemTouchHelper.ACTION_STATE_DRAG -> {
                viewHolder?.itemView?.alpha = 0.7f
                dragFromPosition = viewHolder?.adapterPosition ?: -1
                dragToPosition = dragFromPosition
            }
            ItemTouchHelper.ACTION_STATE_IDLE -> {
                if (dragFromPosition != -1 && dragToPosition != -1 && dragFromPosition != dragToPosition) {
                    onDragCompleted(dragFromPosition, dragToPosition)
                }
                dragFromPosition = -1
                dragToPosition = -1
            }
        }
        super.onSelectedChanged(viewHolder, actionState)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        viewHolder.itemView.alpha = 1.0f
    }

    // QUESTO È IL METODO CHIAVE PER LO SWIPE
    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.adapterPosition
        Log.d("QueueItemTouchHelper", "Swiped at position: $position")
        adapter.onItemDismiss(position)  // Chiama il metodo dell'adapter
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            val itemView = viewHolder.itemView

            // Carica l'icona del cestino se non è già caricata
            if (deleteIcon == null) {
                deleteIcon = ContextCompat.getDrawable(
                    recyclerView.context,
                    android.R.drawable.ic_menu_delete
                )
            }

            val iconMargin = (itemView.height - deleteIcon!!.intrinsicHeight) / 2
            val iconTop = itemView.top + iconMargin
            val iconBottom = iconTop + deleteIcon!!.intrinsicHeight

            when {
                dX > 0 -> { // Swipe verso destra
                    val iconLeft = itemView.left + iconMargin
                    val iconRight = iconLeft + deleteIcon!!.intrinsicWidth
                    deleteIcon!!.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                    deleteBackground.setBounds(itemView.left, itemView.top, itemView.left + dX.toInt(), itemView.bottom)
                }
                dX < 0 -> { // Swipe verso sinistra
                    val iconLeft = itemView.right - iconMargin - deleteIcon!!.intrinsicWidth
                    val iconRight = itemView.right - iconMargin
                    deleteIcon!!.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                    deleteBackground.setBounds(itemView.right + dX.toInt(), itemView.top, itemView.right, itemView.bottom)
                }
                else -> {
                    deleteBackground.setBounds(0, 0, 0, 0)
                }
            }

            deleteBackground.draw(c)
            deleteIcon?.draw(c)
        }

        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}