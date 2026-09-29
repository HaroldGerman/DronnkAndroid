package com.german.dronnk.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.german.dronnk.R
import com.german.dronnk.model.Song

class SongAdapter(
    private var songs: List<Song> = emptyList(),
    private val onClick: (Song) -> Unit,
    private val onOptions: (Song) -> Unit,
    private val onFavorite: (Song) -> Unit,
    private val isFavorite: (Song) -> Boolean
) : RecyclerView.Adapter<SongAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val title: TextView = v.findViewById(R.id.title)
        val artist: TextView = v.findViewById(R.id.artist)
        val status: TextView = v.findViewById(R.id.status)
        val favorite: ImageButton = v.findViewById(R.id.favorite)
        val options: ImageButton = v.findViewById(R.id.options)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = songs[position]
        holder.title.text = song.titulo ?: "Canción"
        holder.artist.text = listOfNotNull(song.canal?.takeIf { it.isNotBlank() }, song.duracion?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
        holder.status.text = if (song.isDownloaded || song.localPath?.startsWith("content://") == true) "Guardada en el dispositivo" else "Toca para descargar y reproducir"
        holder.cover.load(song.thumbnail) {
            crossfade(true)
            placeholder(R.drawable.dronnk_app_icon)
            error(R.drawable.dronnk_app_icon)
        }
        val favorite = isFavorite(song)
        holder.favorite.setImageResource(if (favorite) R.drawable.ic_heart_solid else R.drawable.ic_heart_outline)
        holder.favorite.setColorFilter(if (favorite) Color.parseColor("#9A67FF") else Color.parseColor("#9A9AA6"))
        holder.itemView.setOnClickListener { onClick(song) }
        holder.options.setOnClickListener { onOptions(song) }
        holder.favorite.setOnClickListener {
            onFavorite(song)
            notifyItemChanged(holder.bindingAdapterPosition)
        }
    }

    override fun getItemCount() = songs.size

    fun submit(list: List<Song>) {
        songs = list
        notifyDataSetChanged()
    }
}
