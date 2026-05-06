package io.nekohasekai.sfa

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.util.encoders.Hex
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// 响应数据类
data class ApiResponse(val statusCode: Int, val body: String, val error: Exception? = null)

object P2PClient {
    private var globalSecurity: CryptoUtils.Ecdsa? = null
    private var publicKeyHex: String = ""
    private var isInitialized = false
    private val gson = Gson()

    // 1. 初始化 Security
    @Synchronized
    fun initSecurity(privateKeyHex: String): Exception? {
        if (isInitialized) return null

        try {
            val privateKeyBytes = Hex.decode(privateKeyHex)
            if (privateKeyBytes.size != 32) {
                return Exception("invalid private key: must be 64 hex chars (32 bytes)")
            }

            val security = CryptoUtils.Ecdsa()
            if (!security.setPrivateKey(privateKeyBytes)) {
                return Exception("failed to set private key")
            }

            val pubKey = security.getPublicKeyCompressed()
            if (pubKey.length != 66) {
                return Exception("failed to compute compressed public key")
            }

            globalSecurity = security
            publicKeyHex = pubKey
            isInitialized = true

            println("Global ECDSA security initialized successfully")
            println("Address:   ${security.getAddress()}")
            println("PublicKey: $publicKeyHex")

        } catch (e: Exception) {
            return e
        }
        return null
    }

    fun mustInitSecurity(privateKeyHex: String) {
        val err = initSecurity(privateKeyHex)
        if (err != null) throw RuntimeException(err)
    }

