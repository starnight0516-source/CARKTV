package com.car.taiwanktv

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.car.taiwanktv.databinding.ItemCatalogSongBinding

class CatalogAdapter(
    private val onOrderClick: (SongItem) -> Unit,
    private val onCutInClick: (SongItem) -> Unit
) : RecyclerView.Adapter<CatalogAdapter.ViewHolder>() {

    private val items = mutableListOf<SongItem>()

    class ViewHolder(val binding: ItemCatalogSongBinding) : RecyclerView.ViewHolder(binding.root)

    fun submitList(newItems: List<SongItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCatalogSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = items[position]
        holder.binding.tvRank.text = "${position + 1}"
        holder.binding.tvTitle.text = song.title
        holder.binding.tvArtist.text = song.artist.ifBlank { "精選 MV" }

        holder.binding.root.setOnClickListener {
            onOrderClick(song.copy())
        }
        holder.binding.btnOrder.setOnClickListener {
            onOrderClick(song.copy())
        }
        holder.binding.btnCutIn.setOnClickListener {
            onCutInClick(song.copy())
        }
    }

    override fun getItemCount(): Int = items.size
}
