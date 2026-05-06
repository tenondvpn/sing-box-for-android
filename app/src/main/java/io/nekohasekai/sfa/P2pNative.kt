package io.nekohasekai.sfa
object P2pNative {
    init {
        System.loadLibrary("p2pnet")
    }

    // 示例：所有函数返回错误码，输出参数通过by ref实现
    external fun p2pInit(config: String, error: StringBuilder, initRes: StringBuilder, threadCount: Int): Int

    external fun p2pGetAllStatus(): String?

    external fun p2pStop(): Int

    external fun p2pGetConfigWithKeys(keys: List<String>, res: MutableMap<String, String>): Int

    external fun p2pGetConfigWithStartKey(startKey: String, length: Int, nextStartKey: StringBuilder, res: MutableMap<String, String>): Int

    external fun p2pPut(kvs: Map<String, String>): Int

    external fun p2pDelete(keys: List<String>): Int

    external fun p2pMetrics(ip: String, port: Int, res: StringBuilder): Int

    external fun p2pHelth(ip: String, port: Int, res: StringBuilder): Int

    external fun p2pGetClientMetrics(res: StringBuilder): Int

    external fun p2pWhiteIpList(res: MutableList<String>): Int

    external fun p2pAddWhiteIp(ip: String): Int
}