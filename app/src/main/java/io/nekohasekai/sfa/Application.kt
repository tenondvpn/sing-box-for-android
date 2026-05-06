package io.nekohasekai.sfa

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.getSystemService
import android.net.VpnService
import go.Seq
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.sfa.bg.AppChangeReceiver
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.constant.Bugs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import io.nekohasekai.sfa.Application as BoxApplication
import io.nekohasekai.sfa.ui.MainActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*
import java.net.NetworkInterface
import java.net.Inet4Address
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat

class P2pService : Service() {
    private val CHANNEL_ID = "p2p_channel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("P2P 节点运行中")
            .setContentText("正在保持 NAT 端口开放...")
            .setSmallIcon(R.drawable.ic_status)
            .build()

        // 关键：将服务提升为前台，这样切后台就不会被断网
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 在这里启动你的 C++ Init 逻辑
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "P2P Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}

class Application : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    private fun copyAssetsToFilesDir() {
        val assetsDir = "assets"  // 如果你直接放 assets 根目录，这行可以省略
        val targetDir = File("/storage/emulated/0/Android/data/io.nekohasekai.sfa/files/", "")  // 最终路径：/data/user/0/your.package/files/files/
        targetDir.mkdirs()
        System.out.println("DDDDDD:" + targetDir)
        val files = arrayOf(
            "geoip-cn.srs",
            "geosite-cn.srs",
            "geosite-geolocation-!cn.srs"
        )

