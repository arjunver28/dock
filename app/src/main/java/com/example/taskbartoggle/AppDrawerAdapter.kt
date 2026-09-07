package com.example.taskbartoggle

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class AppInfo(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val launchIntent: Intent?
)

class AppDrawerAdapter(
    private val context: Context,
    private var apps: List<AppInfo>,
    private val onAppClick: (AppInfo) -> Unit,
    private val onAppLongClick: ((AppInfo, View) -> Unit)? = null
) : RecyclerView.Adapter<AppDrawerAdapter.AppViewHolder>() {

    fun updateApps(newApps: List<AppInfo>) {
        this.apps = newApps
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_app_drawer, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = apps[position]
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

    override fun getItemCount(): Int = apps.size

    class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivIcon: ImageView = itemView.findViewById(R.id.ivDrawerAppIcon)
        val tvName: TextView = itemView.findViewById(R.id.tvDrawerAppName)
    }
}
