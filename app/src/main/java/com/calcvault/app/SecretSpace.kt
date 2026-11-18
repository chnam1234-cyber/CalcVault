package com.calcvault.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder

/**
 * "Secret space" = an Android work profile owned by this app.
 * Apps are cloned into it with installExistingPackage() and kept hidden with setApplicationHidden(),
 * so they vanish from the launcher and can only be started from the vault.
 */
object SecretSpace {
    const val ACTION_CMD = "com.calcvault.app.action.CMD"
    const val EXTRA_CMD = "cmd"
    const val EXTRA_PKGS = "pkgs"
    const val CMD_CLONE = "clone"
    const val CMD_LAUNCH = "launch"
    const val CMD_HIDE_ALL = "hide_all"
    const val CMD_REMOVE = "remove"

    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

    // ---------- personal side ----------

    /** True when the work profile exists and is accepting our commands. */
    fun isReady(ctx: Context): Boolean =
        ctx.packageManager.queryIntentActivities(cmdIntent(), 0).isNotEmpty()

    fun canSetUp(ctx: Context): Boolean =
        dpm(ctx).isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)

    /** Human-readable info to explain why setup isn't allowed. */
    fun diagnostics(ctx: Context): String {
        val pm = ctx.packageManager
        val um = ctx.getSystemService(android.os.UserManager::class.java)
        val la = ctx.getSystemService(android.content.pm.LauncherApps::class.java)
        val provisioner = pm.queryIntentActivities(
            Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE), 0).firstOrNull()?.activityInfo?.packageName
        return buildString {
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("managed_users: ${pm.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)}")
            appendLine("device_admin: ${pm.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)}")
            appendLine("allowed: ${canSetUp(ctx)}")
            appendLine("profiles: ${um.userProfiles.size} / launcher: ${la.profiles.size}")
            append("provisioner: ${provisioner ?: "none"}")
        }
    }

    fun provisionIntent(ctx: Context) = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
        .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin(ctx))
        .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true)

    fun cmdIntent(cmd: String? = null, vararg pkgs: String) = Intent(ACTION_CMD)
        .addCategory(Intent.CATEGORY_DEFAULT)
        .apply { if (cmd != null) putExtra(EXTRA_CMD, cmd); putExtra(EXTRA_PKGS, pkgs) }

    // ---------- work-profile side ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("secret_space", Context.MODE_PRIVATE)
    private fun isOwner(ctx: Context) = dpm(ctx).isProfileOwnerApp(ctx.packageName)

    var Context.secretPkgs: Set<String>
        get() = prefs(this).getStringSet("pkgs", emptySet())!!.toSet()
        set(v) { prefs(this).edit().putStringSet("pkgs", v.toSet()).apply() }

    /** One-time setup, run inside the freshly created profile. Safe to call more than once. */
    fun setUpProfile(ctx: Context) {
        if (!isOwner(ctx)) return
        val dpm = dpm(ctx); val admin = admin(ctx); val pm = ctx.packageManager
        dpm.setProfileName(admin, ctx.getString(R.string.app_name))
        dpm.addCrossProfileIntentFilter(
            admin,
            IntentFilter(ACTION_CMD).apply { addCategory(Intent.CATEGORY_DEFAULT) },
            DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED,
        )
        pm.setComponentEnabledSetting(ComponentName(ctx, ProfileCommandActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        // No calculator icon inside the profile
        pm.setComponentEnabledSetting(ComponentName(ctx, MainActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        // Hide the default apps the profile comes with, so its launcher tab stays empty
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.distinct()
            .filter { it != ctx.packageName }
            .forEach { runCatching { dpm.setApplicationHidden(admin, it, true) } }
        dpm.setProfileEnabled(admin)
    }

    fun clone(ctx: Context, pkgs: Array<String>): List<String> {
        val dpm = dpm(ctx); val admin = admin(ctx)
        val ok = pkgs.filter { pkg ->
            runCatching {
                val installed =
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dpm.installExistingPackage(admin, pkg)) ||
                        dpm.isApplicationHidden(admin, pkg) || isInstalled(ctx, pkg)
                if (installed) dpm.setApplicationHidden(admin, pkg, true)
                installed
            }.getOrDefault(false)
        }
        ctx.secretPkgs = ctx.secretPkgs + ok
        return ok
    }

    private fun isInstalled(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    fun hideAll(ctx: Context) {
        if (!isOwner(ctx)) return
        val dpm = dpm(ctx); val admin = admin(ctx)
        ctx.secretPkgs.forEach { runCatching { dpm.setApplicationHidden(admin, it, true) } }
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val dpm = dpm(ctx); val admin = admin(ctx)
        ctx.secretPkgs.filter { it != pkg }.forEach { runCatching { dpm.setApplicationHidden(admin, it, true) } }
        runCatching { dpm.setApplicationHidden(admin, pkg, false) }
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        GuardService.start(ctx)
        return true
    }

    fun remove(ctx: Context, pkg: String) {
        runCatching { dpm(ctx).setApplicationHidden(admin(ctx), pkg, false) }
        ctx.secretPkgs = ctx.secretPkgs - pkg
        ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Device-admin receiver: the system calls it inside the new profile when setup finishes. */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        SecretSpace.setUpProfile(context)
    }
}

/** Android 12+: tells the setup wizard we want a work profile. */
class ProvisionModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setResult(RESULT_OK, Intent().putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE))
        } else setResult(RESULT_OK)
        finish()
    }
}

/** Android 12+ compliance step: finish setup and report success. */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecretSpace.setUpProfile(this)
        setResult(RESULT_OK)
        finish()
    }
}

/** Android 8+: started inside the new profile once it is created. */
class ProvisioningSuccessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecretSpace.setUpProfile(this)
        finish()
    }
}

/** Invisible activity inside the profile that executes commands sent from the calculator. */
class ProfileCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkgs = intent.getStringArrayExtra(SecretSpace.EXTRA_PKGS) ?: emptyArray()
        val result = Intent()
        try {
            when (intent.getStringExtra(SecretSpace.EXTRA_CMD)) {
                SecretSpace.CMD_CLONE ->
                    result.putExtra(SecretSpace.EXTRA_PKGS, SecretSpace.clone(this, pkgs).toTypedArray())
                SecretSpace.CMD_LAUNCH -> pkgs.firstOrNull()?.let {
                    if (!SecretSpace.launch(this, it)) { setResult(RESULT_CANCELED); finish(); return }
                }
                SecretSpace.CMD_REMOVE -> pkgs.firstOrNull()?.let { SecretSpace.remove(this, it) }
                SecretSpace.CMD_HIDE_ALL -> SecretSpace.hideAll(this)
            }
            setResult(RESULT_OK, result)
        } catch (e: Exception) {
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}

/** Runs only while a secret app is open; hides everything again when the screen turns off. */
class GuardService : Service() {
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            SecretSpace.hideAll(c); stopSelf()
        }
    }
    private var registered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("guard", getString(R.string.app_name), NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "guard")
            .setSmallIcon(R.drawable.ic_calc_small)
            .setContentTitle(getString(R.string.app_name))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        if (!registered) {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF)); registered = true
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (registered) unregisterReceiver(screenOff)
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) = runCatching {
            ctx.startForegroundService(Intent(ctx, GuardService::class.java))
        }
    }
}
