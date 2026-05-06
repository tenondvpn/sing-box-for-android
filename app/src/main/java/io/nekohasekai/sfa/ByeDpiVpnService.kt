package io.nekohasekai.sfa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import io.nekohasekai.sfa.ui.MainActivity
import io.nekohasekai.sfa.utils.getStringNotNull
// ✅ 新增协程库引用
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.concurrent.thread

class ByeDpiVpnService : VpnService() {
    enum class ServiceStatus {
        Disconnected,
        Connected,
        Failed,
    }

    companion object {
        const val TAG = "ByeDpiVpnService"
        const val ACTION_START = "io.nekohasekai.sfa.ACTION_START"
        const val ACTION_STOP = "io.nekohasekai.sfa.ACTION_STOP"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "byedpi_vpn_channel"
        private var status: ServiceStatus = ServiceStatus.Disconnected
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    // ✅ 定义一个运行在 IO 线程的协程作用域
//    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "VPN now start called")
        val action = intent?.action

        if (action == ACTION_STOP) {
            serviceScope.launch {
                try {
                    stop()
                    Log.i(TAG, "VPN Backend started")
                } catch (e: Exception) {
                    Log.e(TAG, "Fatal error starting VPN", e)
                }
            }
            return START_NOT_STICKY
        }

        // 1. 立即设置前台服务 (防止 ANR)
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (isRunning) return START_STICKY
        isRunning = true

        // 2. ✅ 使用协程启动挂起函数 (替代 Thread)
        serviceScope.launch {
            try {
                Log.i(TAG, "Initializing VPN backend...")
                setupVpnInterface()
                // 这里如果是 suspend 函数，现在可以正常调用了
                startProxy()
                // 如果 startTun2Socks 也是 suspend，也直接调用
//                 startTun2Socks()
                Log.i(TAG, "VPN Backend started")
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error starting VPN", e)
                stop()
            }
        }

        return START_STICKY
    }

    private val byeDpiProxy = ByeDpiProxy()
    private var proxyJob: Job? = null
//    private var tunFd: ParcelFileDescriptor? = null
//    private val mutex = Mutex()

//    fun Context.getPreferences(): SharedPreferences =
//        PreferenceManager.getDefaultSharedPreferences(this)
//
//    private fun getByeDpiPreferences(args: Array<String>): ByeDpiProxyPreferences =
//        ByeDpiProxyCmdPreferences(args)

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private suspend fun startProxy() {
        Log.i(TAG, "Starting proxy")

        if (proxyJob != null) {
            Log.w(TAG, "Proxy fields not null")
            return
        }


        val userArgs =
            "-s2+se -s2+sh -d3+s -s3+he -a3 -s3+sm -r3+s -s3+se -f3+s -d4+s -d5 -s5+s -f6 -s6 -d6 -s6+sh -d8+s -r8+s -s8+s -s9+s -f9+s "
        val argsArray = userArgs.split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
            .toTypedArray()
        val preferences = ByeDpiProxyCmdPreferences(argsArray)
        val fdDeferred = CompletableDeferred<Int>()
        proxyJob = serviceScope.launch(Dispatchers.IO) {
            status = ServiceStatus.Connected
            byeDpiProxy.initByedpi(this@ByeDpiVpnService);
            val code = byeDpiProxy.startProxy(preferences)
            // 如果 proxy 还没获取到 fd 就退出了
            if (!fdDeferred.isCompleted) fdDeferred.complete(-1)

            withContext(Dispatchers.Main) {
                if (code < 0) {
                    Log.e(TAG, "Proxy stopped with code $code")
                    status = ServiceStatus.Failed
                } else {
                    Log.i(TAG, "Proxy stopped gracefully")
                    status = ServiceStatus.Disconnected
                }
            }

            stop()
        }

        Log.i(TAG, "called start byedpi proxy")
    }

