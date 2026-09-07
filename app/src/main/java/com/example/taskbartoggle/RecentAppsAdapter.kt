package com.example.taskbartoggle

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RecentAppsAdapter(
    private val context: Context,
    private var recents: List<AppInfo>,
    private val onAppClick: (AppInfo) -> Unit,
    private val onAppLongClick: ((AppInfo, View) -> Unit)? = null
) : RecyclerView.Adapter<RecentAppsAdapter.RecentViewHolder>() {

    fun updateRecents(newRecents: List<AppInfo>) {
        this.recents = newRecents
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_recent_app, parent, false)
        return RecentViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecentViewHolder, position: Int) {
        val app = recents[position]
        holder.tvName.text = app.label
        holder.ivIcon.setImageDrawable(app.icon)
        holder.itemView.setOnClickListener {
            onAppClick(app)
        }
        holder.itemView.setOnLongClickListener {
            onAppLongClick?.invoke(app, holder.itemView)
            true
        }
    }

    override fun getItemCount(): Int = recents.size

    class RecentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivIcon: ImageView = itemView.findViewById(R.id.ivRecentAppIcon)
        val tvName: TextView = itemView.findViewById(R.id.tvRecentAppName)
    }
}
