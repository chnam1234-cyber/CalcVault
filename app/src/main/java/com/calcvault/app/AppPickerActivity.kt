package com.calcvault.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Pick apps for the vault.
 * MODE_SECRET: personal apps to clone into the secret space (returns selected packages).
 * MODE_SHORTCUT: apps from every visible profile, incl. the phone's own clones (saved directly).
 */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var store: PinStore
    private var adapter: PickerAdapter? = null
    private var apps: List<AppInfo> = emptyList()
    private val secretMode get() = intent.getStringExtra(EXTRA_MODE) == MODE_SECRET

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_picker)
        supportActionBar?.title = getString(if (secretMode) R.string.pick_secret else R.string.pick_shortcut)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<TextView>(R.id.pickerHint).setText(
            if (secretMode) R.string.pick_secret_hint else R.string.pick_shortcut_hint)

        store = PinStore(this)
        val list = findViewById<RecyclerView>(R.id.list)
        val progress = findViewById<ProgressBar>(R.id.progress)
        list.layoutManager = LinearLayoutManager(this)

        Thread {
            val loaded = Apps.launchable(this, personalOnly = secretMode).let { all ->
                if (secretMode) all.filter { it.packageName !in store.secretApps } else all
            }
            runOnUiThread {
                apps = loaded
                val initial = if (secretMode) mutableSetOf() else store.hiddenApps.toMutableSet()
                adapter = PickerAdapter(loaded, initial)
                list.adapter = adapter
                progress.visibility = View.GONE
            }
        }.start()

        findViewById<View>(R.id.btnSave).setOnClickListener { save(); finish() }
    }

    private fun save() {
        val sel = adapter?.selected ?: return
        if (secretMode) {
            val chosen = apps.filter { it.key in sel }
            chosen.forEach { Apps.cacheSecret(this, it) }
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PKGS, chosen.map { it.packageName }.toTypedArray()))
        } else {
            store.hiddenApps = sel
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    /** If the user leaves for the home screen, close so the vault isn't left open. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing) {
            VaultActivity.lockRequested = true
            finish()
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_PKGS = "pkgs"
        const val MODE_SECRET = "secret"
        const val MODE_SHORTCUT = "shortcut"
    }
}
