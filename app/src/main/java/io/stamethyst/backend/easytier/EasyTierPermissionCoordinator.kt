package io.stamethyst.backend.easytier

import android.content.Context
import android.content.Intent
import android.net.VpnService

object EasyTierPermissionCoordinator {
    @JvmStatic
    fun prepareVpnPermissionIntent(context: Context): Intent? = VpnService.prepare(context)

    @JvmStatic
    fun hasVpnPermission(context: Context): Boolean = prepareVpnPermissionIntent(context) == null
}