    // 2. HTTP Client 配置 (跳过 SSL 验证)
    private val unsafeClient: OkHttpClient by lazy {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    private fun doRequest(method: String, url: String, body: Any?): ApiResponse {
        return try {
            val jsonBody = if (body != null) gson.toJson(body) else ""
            val requestBuilder = Request.Builder().url(url)
                .header("Accept", "application/json")

            if (body != null) {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val reqBody = jsonBody.toRequestBody(mediaType)
                requestBuilder.method(method, reqBody)
            } else {
                requestBuilder.method(method, null)
            }

            val response = unsafeClient.newCall(requestBuilder.build()).execute()
            val respBody = response.body?.string() ?: ""

            // 必须关闭 body
            response.close()

            ApiResponse(response.code, respBody, null)
        } catch (e: Exception) {
            ApiResponse(0, "", e)
        }
    }

    private fun doGet(url: String) = doRequest("GET", url, null)
    private fun doPost(url: String, payload: Any) = doRequest("POST", url, payload)

    // --- API 实现 ---

    fun health(host: String, port: Int) = doGet("https://$host:$port/health")
    fun metrics(host: String, port: Int) = doGet("https://$host:$port/metrics")

    fun clientInit(host: String, port: Int, localPort: Int): ApiResponse {
        val url = "https://$host:$port/api/v1/client_init"
        return doPost(url, mapOf("local_port" to localPort))
    }

    fun apiGet(host: String, port: Int, key: String): ApiResponse {
        val url = "https://$host:$port/api/v1/get"
        return doPost(url, mapOf("key" to key))
    }

    fun apiGets(host: String, port: Int, keys: List<String>): ApiResponse {
        val url = "https://$host:$port/api/v1/gets"
        return doPost(url, mapOf("keys" to keys))
    }

    fun apiPrefix(host: String, port: Int, beginKey: String, length: Int): ApiResponse {
        val url = "https://$host:$port/api/v1/prefix"
        return doPost(url, mapOf("begin_key" to beginKey, "length" to length))
    }

    // --- 签名相关 API ---

    private fun doPostSecure(
        url: String,
        payload: MutableMap<String, Any>,
        signDataBuilder: (MutableMap<String, Any>) -> ByteArray
    ): ApiResponse {
        if (!isInitialized) return ApiResponse(0, "", Exception("security not initialized"))

        try {
            // 1. 构建签名数据
            val dataToSign = signDataBuilder(payload)

            // 2. Keccak256 哈希
            val msgHash = CryptoUtils.keccak256(dataToSign)
            val msgHashHex = Hex.toHexString(msgHash)

            // 3. 签名
            val signHex = globalSecurity?.sign(msgHashHex)
                ?: return ApiResponse(0, "", Exception("sign message failed"))

            // 4. 填充签名
            payload["sign"] = signHex

            return doPost(url, payload)
        } catch (e: Exception) {
            return ApiResponse(0, "", e)
        }
    }

    data class KV(val key: String, val value: String, val version: Long)

    fun apiPut(host: String, port: Int, kvs: Map<String, String>): ApiResponse {
        if (kvs.isEmpty()) return ApiResponse(0, "", Exception("kvs cannot be empty"))

        val url = "https://$host:$port/api/v1/put"
        val pubKeyBytes = Hex.decode(publicKeyHex)
        val currentVersion = System.currentTimeMillis() * 1000 // Microseconds approximation

        val kvList = ArrayList<KV>()
        val payload = mutableMapOf<String, Any>(
            "public_key" to publicKeyHex,
            "kvs" to kvList // 引用，稍后填充
        )

        val signBuilder: (MutableMap<String, Any>) -> ByteArray = { _ ->
            var dataToSign = pubKeyBytes.clone()

            for ((key, value) in kvs) {
                // 添加到 JSON 结构
                kvList.add(KV(key, value, currentVersion))

                // 拼接到签名数据
                dataToSign += key.toByteArray()
                dataToSign += value.toByteArray()
                dataToSign += currentVersion.toString().toByteArray()
            }
            dataToSign
        }

        return doPostSecure(url, payload, signBuilder)
    }

    data class KeyVersion(val key: String, val version: Long)

    fun apiDelete(host: String, port: Int, keys: List<String>): ApiResponse {
        if (keys.isEmpty()) return ApiResponse(0, "", Exception("keys cannot be empty"))

        val url = "https://$host:$port/api/v1/delete"
        val pubKeyBytes = Hex.decode(publicKeyHex)
        val currentVersion = System.currentTimeMillis() * 1000

        val kvList = ArrayList<KeyVersion>()
        val payload = mutableMapOf<String, Any>(
            "public_key" to publicKeyHex,
            "kvs" to kvList
        )

        val signBuilder: (MutableMap<String, Any>) -> ByteArray = { _ ->
            var dataToSign = pubKeyBytes.clone()
            for (key in keys) {
                kvList.add(KeyVersion(key, currentVersion))
                dataToSign += key.toByteArray()
                dataToSign += currentVersion.toString().toByteArray()
            }
            dataToSign
        }

        return doPostSecure(url, payload, signBuilder)
    }

    // 通用列表签名构建器 (用于 AddUser, AddNode 等结构相似的接口)
    private fun createListSignBuilder(
        listKey: String,
        items: List<String>,
        version: Long
    ): (MutableMap<String, Any>) -> ByteArray {
        return { _ ->
            val pubKeyBytes = Hex.decode(publicKeyHex)
            var dataToSign = pubKeyBytes.clone()
            for (item in items) {
                dataToSign += item.toByteArray()
            }
            dataToSign += version.toString().toByteArray()
            dataToSign
        }
    }

    fun addUser(host: String, port: Int, users: List<String>): ApiResponse {
        val url = "https://$host:$port/api/v1/add_user"
        val version = System.currentTimeMillis() * 1000
        val payload = mutableMapOf<String, Any>(
            "public_key" to publicKeyHex,
            "users" to users,
            "version" to version
        )
        return doPostSecure(url, payload, createListSignBuilder("users", users, version))
    }

    fun removeUser(host: String, port: Int, users: List<String>): ApiResponse {
        val url = "https://$host:$port/api/v1/remove_user"
        val version = System.currentTimeMillis() * 1000
        val payload = mutableMapOf<String, Any>(
            "public_key" to publicKeyHex,
            "users" to users,
            "version" to version
        )
        return doPostSecure(url, payload, createListSignBuilder("users", users, version))
    }

    fun getUsers(host: String, port: Int): ApiResponse {
        val url = "https://$host:$port/api/v1/get_users"
        return doPost(url, mapOf<String, Any>())
    }

    fun addNode(host: String, port: Int, nodes: List<String>): ApiResponse {
        val url = "https://$host:$port/api/v1/add_node"
        val version = System.currentTimeMillis() * 1000
        val payload = mutableMapOf<String, Any>(
            "public_key" to publicKeyHex,
            "nodes" to nodes,
            "version" to version
        )
        return doPostSecure(url, payload, createListSignBuilder("nodes", nodes, version))
    }

    fun removeNode(host: String, port: Int, nodes: List<String>): ApiResponse {
        val url = "https://$host:$port/api/v1/remove_node"
        val version = System.currentTimeMillis() * 1000
        val payload = mutableMapOf<String, Any>(
            "public_key" to publicKeyHex,
            "nodes" to nodes,
            "version" to version
        )
        return doPostSecure(url, payload, createListSignBuilder("nodes", nodes, version))
    }

    fun getNodes(host: String, port: Int): ApiResponse {
        val url = "https://$host:$port/api/v1/get_nodes"
        return doPost(url, mapOf<String, Any>())
    }

    fun validCountry(host: String, port: Int, countries: List<String>): ApiResponse {
        val url = "https://$host:$port/api/v1/valid_country"
        val version = System.currentTimeMillis() * 1000
        val payload = mutableMapOf<String, Any>(
            "public_key" to publicKeyHex,
            "country" to countries, // 注意这里字段是 country
            "version" to version
        )
        return doPostSecure(url, payload, createListSignBuilder("country", countries, version))
    }

    fun removeCountry(host: String, port: Int, countries: List<String>): ApiResponse {
        val url = "https://$host:$port/api/v1/remove_country"
        val version = System.currentTimeMillis() * 1000
        val payload = mutableMapOf<String, Any>(
            "public_key" to publicKeyHex,
            "country" to countries,
            "version" to version
        )
        return doPostSecure(url, payload, createListSignBuilder("country", countries, version))
    }

    fun getCountry(host: String, port: Int): ApiResponse {
        val url = "https://$host:$port/api/v1/get_country"
        return doPost(url, mapOf<String, Any>())
    }
}