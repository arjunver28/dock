package com.example.taskbartoggle

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox

class SelectDockAppsAdapter(
    private val context: Context,
    private val allApps: List<AppInfo>,
    initialSelectedPackages: Set<String>
) : RecyclerView.Adapter<SelectDockAppsAdapter.ViewHolder>() {

    private val selectedPackages = initialSelectedPackages.toMutableSet()
    private var filteredApps: List<AppInfo> = allApps

    fun filter(query: String) {
        filteredApps = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter {
                it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
        }
        notifyDataSetChanged()
    }

    fun getSelectedPackages(): List<String> = selectedPackages.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_select_dock_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = filteredApps[position]
        holder.tvName.text = app.label
        holder.tvPackage.text = app.packageName
        holder.ivIcon.setImageDrawable(app.icon)

        val isChecked = selectedPackages.contains(app.packageName)
        holder.cbSelected.isChecked = isChecked

        holder.itemView.setOnClickListener {
            if (selectedPackages.contains(app.packageName)) {
                selectedPackages.remove(app.packageName)
                holder.cbSelected.isChecked = false
            } else {
                if (selectedPackages.size >= 8) {
                    holder.cbSelected.isChecked = false
                    return@setOnClickListener
                }
                selectedPackages.add(app.packageName)
                holder.cbSelected.isChecked = true
            }
        }
    }

    override fun getItemCount(): Int = filteredApps.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        val tvPackage: TextView = itemView.findViewById(R.id.tvPackageName)
        val cbSelected: MaterialCheckBox = itemView.findViewById(R.id.cbSelected)
    }
}
