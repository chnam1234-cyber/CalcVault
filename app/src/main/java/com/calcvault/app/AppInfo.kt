package com.calcvault.app

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class AppInfo(val packageName: String, val label: String, val icon: Drawable)

object Apps {
    /** All apps that have a launcher icon, except this one. */
    fun launchable(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .mapNotNull { load(context, it) }
            .sortedBy { it.label.lowercase() }
    }

    fun load(context: Context, pkg: String): AppInfo? = try {
        val pm = context.packageManager
        val ai = pm.getApplicationInfo(pkg, 0)
        AppInfo(pkg, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai))
    } catch (e: Exception) { null }   // uninstalled
}

/** Grid of hidden apps inside the vault. */
class VaultAdapter(
    private val onClick: (AppInfo) -> Unit,
    private val onLongClick: (AppInfo) -> Unit,
) : RecyclerView.Adapter<VaultAdapter.VH>() {
    var items: List<AppInfo> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val label: TextView = v.findViewById(R.id.label)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_app_grid, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val app = items[pos]
        h.icon.setImageDrawable(app.icon)
        h.label.text = app.label
        h.itemView.setOnClickListener { onClick(app) }
        h.itemView.setOnLongClickListener { onLongClick(app); true }
    }
}

/** Checkbox list used to choose which apps to hide. */
class PickerAdapter(
    private val apps: List<AppInfo>,
    val selected: MutableSet<String>,
) : RecyclerView.Adapter<PickerAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val label: TextView = v.findViewById(R.id.label)
        val check: CheckBox = v.findViewById(R.id.check)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_app_row, p, false))

    override fun getItemCount() = apps.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val app = apps[pos]
        h.icon.setImageDrawable(app.icon)
        h.label.text = app.label
        h.check.setOnCheckedChangeListener(null)
        h.check.isChecked = app.packageName in selected
        val toggle = {
            if (!selected.add(app.packageName)) selected.remove(app.packageName)
            h.check.isChecked = app.packageName in selected
        }
        h.itemView.setOnClickListener { toggle() }
        h.check.setOnClickListener {
            if (h.check.isChecked) selected.add(app.packageName) else selected.remove(app.packageName)
        }
    }
}
