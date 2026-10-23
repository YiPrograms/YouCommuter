package dev.yikuo.youcommuter.core.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat

data class NotificationCapability(
    val permissionGranted: Boolean,
    val notificationsEnabled: Boolean,
) {
    val canPost: Boolean get() = permissionGranted && notificationsEnabled

    companion object {
        fun read(context: Context) = NotificationCapability(
            permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
    }
}
