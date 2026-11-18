package com.calcvault.app

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Pick which installed apps should appear inside the vault. */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var store: PinStore
    private var adapter: PickerAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_picker)
        supportActionBar?.title = getString(R.string.pick_apps)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        store = PinStore(this)
        val list = findViewById<RecyclerView>(R.id.list)
        val progress = findViewById<ProgressBar>(R.id.progress)
        list.layoutManager = LinearLayoutManager(this)

        // Loading icons can take a moment, so do it off the main thread
        Thread {
            val apps = Apps.launchable(this)
            runOnUiThread {
                adapter = PickerAdapter(apps, store.hiddenApps.toMutableSet())
                list.adapter = adapter
                progress.visibility = View.GONE
            }
        }.start()

        findViewById<View>(R.id.btnSave).setOnClickListener { save(); finish() }
    }

    private fun save() {
        adapter?.let { store.hiddenApps = it.selected }
    }

    override fun onSupportNavigateUp(): Boolean { save(); finish(); return true }

    /** If the user leaves for the home screen, close so the vault isn't left open. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing) {
            VaultActivity.lockRequested = true
            finish()
        }
    }
}
