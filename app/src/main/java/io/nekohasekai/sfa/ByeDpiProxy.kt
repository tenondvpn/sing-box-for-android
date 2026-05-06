package io.nekohasekai.sfa


import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ByeDpiProxy {
    companion object {
        init {
            System.loadLibrary("byedpi")
        }
    }
    fun startProxy(preferences: ByeDpiProxyPreferences): Int {
        val args = prepareArgs(preferences)
        return jniStartProxy(args)
    }

    fun stopProxy(): Int {
        return jniStopProxy()
    }

    fun GetFd(): Int {
        return jniGetFd()
    }

    fun initByedpi(vpnServiceInstance: Any) {
        jniInitByedpi(vpnServiceInstance)
    }

    private fun prepareArgs(preferences: ByeDpiProxyPreferences): Array<String> =
        when (preferences) {
            is ByeDpiProxyCmdPreferences -> preferences.args
            is ByeDpiProxyUIPreferences -> preferences.uiargs
        }

    private external fun jniStartProxy(args: Array<String>): Int
    private external fun jniGetFd(): Int
    private external fun jniInitByedpi(vpnServiceInstance: Any)
    private external fun jniStopProxy(): Int
    external fun jniForceClose(): Int
}