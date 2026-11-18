package com.calcvault.app

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home screen (launcher). Shows every app — including the phone's clone apps —
 * except the ones put in the vault. Those can only be opened from the calculator.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var store: PinStore
    private lateinit var grid: RecyclerView
    private lateinit var search: EditText
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private val adapter = HomeAdapter(::open, ::showOptions)
    private var allApps: List<AppInfo> = emptyList()
    private val handler = Handler(Looper.getMainLooper())

    private val launcherCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(p: String, u: UserHandle) = reloadSoon()
        override fun onPackageAdded(p: String, u: UserHandle) = reloadSoon()
        override fun onPackageChanged(p: String, u: UserHandle) = reloadSoon()
        override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) = reloadSoon()
        override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = reloadSoon()
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            date.text = SimpleDateFormat("EEEE d MMMM", Locale("th", "TH")).format(now)
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        store = PinStore(this)
        grid = findViewById(R.id.homeGrid)
        search = findViewById(R.id.homeSearch)
        clock = findViewById(R.id.homeClock)
        date = findViewById(R.id.homeDate)

        grid.layoutManager = GridLayoutManager(this, 5)
        grid.adapter = adapter
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        getSystemService(LauncherApps::class.java).registerCallback(launcherCallback, handler)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        reload()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        getSystemService(LauncherApps::class.java).unregisterCallback(launcherCallback)
        super.onDestroy()
    }

    /** Pressing Home while already home: clear search and scroll to top. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        search.setText("")
        hideKeyboard()
        grid.scrollToPosition(0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Home screen: Back just clears the search
        search.setText(""); hideKeyboard()
    }

    private fun reloadSoon() {
        handler.removeCallbacks(reloadRunnable); handler.postDelayed(reloadRunnable, 300)
    }
    private val reloadRunnable = Runnable { reload() }

    private fun reload() {
        Thread {
            val hidden = store.hiddenApps
            val apps = runCatching { Apps.launchable(this, personalOnly = false) }.getOrDefault(emptyList())
                .filter { it.key !in hidden && it.packageName !in store.secretApps }
            val calc = AppInfo(KEY_CALC, packageName, getString(R.string.app_name),
                ContextCompat.getDrawable(this, R.mipmap.ic_launcher)!!, null, false)
            val list = (apps + calc).sortedBy { it.label.lowercase() }
            runOnUiThread { allApps = list; applyFilter() }
        }.start()
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        adapter.items = if (q.isEmpty()) allApps else allApps.filter { it.label.lowercase().contains(q) }
    }

    private fun open(app: AppInfo) {
        hideKeyboard()
        if (app.key == KEY_CALC) {
            startActivity(Intent(this, MainActivity::class.java)); return
        }
        if (!runCatching { Apps.launchShortcut(this, app) }.getOrDefault(false)) reload()
    }

    private fun showOptions(app: AppInfo) {
        if (app.key == KEY_CALC) return
        val opts = arrayOf(getString(R.string.app_info), getString(R.string.uninstall))
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(opts) { _, which ->
                runCatching {
                    if (which == 0) {
                        val la = getSystemService(LauncherApps::class.java)
                        val act = la.getActivityList(app.packageName, app.user).firstOrNull()
                        if (act != null) la.startAppDetailsActivity(act.componentName, app.user, null, null)
                    } else {
                        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
                    }
                }
            }
            .show()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    companion object {
        private const val KEY_CALC = "calc"

        fun isDefaultHome(ctx: Context): Boolean {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            return ctx.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName == ctx.packageName
        }

        /** Intent that asks the user to make this app the home screen. */
        fun setDefaultIntent(ctx: Context): Intent {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = ctx.getSystemService(RoleManager::class.java)
                if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME))
                    return rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
            }
            return Intent(Settings.ACTION_HOME_SETTINGS)
        }
    }
}

class HomeAdapter(
    private val onClick: (AppInfo) -> Unit,
    private val onLongClick: (AppInfo) -> Unit,
) : RecyclerView.Adapter<HomeAdapter.VH>() {
    var items: List<AppInfo> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val label: TextView = v.findViewById(R.id.label)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_home_app, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val app = items[pos]
        h.icon.setImageDrawable(app.icon)
        h.label.text = app.label
        h.itemView.setOnClickListener { onClick(app) }
        h.itemView.setOnLongClickListener { onLongClick(app); true }
    }
}
