package com.car.taiwanktv

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.car.taiwanktv.databinding.ItemSingerBinding

class SingerAdapter(
    private val singers: List<SingerInfo>,
    private val onSingerClick: (SingerInfo) -> Unit
) : RecyclerView.Adapter<SingerAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemSingerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSingerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val singer = singers[position]
        holder.binding.tvSingerName.text = singer.name
        holder.binding.tvSingerTag.text = singer.tag
        holder.binding.root.setOnClickListener {
            onSingerClick(singer)
        }
    }

    override fun getItemCount(): Int = singers.size
}