        files.forEach { fileName ->
            val targetFile = File(targetDir, fileName)
            if (targetFile.exists()) return@forEach  // 已存在就跳过

            assets.open(fileName).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    fun startIgnoreBatteryOptimizationThread() {
        Thread {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val packageName = packageName

                // 检查是否已经忽略，避免重复弹出干扰用户
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                            // 在非 Activity 环境启动 Intent 需要加此 Flag
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        Log.i("P2P_SETTING", "已弹出忽略电池优化请求")
                    }
                }
            } catch (e: Exception) {
                Log.e("P2P_SETTING", "请求失败，尝试跳转设置列表: ${e.message}")
                // 备选方案：跳转到电池优化设置列表
                val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(listIntent)
            }
        }.start()
    }

    override fun onCreate() {
        super.onCreate()
//        runP2PTest()
        startIgnoreBatteryOptimizationThread();

        Thread {
            try {
                demoP2p()
            } catch (e: Exception) {
                android.util.Log.e("p2p", "后台执行 demoP2p 出错", e)
            }
        }.start()
//        copyAssetsToFilesDir()
        Seq.setContext(this)
        Libbox.setLocale(Locale.getDefault().toLanguageTag().replace("-", "_"))

        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            initialize()
            UpdateProfileWork.reconfigureUpdater()
        }

        registerReceiver(AppChangeReceiver(), IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addDataScheme("package")
        })

    }

    private fun initialize() {
        val baseDir = filesDir
        baseDir.mkdirs()
        val workingDir = getExternalFilesDir(null) ?: return
        workingDir.mkdirs()
        val tempDir = cacheDir
        tempDir.mkdirs()
        Libbox.setup(SetupOptions().also {
            it.basePath = baseDir.path
            it.workingPath = workingDir.path
            it.tempPath = tempDir.path
            it.fixAndroidStack = Bugs.fixAndroidStack
        })
        Libbox.redirectStderr(File(workingDir, "stderr.log").path)
    }

    companion object {
        lateinit var application: BoxApplication

        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val packageManager by lazy { application.packageManager }
        val powerManager by lazy { application.getSystemService<PowerManager>()!! }
        val notificationManager by lazy { application.getSystemService<NotificationManager>()!! }
        val wifiManager by lazy { application.getSystemService<WifiManager>()!! }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
    }

    fun stopProxy() {
        val TAG = "p2p"
        val serviceIntent = Intent(this, ByeDpiVpnService::class.java).apply {
            // 假设你在 Service 中定义了这个常量
            action = ByeDpiVpnService.ACTION_STOP
        }

        // 发送指令给 Service，让 Service 内部决定如何停止 (调用 stopSelf())
        startService(serviceIntent)
    }

    fun tryStartVpn(): Boolean {
        // 1. 检查权限
        // 注意：prepare 方法在 Application Context 下也是可以调用的
        val TAG = "p2p"
        val prepareIntent = VpnService.prepare(this)

        if (prepareIntent != null) {
            // === 情况 A: 没有权限 ===
            Log.w(TAG, "没有 VPN 权限，无法在后台直接启动。需要跳转 Activity 请求权限。")

            // 这里我们需要启动 MainActivity 来处理权限请求
            val activityIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // 在 Application 启动 Activity 必须加这个 Flag
                putExtra("request_vpn_permission", true) // 传个标记，告诉 Activity 它是被拉起来请求权限的
            }
            startActivity(activityIntent)

            return false
        } else {
            // === 情况 B: 已有权限 ===
            Log.i(TAG, "已有 VPN 权限，正在启动服务...")

            val serviceIntent = Intent(this, ByeDpiVpnService::class.java).apply {
                action = ByeDpiVpnService.ACTION_START
            }

            // Android 8.0 (Oreo) 及以上必须使用 startForegroundService
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            return true
        }
    }

    fun printResult(funcName: String, resp: ApiResponse) {
        if (resp.error != null) {
            // 修改：打印异常类型和 message，防止 message 为 null 时不知道发生了什么
            println("❌ $funcName 错误: [${resp.error.javaClass.simpleName}] ${resp.error.message}")
        } else {
            println("✅ $funcName 成功 | 状态码: ${resp.statusCode} | 响应体预览: ${resp.body}")
        }
        println("-\n")
    }
    // 在 Android 中调用测试代码的正确姿势
    fun runP2PTest() {
        // 启动一个子线程来执行网络请求
        Thread {
            try {
                println("--- 开始 P2P 测试 (子线程) ---")

                // 这里放入原本 main() 函数中的所有代码
                val host = "129.151.137.91"
                val port = 29001

                // --- 1. 基础 API ---
                printResult("Health", P2PClient.health(host, port))
                printResult("Metrics", P2PClient.metrics(host, port))
                printResult("ClientInit", P2PClient.clientInit(host, port, 0))

                // --- 2. 初始化安全模块 ---
                val userPrivateKeyHex = "3f5ffe90a794ae529488ce9edc456382cbd8fa1df0974cd296705176b79eaaf5"
                val initErr = P2PClient.initSecurity(userPrivateKeyHex) // 改用非 panic 版本更安全
                if (initErr != null) {
                    println("❌ Security Init Failed: ${initErr.message}")
                    return@Thread
                }

                val result = P2PClient.clientInit(host, port, port)

                // 4. 使用之前定义的 printResult 打印结果
                printResult("ClientInit", result)

                val kvs = mapOf(
                    "username" to "alice",
                    "role" to "admin",
                    "key1" to "admin"
                )

                println("\n--- 配置管理 API 调用 ---")
                printResult("ApiPut", P2PClient.apiPut(host, port, kvs))

                val keysToDelete = listOf("key1", "key2", "temp_data")
                printResult("ApiDelete", P2PClient.apiDelete(host, port, keysToDelete))

                printResult("ApiGet", P2PClient.apiGet(host, port, "test_key"))
                printResult("ApiGets", P2PClient.apiGets(host, port, listOf("key1", "key2")))
                printResult("ApiPrefix", P2PClient.apiPrefix(host, port, "", 50))

                println("\n--- 用户管理 API 调用 ---")
                val newUsers = listOf("02xxyyzzpublickeyA", "02aabbccpublickeyB")
                printResult("AddUser", P2PClient.addUser(host, port, newUsers))
                printResult("RemoveUser", P2PClient.removeUser(host, port, listOf("02xxyyzzpublickeyA")))
                printResult("GetUsers", P2PClient.getUsers(host, port))

                println("\n--- 节点管理 API 调用 ---")
                val newNodes = listOf("192.168.1.100", "192.168.1.101")
                printResult("AddNode", P2PClient.addNode(host, port, newNodes))
                printResult("GetNodes", P2PClient.getNodes(host, port))

                println("\n--- 国家/地区管理 API 调用 ---")
                val countries = listOf("CA", "MX")
                printResult("ValidCountry", P2PClient.validCountry(host, port, countries))
                printResult("GetCountry", P2PClient.getCountry(host, port))

            } catch (e: Exception) {
                e.printStackTrace()
                println("❌ 测试过程中发生严重错误: ${e.message}")
            }
        }.start()
    }

    fun testHttpHeartbeat(ip: String?, port: Int, localPort: Int) {
        // 1. 配置忽略 SSL 校验的 Client
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL").apply {
            init(null, trustAllCerts, SecureRandom())
        }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()

        // 2. 构造请求
        val body = """{"node_id":"pixel9_test","local_port":$localPort}"""
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("https://$ip:$port/api/v1/heartbeat").post(body).build()

        // 3. 执行请求 (建议在协程/线程中运行)
            try {
                client.newCall(request).execute().use { res ->
                    println("HTTP Status: ${res.code}, Body: ${res.body?.string()}")
                }
            } catch (e: Exception) {
                println("HTTP Error: ${e.message}")
            }
    }

    fun testUdpHeartbeat(ip: String?, port: Int, localPort: Int) {
        try {
            val socket = DatagramSocket()
            socket.soTimeout = 2000 // 2秒接收超时

            // 1. 构造并发送数据包
            val msg = """{"type":"heartbeat","node_id":"pixel9_udp","local_port":$localPort,"seq":101}"""
            val data = msg.toByteArray()
            val packet = DatagramPacket(data, data.size, InetAddress.getByName(ip), port)
            socket.send(packet)
            println("UDP Sent to $ip:$port")

            // 2. 尝试接收服务器回包 (ACK)
            val buf = ByteArray(1024)
            val ackPacket = DatagramPacket(buf, buf.size)
            socket.receive(ackPacket)
            println("UDP Received: ${String(ackPacket.data, 0, ackPacket.length)}")

            socket.close()
        } catch (e: Exception) {
            println("UDP Error: ${e.message}")
        }
    }

    fun getLocalIpAddress(): String? {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    // 排除回环地址 (127.0.0.1) 并且只取 IPv4
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return null
    }

    fun startP2pStatusMonitor() {
//        // 获取主线程 Handler，用于更新 UI
//        val mainHandler = Handler(Looper.getMainLooper())
//
//            Log.i("P2P_THREAD", "状态监控线程已启动")
//            try {
//                // 1. 调用 JNI 获取所有节点状态 (阻塞操作)
//                val statusJson = P2pNative.p2pGetAllStatus()
//                if (!statusJson.isNullOrEmpty()) {
//                    // 2. 切回主线程更新界面
//                    mainHandler.post {
//                        // 在此处操作你的 TextView 或 RecyclerView
//                        // binding.tvStatus.text = statusJson
//                        Log.d("P2P_UI", "收到状态更新: ${statusJson}")
//                    }
//
//                }
//            } catch (e: InterruptedException) {
//                Log.e("P2P_THREAD", "监控线程被中断")
//            } catch (e: Exception) {
//                Log.e("P2P_THREAD", "获取状态异常: ${e.message}")
//            }
    }

    fun demoP2p() {
        val TAG = "p2p"
        // 1. P2pInit
        //1f7ae5a528463841ec3a345b644120345d26178a0270f50983acafa36fbbee551205c46e9a89bf2a2d7f8e82c6fc8feb75b33077ff01b465be2f646b20a7e94f034e1a0d1c2046d1e91750c3105897dc
        val initError = StringBuilder()
        val initRes = StringBuilder()
        val config_str = """
{"private_key": "1f7ae5a528463841ec3a345b644120345d26178a0270f50983acafa36fbbee551205c46e9a89bf2a2d7f8e82c6fc8feb75b33077ff01b465be2f646b20a7e94f034e1a0d1c2046d1e91750c3105897dc","snis":["https://test1/","https://test2/","https://test3/"],"local_country": "CN","bootstrap":[{"country":"SG","pubkey":"031c68b2775c6fd1e2f0a5eefb7141d5319315c48b68edfbeb8df53d02be0fedf2", "ip":"47.128.73.97","port":443},{"country":"SG","pubkey":"0233ad0b5b715241ae4fede40d6e253bb0158dd4094b7afbafb81153ed2c020106", "ip":"54.254.221.255","port":443},{"country":"SG","pubkey":"0238fcf136d9836572d9f56f925e567c4ff753f8e29a5362aa1ba997e86b6bf062", "ip":"13.250.17.215","port":443}]}
            """.trimIndent();
        var initRet = P2pNative.p2pInit(
            config = config_str,  // 示例配置 JSON 字符串
            error = initError,
            initRes = initRes,
            threadCount = 4
        )
        startP2pStatusMonitor()
        Log.i(TAG, "P2pInit 返回码: $initRet")
//        initRet = P2pNative.p2pInit(
//            config = config_str,  // 示例配置 JSON 字符串
//            error = initError,
//            initRes = initRes,
//            threadCount = 4
//        )
//        startP2pStatusMonitor()
//        Log.i(TAG, "P2pInit 返回码: $initRet")
        if (initRet == 0) {
            Log.i(TAG, "P2pInit 成功: $initRes")
        } else {
            Log.e(TAG, "P2pInit 失败: $initError $initRes")
        }

        if (true) {
            // 5. P2pPut（写入键值对）
            val putData = mapOf(
                "test_key1" to "value1",
                "test_key2" to "value2",
                "temp_setting" to "android_demo",
                "node_id" to "node_id",
                "port" to "port",
                "log_level" to "log_level"
            )
            val putRet = P2pNative.p2pPut(putData)
            startP2pStatusMonitor()
            Log.i(TAG, "P2pPut 返回码: $putRet")  // 0 表示成功
        }

        if (true) {
            // 3. P2pGetConfigWithKeys
            val configRes1 = mutableMapOf<String, String>()
            val keys = listOf("node_id", "port", "log_level")
            val getConfigRet1 = P2pNative.p2pGetConfigWithKeys(keys, configRes1)
            Log.i(TAG, "P2pGetConfigWithKeys 返回码: $getConfigRet1, 结果: $configRes1")
            startP2pStatusMonitor()
        }

        if (true) {
            // 4. P2pGetConfigWithStartKey（分页获取配置）
            val nextStartKey = StringBuilder()
            val pageRes = mutableMapOf<String, String>()
            val getPageRet = P2pNative.p2pGetConfigWithStartKey(
                startKey = "",          // 空字符串表示从头开始
                length = 10,            // 一次获取最多10条
                nextStartKey = nextStartKey,
                res = pageRes
            )
            Log.i(TAG, "P2pGetConfigWithStartKey 返回码: $getPageRet")
            Log.i(TAG, "分页结果: $pageRes")
            Log.i(TAG, "下一页起始key: ${nextStartKey.toString()}")
            startP2pStatusMonitor()
        }


        if (true) {
            // 4. P2pGetConfigWithStartKey（分页获取配置）
            val nextStartKey = StringBuilder()
            val pageRes = mutableMapOf<String, String>()
            val getPageRet = P2pNative.p2pGetConfigWithStartKey(
                startKey = "",          // 空字符串表示从头开始
                length = 10,            // 一次获取最多10条
                nextStartKey = nextStartKey,
                res = pageRes
            )
            Log.i(TAG, "P2pGetConfigWithStartKey 返回码: $getPageRet")
            Log.i(TAG, "分页结果: $pageRes")
            Log.i(TAG, "下一页起始key: ${nextStartKey.toString()}")
            startP2pStatusMonitor()
        }

        if (true) {
            // 6. P2pDelete（删除键）
            val deleteRet = P2pNative.p2pDelete(listOf("test_key1", "test_key2"))
            Log.i(TAG, "P2pDelete 返回码: $deleteRet")
            startP2pStatusMonitor()
        }

        if (true) {
            // 4. P2pGetConfigWithStartKey（分页获取配置）
            val nextStartKey = StringBuilder()
            val pageRes = mutableMapOf<String, String>()
            val getPageRet = P2pNative.p2pGetConfigWithStartKey(
                startKey = "",          // 空字符串表示从头开始
                length = 10,            // 一次获取最多10条
                nextStartKey = nextStartKey,
                res = pageRes
            )
            Log.i(TAG, "P2pGetConfigWithStartKey 返回码: $getPageRet")
            Log.i(TAG, "分页结果: $pageRes")
            Log.i(TAG, "下一页起始key: ${nextStartKey.toString()}")
            startP2pStatusMonitor()
        }
        // 7. P2pMetrics（获取节点指标）
        val metricsRes = StringBuilder()
        val metricsRet = P2pNative.p2pMetrics("54.251.228.32", 29001, metricsRes)
        Log.i(TAG, "P2pMetrics 返回码: $metricsRet, 内容: $metricsRes")
        startP2pStatusMonitor()

        // 8. P2pHelth（健康检查，注意原接口拼写是 Helth，可能为 typo，应为 Health）
        val healthRes = StringBuilder()
        val healthRet = P2pNative.p2pHelth("54.251.228.32", 29001, healthRes)
        Log.i(TAG, "P2pHelth 返回码: $healthRet, 内容: $healthRes")
        startP2pStatusMonitor()

        // 9. P2pGetClientMetrics（客户端整体指标）
        val clientMetricsRes = StringBuilder()
        val clientMetricsRet = P2pNative.p2pGetClientMetrics(clientMetricsRes)
        Log.i(TAG, "P2pGetClientMetrics 返回码: $clientMetricsRet, 内容: $clientMetricsRes")
        startP2pStatusMonitor()

        // 10. P2pWhiteIpList（获取白名单IP列表）
        val whiteList = mutableListOf<String>()
        val whiteListRet = P2pNative.p2pWhiteIpList(whiteList)
        Log.i(TAG, "P2pWhiteIpList 返回码: $whiteListRet, 白名单: $whiteList")
        startP2pStatusMonitor()

        // 11. P2pAddWhiteIp（添加白名单IP）
        val addIpRet = P2pNative.p2pAddWhiteIp("192.168.1.100")
        Log.i(TAG, "P2pAddWhiteIp 添加 192.168.1.100 返回码: $addIpRet")
        startP2pStatusMonitor()

        // 再次获取白名单验证是否添加成功
        val whiteList2 = mutableListOf<String>()
        P2pNative.p2pWhiteIpList(whiteList2)
        Log.i(TAG, "添加后白名单: $whiteList2")
        startP2pStatusMonitor()

//        testHttpHeartbeat(getLocalIpAddress(), 8443, 0);
//        testUdpHeartbeat(getLocalIpAddress(), 19002, 0);
        // 最后停止（实际项目中通常在应用销毁时调用）
        P2pNative.p2pStop()
        Log.i(TAG, "停止")
    }

}