package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.broadcastReceiver

class VpnRequestActivity : AppCompatActivity() {
    private var receiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getSystemService<KeyguardManager>()!!.isKeyguardLocked) {
            receiver = broadcastReceiver { _, _ -> connect.launch(null) }
            if (SDK_INT >= 33) {
                registerReceiver(
                    receiver,
                    IntentFilter(Intent.ACTION_USER_PRESENT),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
            }
        } else connect.launch(null)
    }

    private val connect = registerForActivityResult(StartService()) {
        if (it) Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (receiver != null) unregisterReceiver(receiver)
    }

    class StartService : ActivityResultContract<Void?, Boolean>() {
        private var cachedIntent: Intent? = null

        override fun getSynchronousResult(
            context: Context,
            input: Void?,
        ): SynchronousResult<Boolean>? {
            if (DataStore.serviceMode == Key.MODE_VPN) VpnService.prepare(context)?.let { intent ->
                // 刚刷完机时 VPN 授权尚未授予。先尝试用 root 同步授权，
                // 成功后系统授权框就不会出现，首次连接也不弹。
                if (grantVpnPermissionViaRoot(context) && VpnService.prepare(context) == null) {
                    SagerNet.startService()
                    return SynchronousResult(false)
                }
                cachedIntent = intent
                return null
            }
            SagerNet.startService()
            return SynchronousResult(false)
        }

        // 通过 root 把 ACTIVATE_VPN AppOps 设为 allow（等价于在系统框点 OK）。
        // 放在工作线程并限时 join，避免 su 卡住导致主线程 ANR。
        private fun grantVpnPermissionViaRoot(context: Context): Boolean {
            val pkg = context.packageName
            var ok = false
            val worker = Thread {
                try {
                    val process = ProcessBuilder(
                        "su", "-c", "appops set $pkg ACTIVATE_VPN allow"
                    ).redirectErrorStream(true).start()
                    process.waitFor()
                    ok = process.exitValue() == 0
                } catch (e: Exception) {
                    Logs.w("grantVpnPermissionViaRoot failed: ${e.message}")
                }
            }
            worker.start()
            worker.join(4000)
            return ok
        }

        override fun createIntent(context: Context, input: Void?) =
            cachedIntent!!.also { cachedIntent = null }

        override fun parseResult(resultCode: Int, intent: Intent?) =
            if (resultCode == Activity.RESULT_OK) {
                SagerNet.startService()
                false
            } else {
                Logs.e("Failed to start VpnService: $intent")
                true
            }
    }


}
