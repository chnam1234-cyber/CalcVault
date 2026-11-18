package com.calcvault.app

import android.content.ActivityNotFoundException
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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** The vault: secret (truly hidden) apps + shortcuts. Locks itself when you leave. */
class VaultActivity : AppCompatActivity() {

    private lateinit var store: PinStore
    private lateinit var adapter: VaultAdapter
    private lateinit var empty: TextView
    private lateinit var setupCard: View
    private var launchingChild = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_vault)
        supportActionBar?.title = getString(R.string.vault_title)

        store = PinStore(this)
        empty = findViewById(R.id.empty)
        setupCard = findViewById(R.id.setupCard)
        adapter = VaultAdapter(::launch, ::showAppOptions)
        findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = GridLayoutManager(this@VaultActivity, 4)
            adapter = this@VaultActivity.adapter
        }
        findViewById<View>(R.id.fabAdd).setOnClickListener { chooseAddMode() }
        findViewById<Button>(R.id.btnSetup).setOnClickListener { startSetup() }
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

    private fun reload() {
        val ready = SecretSpace.isReady(this)
        setupCard.visibility = if (ready) View.GONE else View.VISIBLE

        val secret = if (ready) store.secretApps.map { Apps.loadSecret(this, it) } else emptyList()
        val shortcuts = store.hiddenApps.mapNotNull { Apps.loadShortcut(this, it) }
        if (shortcuts.size != store.hiddenApps.size) store.hiddenApps = shortcuts.map { it.key }.toSet()

        adapter.items = (secret + shortcuts).sortedBy { it.label.lowercase() }
        empty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------- launching ----------

    private fun launch(app: AppInfo) {
        if (app.secret) {
            sendCmd(SecretSpace.cmdIntent(SecretSpace.CMD_LAUNCH, app.packageName), REQ_LAUNCH)
        } else if (!Apps.launchShortcut(this, app)) {
            toast(R.string.cannot_open)
        }
    }

    private fun sendCmd(intent: Intent, req: Int) {
        try { startActivityForResult(intent, req) }
        catch (e: ActivityNotFoundException) { toast(R.string.secret_not_ready) }
        catch (e: SecurityException) { toast(R.string.secret_not_ready) }
    }

    // ---------- adding apps ----------

    private fun chooseAddMode() {
        val opts = arrayOf(getString(R.string.add_secret), getString(R.string.add_shortcut))
        AlertDialog.Builder(this)
            .setTitle(R.string.add_apps)
            .setItems(opts) { _, which ->
                if (which == 0) {
                    if (SecretSpace.isReady(this)) openPicker(AppPickerActivity.MODE_SECRET) else startSetup()
                } else openPicker(AppPickerActivity.MODE_SHORTCUT)
            }
            .show()
    }

    private fun openPicker(mode: String) {
        launchingChild = true
        lockRequested = false
        startActivityForResult(
            Intent(this, AppPickerActivity::class.java).putExtra(AppPickerActivity.EXTRA_MODE, mode), REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PICK -> {
                val pkgs = data?.getStringArrayExtra(AppPickerActivity.EXTRA_PKGS)
                if (resultCode == RESULT_OK && !pkgs.isNullOrEmpty())
                    sendCmd(SecretSpace.cmdIntent(SecretSpace.CMD_CLONE, *pkgs), REQ_CLONE)
            }
            REQ_CLONE -> {
                val ok = data?.getStringArrayExtra(SecretSpace.EXTRA_PKGS)?.toList().orEmpty()
                if (resultCode != RESULT_OK || ok.isEmpty()) { toast(R.string.clone_failed); return }
                store.secretApps = store.secretApps + ok
                reload()
                offerUninstallOriginals(ok)
            }
            REQ_SETUP -> {
                if (resultCode == RESULT_OK) toast(R.string.secret_ready) else toast(R.string.setup_cancelled)
                reload()
            }
            REQ_LAUNCH -> if (resultCode != RESULT_OK) toast(R.string.cannot_open)
        }
    }

    /** After cloning, the original copy can be removed so the app disappears from the home screen. */
    private fun offerUninstallOriginals(pkgs: List<String>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.cloned_title)
            .setMessage(R.string.cloned_msg)
            .setPositiveButton(R.string.remove_originals) { _, _ ->
                pkgs.forEach {
                    runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$it"))) }
                }
            }
            .setNegativeButton(R.string.keep_both, null)
            .show()
    }

    // ---------- secret space setup ----------

    private fun startSetup() {
        if (!SecretSpace.canSetUp(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.setup_unavailable_title)
                .setMessage(R.string.setup_unavailable_msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_title)
            .setMessage(R.string.setup_msg)
            .setPositiveButton(R.string.setup_go) { _, _ ->
                launchingChild = true
                try { startActivityForResult(SecretSpace.provisionIntent(this), REQ_SETUP) }
                catch (e: ActivityNotFoundException) { toast(R.string.setup_unavailable_title) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- long-press options ----------

    private fun showAppOptions(app: AppInfo) {
        if (app.secret) {
            AlertDialog.Builder(this)
                .setTitle(app.label)
                .setItems(arrayOf(getString(R.string.remove_secret))) { _, _ ->
                    AlertDialog.Builder(this)
                        .setMessage(R.string.remove_secret_confirm)
                        .setPositiveButton(R.string.remove) { _, _ ->
                            sendCmd(SecretSpace.cmdIntent(SecretSpace.CMD_REMOVE, app.packageName), REQ_REMOVE)
                            store.secretApps = store.secretApps - app.packageName
                            Apps.forgetSecret(this, app.packageName)
                            reload()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                .show()
        } else {
            val options = arrayOf(getString(R.string.remove_from_vault), getString(R.string.app_info))
            AlertDialog.Builder(this)
                .setTitle(app.label)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> { store.hiddenApps = store.hiddenApps - app.key; reload() }
                        1 -> runCatching {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${app.packageName}")))
                        }
                    }
                }
                .show()
        }
    }

    // ---------- menu ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.vault_menu, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_add -> { chooseAddMode(); true }
        R.id.action_change_pin -> { changePin(); true }
        R.id.action_lock -> {
            if (SecretSpace.isReady(this)) sendCmd(SecretSpace.cmdIntent(SecretSpace.CMD_HIDE_ALL), REQ_HIDE)
            finish(); true
        }
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
                toast(msg)
            }
            .show()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    companion object {
        /** Set by the picker when the user leaves the app from there. */
        @Volatile var lockRequested = false

        private const val REQ_PICK = 1
        private const val REQ_CLONE = 2
        private const val REQ_SETUP = 3
        private const val REQ_LAUNCH = 4
        private const val REQ_REMOVE = 5
        private const val REQ_HIDE = 6
    }
}
