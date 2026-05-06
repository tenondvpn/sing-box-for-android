package io.nekohasekai.sfa.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.ProfileContent
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.ServiceConnection
import io.nekohasekai.sfa.bg.ServiceNotification
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.databinding.ActivityMainBinding
import io.nekohasekai.sfa.ktx.errorDialogBuilder
import io.nekohasekai.sfa.ktx.hasPermission
import io.nekohasekai.sfa.ktx.launchCustomTab
import io.nekohasekai.sfa.ui.profile.NewProfileActivity
import io.nekohasekai.sfa.ui.shared.AbstractActivity
import io.nekohasekai.sfa.utils.MIUIUtils
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

class MainActivity : AbstractActivity<ActivityMainBinding>(),
    ServiceConnection.Callback {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var navHostFragment: NavHostFragment
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    private val connection = ServiceConnection(this, this)

    val serviceStatus = MutableLiveData(Status.Stopped)

//    private lateinit var binding: ActivityMainBinding

    // 注册一个 ActivityResultLauncher 来接收 VPN 权限请求的结果
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 当用户从 VPN 权限对话框返回时，会执行这里
        Log.i("MainActivity", "VPN 权限请求返回，结果码: ${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            // 用户授予了权限，现在可以安全地再次尝试启动 VPN
            Log.i("MainActivity", "用户已授予 VPN 权限，再次尝试启动服务。")
            (application as Application).tryStartVpn()
        } else {
            // 用户拒绝了权限
            Log.w("MainActivity", "用户拒绝了 VPN 权限。")
            // 你可以在这里给用户一个提示，例如使用 Toast 或 Snackbar
        }
    }

    private fun handleVpnPermissionRequest(intent: Intent) {
        // 检查是否是 Application 要求我们来请求 VPN 权限
        if (intent.getBooleanExtra("request_vpn_permission", false)) {
            Log.i("MainActivity", "收到来自 Application 的 VPN 权限请求。")
            // 清除标记，防止重复请求
            intent.removeExtra("request_vpn_permission")

            // 再次调用 VpnService.prepare() 以获取用于启动权限对话框的 Intent
            val prepareIntent = android.net.VpnService.prepare(this)
            if (prepareIntent != null) {
                // 使用我们注册的 launcher 来启动这个 Intent
                vpnPermissionLauncher.launch(prepareIntent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 使用 ViewBinding 初始化布局
//        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 为新添加的按钮设置点击监听器
        binding.buttonStartVpn.setOnClickListener {
            Log.d("MainActivity", "Start VPN 按钮被点击")
            // 获取 Application 实例并调用 tryStartVpn 方法
            (application as Application).tryStartVpn()
        }

        binding.buttonStopVpn.setOnClickListener {
            Log.d("MainActivity", "Stop VPN 按钮被点击")
            // 获取 Application 实例并调用 tryStartVpn 方法
            (application as Application).stopProxy()
        }

        // 处理从 Application 类跳转过来的权限请求
        handleVpnPermissionRequest(intent)

        navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_activity_my) as NavHostFragment
        navController = navHostFragment.navController
        navController.setGraph(R.navigation.mobile_navigation)
        navController.addOnDestinationChangedListener(::onDestinationChanged)
        appBarConfiguration =
            AppBarConfiguration(
                setOf(
                    R.id.navigation_dashboard,
                    R.id.navigation_log,
                    R.id.navigation_configuration,
                    R.id.navigation_settings,
                )
            )
        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.navView.setupWithNavController(navController)
        reconnect()
        startIntegration()

        onNewIntent(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onDestinationChanged(
        navController: NavController,
        navDestination: NavDestination,
        bundle: Bundle?
    ) {
        val destinationId = navDestination.id
        binding.dashboardTabContainer.isVisible = destinationId == R.id.navigation_dashboard
    }

    override public fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 当 Activity 已经在运行时，通过 onNewIntent 接收新的 Intent
        if (intent != null) {
            handleVpnPermissionRequest(intent)
        }

        val uri = intent.data ?: return
        when (intent.action) {
            Action.OPEN_URL -> {
                launchCustomTab(uri.toString())
                return
            }
        }
        if (uri.scheme == "sing-box" && uri.host == "import-remote-profile") {
            val profile = try {
                Libbox.parseRemoteProfileImportLink(uri.toString())
            } catch (e: Exception) {
                errorDialogBuilder(e).show()
                return
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.import_remote_profile)
                .setMessage(
                    getString(
                        R.string.import_remote_profile_message,
                        profile.name,
                        profile.host
                    )
                )
                .setPositiveButton(R.string.ok) { _, _ ->
                    startActivity(Intent(this, NewProfileActivity::class.java).apply {
                        putExtra("importName", profile.name)
                        putExtra("importURL", profile.url)
                    })
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else if (intent.action == Intent.ACTION_VIEW) {
            try {
                val data = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
                val content = Libbox.decodeProfileContent(data)
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.import_profile)
                    .setMessage(
                        getString(
                            R.string.import_profile_message,
                            content.name
                        )
                    )
                    .setPositiveButton(R.string.ok) { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    importProfile(content)
                                }.onFailure {
                                    withContext(Dispatchers.Main) {
                                        errorDialogBuilder(it).show()
                                    }
                                }
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } catch (e: Exception) {
                errorDialogBuilder(e).show()
            }
        }
    }

    private suspend fun importProfile(content: ProfileContent) {
        val typedProfile = TypedProfile()
        val profile = Profile(name = content.name, typed = typedProfile)
        profile.userOrder = ProfileManager.nextOrder()
        when (content.type) {
            Libbox.ProfileTypeLocal -> {
                typedProfile.type = TypedProfile.Type.Local
            }

            Libbox.ProfileTypeiCloud -> {
                errorDialogBuilder(R.string.icloud_profile_unsupported).show()
                return
            }

            Libbox.ProfileTypeRemote -> {
                typedProfile.type = TypedProfile.Type.Remote
                typedProfile.remoteURL = content.remotePath
                typedProfile.autoUpdate = content.autoUpdate
                typedProfile.autoUpdateInterval = content.autoUpdateInterval
                typedProfile.lastUpdated = Date(content.lastUpdated)
            }
        }
        val configDirectory = File(filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "${profile.userOrder}.json")
        configFile.writeText(content.config)
        typedProfile.path = configFile.path
        ProfileManager.create(profile)
    }

    fun reconnect() {
        connection.reconnect()
    }

    private fun startIntegration() {
        if (Vendor.checkUpdateAvailable()) {
            lifecycleScope.launch(Dispatchers.IO) {
                if (Settings.checkUpdateEnabled) {
                    Vendor.checkUpdate(this@MainActivity, false)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    fun startService() {
        if (!ServiceNotification.checkPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startService0()
    }

    private fun startService0() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (Settings.rebuildServiceMode()) {
                reconnect()
            }
            if (Settings.serviceMode == ServiceMode.VPN) {
                if (prepare()) {
                    return@launch
                }
            }
            val intent = Intent(Application.application, Settings.serviceClass())
            withContext(Dispatchers.Main) {
                ContextCompat.startForegroundService(Application.application, intent)
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (Settings.dynamicNotification && !it) {
            onServiceAlert(Alert.RequestNotificationPermission, null)
        } else {
            startService0()
        }
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) {
                if (it && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    requestBackgroundLocationPermission()
                } else {
                    startService()
                }
            }
        }

    private val backgroundLocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) {
                startService()
            }
        }

    private val prepareLauncher = registerForActivityResult(PrepareService()) {
        if (it) {
            startService()
        } else {
            onServiceAlert(Alert.RequestVPNPermission, null)
        }
    }

    private class PrepareService : ActivityResultContract<Intent, Boolean>() {
        override fun createIntent(context: Context, input: Intent): Intent {
            return input
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
            return resultCode == RESULT_OK
        }
    }

    private suspend fun prepare() = withContext(Dispatchers.Main) {
        try {
            val intent = VpnService.prepare(this@MainActivity)
            if (intent != null) {
                prepareLauncher.launch(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            onServiceAlert(Alert.RequestVPNPermission, e.message)
            false
        }
    }

    override fun onServiceStatusChanged(status: Status) {
        serviceStatus.postValue(status)
    }

    override fun onServiceAlert(type: Alert, message: String?) {
        serviceStatus.value = Status.Stopped

        when (type) {
            Alert.RequestLocationPermission -> {
                return requestLocationPermission()
            }

            else -> {}
        }

        val builder = MaterialAlertDialogBuilder(this)
        builder.setPositiveButton(R.string.ok, null)
        when (type) {
            Alert.RequestVPNPermission -> {
                builder.setMessage(getString(R.string.service_error_missing_permission))
            }

            Alert.RequestNotificationPermission -> {
                builder.setTitle(R.string.notification_permission_title)
                builder.setMessage(R.string.notification_permission_required_description)
            }

            Alert.EmptyConfiguration -> {
                builder.setMessage(getString(R.string.service_error_empty_configuration))
            }

            Alert.StartCommandServer -> {
                builder.setTitle(getString(R.string.service_error_title_start_command_server))
                builder.setMessage(message)
            }

            Alert.CreateService -> {
                builder.setTitle(getString(R.string.service_error_title_create_service))
                builder.setMessage(message)
            }

            Alert.StartService -> {
                builder.setTitle(getString(R.string.service_error_title_start_service))
                builder.setMessage(message)

            }

            else -> {}
        }
        builder.show()
    }

    private fun requestLocationPermission() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestFineLocationPermission()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestBackgroundLocationPermission()
        }
    }

    private fun requestFineLocationPermission() {
        val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(
                getString(R.string.location_permission_description),
                Html.FROM_HTML_MODE_LEGACY
            )
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(getString(R.string.location_permission_description))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_permission_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok) { _, _ ->
                requestFineLocationPermission0()
            }
            .setNegativeButton(R.string.no_thanks, null)
            .setCancelable(false)
            .show()
    }

    private fun requestFineLocationPermission0() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            openPermissionSettings()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestBackgroundLocationPermission() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_permission_title)
            .setMessage(
                Html.fromHtml(
                    getString(R.string.location_permission_background_description),
                    Html.FROM_HTML_MODE_LEGACY
                )
            )
            .setPositiveButton(R.string.ok) { _, _ ->
                backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton(R.string.no_thanks, null)
            .setCancelable(false)
            .show()
    }

    private fun openPermissionSettings() {
        if (MIUIUtils.isMIUI) {
            try {
                MIUIUtils.openPermissionSettings(this)
                return
            } catch (ignored: Exception) {
            }
        }

        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            errorDialogBuilder(e).show()
        }
    }

    override fun onDestroy() {
        connection.disconnect()
        super.onDestroy()
    }

}