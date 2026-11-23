package com.car.taiwanktv

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.car.taiwanktv.databinding.ItemQueueSongBinding

class QueueAdapter(
    private val items: MutableList<SongItem>,
    private val onMoveTop: (Int) -> Unit,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemQueueSongBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemQueueSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = items[position]
        holder.binding.tvIndex.text = "${position + 1}."
        holder.binding.tvSongTitle.text = song.title
        holder.binding.tvSongArtist.text = song.artist.ifBlank { "點歌清單" }

        holder.binding.btnMoveTop.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onMoveTop(pos)
        }
        holder.binding.btnRemove.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onRemove(pos)
        }
    }

    override fun getItemCount(): Int = items.size
}
