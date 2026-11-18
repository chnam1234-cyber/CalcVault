package com.calcvault.app

import android.content.Context
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * One app shown in the vault or picker.
 * key: "secret:<pkg>" for apps cloned into the secret space,
 *      "<userSerial>:<pkg>" for shortcuts to apps in the personal profile or in a phone's own clone profile.
 */
data class AppInfo(
    val key: String,
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val user: UserHandle?,
    val secret: Boolean,
    val isClone: Boolean = false,
)

object Apps {
    private fun la(ctx: Context) = ctx.getSystemService(LauncherApps::class.java)
    private fun um(ctx: Context) = ctx.getSystemService(UserManager::class.java)

    /** Profiles that hold our own secret space (excluded from shortcut lists). */
    fun ourProfiles(ctx: Context): Set<UserHandle> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            ctx.getSystemService(CrossProfileApps::class.java).targetUserProfiles.toSet()
        else emptySet()

    /**
     * Launchable apps across every profile the system lets us see — this includes
     * "dual app"/clone profiles on phones that expose them to normal apps.
     */
    fun launchable(ctx: Context, personalOnly: Boolean): List<AppInfo> {
        val me = Process.myUserHandle()
        val skip = ourProfiles(ctx)
        val profiles = if (personalOnly) listOf(me) else la(ctx).profiles.filter { it !in skip }
        return profiles.flatMap { user ->
            val serial = um(ctx).getSerialNumberForUser(user)
            la(ctx).getActivityList(null, user)
                .filter { it.componentName.packageName != ctx.packageName }
                .distinctBy { it.componentName.packageName }
                .map {
                    val clone = user != me
                    AppInfo(
                        key = "$serial:${it.componentName.packageName}",
                        packageName = it.componentName.packageName,
                        label = it.label.toString() + if (clone) " ⧉" else "",
                        icon = it.getBadgedIcon(0),
                        user = user,
                        secret = false,
                        isClone = clone,
                    )
                }
        }.sortedBy { it.label.lowercase() }
    }

    fun loadShortcut(ctx: Context, key: String): AppInfo? {
        val (serialStr, pkg) = key.split(":", limit = 2).takeIf { it.size == 2 } ?: return null
        val user = um(ctx).getUserForSerialNumber(serialStr.toLongOrNull() ?: return null) ?: return null
        val act = runCatching { la(ctx).getActivityList(pkg, user).firstOrNull() }.getOrNull() ?: return null
        val clone = user != Process.myUserHandle()
        return AppInfo(key, pkg, act.label.toString() + if (clone) " ⧉" else "", act.getBadgedIcon(0), user, false, clone)
    }

    fun launchShortcut(ctx: Context, app: AppInfo): Boolean {
        val user = app.user ?: return false
        val act = la(ctx).getActivityList(app.packageName, user).firstOrNull() ?: return false
        la(ctx).startMainActivity(act.componentName, user, null, null)
        return true
    }

    // ---- cached label/icon for secret apps (the original may be uninstalled from the home screen) ----

    private fun iconFile(ctx: Context, pkg: String) = File(File(ctx.filesDir, "icons").apply { mkdirs() }, "$pkg.png")

    fun cacheSecret(ctx: Context, app: AppInfo) {
        ctx.getSharedPreferences("labels", Context.MODE_PRIVATE).edit().putString(app.packageName, app.label).apply()
        runCatching {
            val d = app.icon
            val bmp = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 144, 144); d.draw(Canvas(bmp))
            iconFile(ctx, app.packageName).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    fun loadSecret(ctx: Context, pkg: String): AppInfo {
        val label = ctx.getSharedPreferences("labels", Context.MODE_PRIVATE).getString(pkg, pkg)!!
        val f = iconFile(ctx, pkg)
        val icon: Drawable = if (f.exists()) BitmapDrawable(ctx.resources, BitmapFactory.decodeFile(f.path))
        else ContextCompat.getDrawable(ctx, android.R.drawable.sym_def_app_icon)!!
        return AppInfo("secret:$pkg", pkg, label, icon, null, true)
    }

    fun forgetSecret(ctx: Context, pkg: String) {
        ctx.getSharedPreferences("labels", Context.MODE_PRIVATE).edit().remove(pkg).apply()
        iconFile(ctx, pkg).delete()
    }
}

/** Grid of apps inside the vault. */
class VaultAdapter(
    private val onClick: (AppInfo) -> Unit,
    private val onLongClick: (AppInfo) -> Unit,
) : RecyclerView.Adapter<VaultAdapter.VH>() {
    var items: List<AppInfo> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val label: TextView = v.findViewById(R.id.label)
        val lock: View = v.findViewById(R.id.lockBadge)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_app_grid, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val app = items[pos]
        h.icon.setImageDrawable(app.icon)
        h.label.text = app.label
        h.lock.visibility = if (app.secret) View.VISIBLE else View.GONE
        h.itemView.setOnClickListener { onClick(app) }
        h.itemView.setOnLongClickListener { onLongClick(app); true }
    }
}

/** Checkbox list used to choose apps. */
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
        h.check.isChecked = app.key in selected
        h.itemView.setOnClickListener {
            if (!selected.add(app.key)) selected.remove(app.key)
            h.check.isChecked = app.key in selected
        }
        h.check.setOnClickListener {
            if (h.check.isChecked) selected.add(app.key) else selected.remove(app.key)
        }
    }
}