    private suspend fun stopProxy() {
        Log.i(TAG, "Stopping proxy")

        if (status == ServiceStatus.Disconnected) {
            Log.w(TAG, "Proxy already disconnected")
            return
        }

        byeDpiProxy.stopProxy()
        proxyJob?.join()
        proxyJob = null

        Log.i(TAG, "Proxy stopped")
    }

    private suspend fun stop() {
        Log.i(TAG, "Stopping VPN")
        try {
            stopProxy()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop VPN", e)
        } finally {
        }
        status = ServiceStatus.Disconnected
        stopSelf()
    }

    // 假设这是你定义的挂起函数
//    private suspend fun startTun2Socks() {
//        // 这里如果是耗时操作，建议用 withContext(Dispatchers.IO) 包裹
//        if (tunFd != null) {
//            throw IllegalStateException("VPN field not null")
//        }
//
//        val sharedPreferences = getPreferences()
//        val port = sharedPreferences.getString("byedpi_proxy_port", null)?.toInt() ?: 1080
//        val dns = sharedPreferences.getStringNotNull("dns_ip", "8.8.8.8")
//        val ipv6 = sharedPreferences.getBoolean("ipv6_enable", false)
//
//        val tun2socksConfig = """
//        | misc:
//        |   task-stack-size: 81920
//        | socks5:
//        |   mtu: 8500
//        |   address: 127.0.0.1
//        |   port: $port
//        |   udp: udp
//        """.trimMargin("| ")
//        Log.i(TAG, tun2socksConfig)
//
//        val configPath = try {
//            File.createTempFile("config", "tmp", cacheDir).apply {
//                writeText(tun2socksConfig)
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to create config file", e)
//            throw e
//        }
//
//        val fd = createBuilder(dns, ipv6).establish()
//            ?: throw IllegalStateException("VPN connection failed")
//
//        this.tunFd = fd
//
//        ByeDpiProxy.TProxyStartService(configPath.absolutePath, fd.fd)
//    }


//    private fun createBuilder(dns: String, ipv6: Boolean): Builder {
//        Log.d(TAG, "DNS: $dns")
//        val builder = Builder()
//        builder.setSession("ByeDPI")
//        builder.setConfigureIntent(
//            PendingIntent.getActivity(
//                this,
//                0,
//                Intent(this, MainActivity::class.java),
//                PendingIntent.FLAG_IMMUTABLE,
//            )
//        )
//
//        builder.addAddress("10.10.10.10", 32)
//            .addRoute("0.0.0.0", 0)
//
//        if (ipv6) {
//            builder.addAddress("fd00::1", 128)
//                .addRoute("::", 0)
//        }
//
//        if (dns.isNotBlank()) {
//            builder.addDnsServer(dns)
//        }
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//            builder.setMetered(false)
//        }
//
//        builder.addDisallowedApplication(applicationContext.packageName)
//
//        return builder
//    }

    private fun setupVpnInterface() {
        if (vpnInterface != null) return
        val builder = Builder()
        builder.setSession("ByeDpi VPN")
        builder.setMtu(1500)
        builder.addAddress("172.16.0.1", 24)
        builder.addRoute("0.0.0.0", 0)
        vpnInterface =
            builder.establish() ?: throw IllegalStateException("Failed to establish VPN interface")
    }

//    private fun stopTun2Socks() {
//        isRunning = false
//
//        Log.i(TAG, "Stopping tun2socks")
//
//        ByeDpiProxy.TProxyStopService()
//        Log.i(TAG, "Stoped tun2socks")
//
//        try {
//            File(cacheDir, "config.tmp").delete()
//        } catch (e: SecurityException) {
//            Log.e(TAG, "Failed to delete config file", e)
//        }
//
//        tunFd?.close() ?: Log.w(TAG, "VPN not running")
//        tunFd = null
//
//        Log.i(TAG, "Tun2socks stopped")
//    }

    // ... createNotification 代码保持不变 ...
    private fun createNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ByeDpi VPN Status",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, ByeDpiVpnService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent =
            PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ByeDpi 正在运行")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        // ✅ 服务销毁时清理协程
        serviceScope.cancel()
//        stop()
    }
}