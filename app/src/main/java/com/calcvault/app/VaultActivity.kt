package com.calcvault.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** The secret area: shows hidden apps and launches them. Locks itself when you leave. */
class VaultActivity : AppCompatActivity() {

    private lateinit var store: PinStore
    private lateinit var adapter: VaultAdapter
    private lateinit var empty: TextView
    private var launchingChild = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hide content from screenshots and the recent-apps preview
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_vault)
        supportActionBar?.title = getString(R.string.vault_title)

        store = PinStore(this)
        empty = findViewById(R.id.empty)
        adapter = VaultAdapter(::launch, ::showAppOptions)
        findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = GridLayoutManager(this@VaultActivity, 4)
            adapter = this@VaultActivity.adapter
        }
        findViewById<View>(R.id.fabAdd).setOnClickListener { openPicker() }
    }

    override fun onResume() {
        super.onResume()
        launchingChild = false
        reload()
    }

    /** Leaving the vault (home button, opening another app) locks it again. */
    override fun onStop() {
        super.onStop()
        if (!launchingChild && !isChangingConfigurations) finish()
    }

    override fun onRestart() {
        super.onRestart()
        if (lockRequested) { lockRequested = false; finish() }
    }

    companion object {
        /** Set by the picker when the user leaves the app from there. */
        @Volatile var lockRequested = false
    }

    private fun reload() {
        val apps = store.hiddenApps.mapNotNull { Apps.load(this, it) }.sortedBy { it.label.lowercase() }
        // Forget apps that were uninstalled
        if (apps.size != store.hiddenApps.size) store.hiddenApps = apps.map { it.packageName }.toSet()
        adapter.items = apps
        empty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun launch(app: AppInfo) {
        val intent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent == null) { Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show(); return }
        startActivity(intent)
    }

    private fun openPicker() {
        launchingChild = true
        VaultActivity.lockRequested = false
        startActivity(Intent(this, AppPickerActivity::class.java))
    }

    private fun showAppOptions(app: AppInfo) {
        val options = arrayOf(getString(R.string.remove_from_vault), getString(R.string.app_info))
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { store.hiddenApps = store.hiddenApps - app.packageName; reload() }
                    1 -> startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                    )
                }
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.vault_menu, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_add -> { openPicker(); true }
        R.id.action_change_pin -> { changePin(); true }
        R.id.action_lock -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun changePin() {
        fun pinField(hint: Int) = EditText(this).apply {
            setHint(hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(PinStore.PIN_LENGTH))
        }
        val oldPin = pinField(R.string.old_pin)
        val newPin = pinField(R.string.new_pin)
        val confirm = pinField(R.string.confirm_pin)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(oldPin); addView(newPin); addView(confirm)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.change_pin)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val msg = when {
                    !store.checkPin(oldPin.text.toString()) -> R.string.wrong_old_pin
                    !PinStore.isValidPin(newPin.text.toString()) -> R.string.setup_need_6
                    newPin.text.toString() != confirm.text.toString() -> R.string.setup_mismatch
                    else -> { store.setPin(newPin.text.toString()); R.string.pin_changed }
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
