package dev.yikuo.youcommuter.core.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** Reports grants only. Permission requests belong to an explicit UI flow. */
data class LocationCapability(val preciseLocation: Boolean, val backgroundLocation: Boolean) {
    companion object {
        fun read(context: Context) = LocationCapability(
            preciseLocation = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            backgroundLocation = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )
    }
}
