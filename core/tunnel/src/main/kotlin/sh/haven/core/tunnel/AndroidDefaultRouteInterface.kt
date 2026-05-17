package sh.haven.core.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log

internal object AndroidDefaultRouteInterface {
    private const val TAG = "AndroidDefaultRouteInterface"

    fun current(context: Context): String {
        return try {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
                ?: return ""
            val network = connectivity.activeNetwork ?: return ""
            connectivity.getLinkProperties(network)?.interfaceName.orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to read active network interface", t)
            ""
        }
    }
}
