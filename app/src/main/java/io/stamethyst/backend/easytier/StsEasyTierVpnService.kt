package io.stamethyst.backend.easytier

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log

class StsEasyTierVpnService : VpnService() {
    companion object {
        private const val TAG = "StsEasyTierVpnService"

        const val ACTION_START_SESSION = "io.stamethyst.action.EASYTIER_VPN_START_SESSION"
        const val ACTION_STOP_SESSION = "io.stamethyst.action.EASYTIER_VPN_STOP_SESSION"
        const val EXTRA_INSTANCE_NAME = "io.stamethyst.extra.EASYTIER_INSTANCE_NAME"
        const val EXTRA_IPV4_CIDR = "io.stamethyst.extra.EASYTIER_IPV4_CIDR"
        const val EXTRA_ROUTE_CIDRS = "io.stamethyst.extra.EASYTIER_ROUTE_CIDRS"

        private const val VPN_MTU = 1400

        fun startSession(
            context: Context,
            instanceName: String,
            ipv4Cidr: String,
            routeCidrs: List<String>,
        ) {
            val appContext = context.applicationContext
            appContext.startService(
                Intent(appContext, StsEasyTierVpnService::class.java).apply {
                    action = ACTION_START_SESSION
                    putExtra(EXTRA_INSTANCE_NAME, instanceName)
                    putExtra(EXTRA_IPV4_CIDR, ipv4Cidr)
                    putStringArrayListExtra(EXTRA_ROUTE_CIDRS, ArrayList(routeCidrs))
                }
            )
        }

        fun stopSession(context: Context) {
            val appContext = context.applicationContext
            appContext.startService(
                Intent(appContext, StsEasyTierVpnService::class.java).apply {
                    action = ACTION_STOP_SESSION
                }
            )
        }
    }

    @Volatile
    private var tunnelInterface: ParcelFileDescriptor? = null
    @Volatile
    private var workerThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> {
                val safeIntent = Intent(intent)
                val thread = Thread(
                    { runStartSession(startId, safeIntent) },
                    "STS-EasyTierVpn"
                )
                workerThread?.interrupt()
                workerThread = thread
                thread.start()
                return START_STICKY
            }
            ACTION_STOP_SESSION -> {
                workerThread?.interrupt()
                workerThread = null
                closeTunnel()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        EasyTierJniBridge.stopAllInstances()
        closeTunnel()
        val snapshot = EasyTierSessionController.persistSnapshot(
            context = applicationContext,
            snapshot = EasyTierSessionController.buildPermissionRevokedSnapshot(
                context = applicationContext,
                previous = EasyTierSessionController.currentSnapshot(applicationContext),
            ),
            extraLines = listOf("vpn_revoked=true"),
        )
        EasyTierProcessService.broadcastSnapshot(
            context = applicationContext,
            resultCode = EasyTierProcessService.RESULT_PERMISSION_REQUIRED,
            snapshot = snapshot,
        )
        super.onRevoke()
    }

    override fun onDestroy() {
        workerThread?.interrupt()
        workerThread = null
        closeTunnel()
        super.onDestroy()
    }

    private fun runStartSession(startId: Int, intent: Intent) {
        val instanceName = intent.getStringExtra(EXTRA_INSTANCE_NAME).orEmpty().trim()
        val ipv4Cidr = intent.getStringExtra(EXTRA_IPV4_CIDR).orEmpty().trim()
        val routeCidrs = intent.getStringArrayListExtra(EXTRA_ROUTE_CIDRS).orEmpty()
        if (instanceName.isBlank() || ipv4Cidr.isBlank()) {
            reportFailure(
                summary = "EasyTier VPN start request was missing instance name or IPv4 CIDR.",
                failureCategory = EasyTierFailureCategory.Unknown,
                extraLines = listOf(
                    "vpn_start_missing_fields=true",
                    "instance_name=${instanceName.ifBlank { "<empty>" }}",
                    "ipv4_cidr=${ipv4Cidr.ifBlank { "<empty>" }}",
                ),
            )
            stopSelf(startId)
            return
        }

        val address = parseEasyTierIpv4Cidr(ipv4Cidr)
        if (address == null) {
            reportFailure(
                summary = "EasyTier runtime returned an invalid virtual IPv4 CIDR: $ipv4Cidr",
                failureCategory = EasyTierFailureCategory.Unknown,
                extraLines = listOf("invalid_virtual_ipv4_cidr=$ipv4Cidr"),
            )
            stopSelf(startId)
            return
        }

        val routes = buildVpnRoutes(ipv4Cidr, routeCidrs)
        if (routes.isEmpty()) {
            reportFailure(
                summary = "EasyTier runtime did not provide any usable IPv4 route for Android VPN.",
                failureCategory = EasyTierFailureCategory.Unknown,
                extraLines = listOf("vpn_route_count=0"),
            )
            stopSelf(startId)
            return
        }

        closeTunnel()
        runCatching {
            val builder = Builder()
                .setSession("Slay the Amethyst EasyTier")
                .setMtu(VPN_MTU)
                .addAddress(address.address, address.prefixLength)
            routes.forEach { route ->
                builder.addRoute(route.address, route.prefixLength)
            }
            val established = builder.establish()
                ?: throw IllegalStateException("Android returned null while establishing EasyTier VPN.")
            tunnelInterface = established
            val fd = established.fd
            EasyTierJniBridge.setTunFd(instanceName, fd).exceptionOrNull()?.let { error ->
                throw error
            }
            reportConnected(
                ipv4Cidr = address.cidr,
                routeCidrs = routes.map(EasyTierIpv4Cidr::cidr),
                instanceName = instanceName,
                fd = fd,
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to start EasyTier VPN session", error)
            closeTunnel()
            reportFailure(
                summary = EasyTierJniBridge.failureSummary(error),
                failureCategory = EasyTierJniBridge.failureCategory(error),
                extraLines = buildList {
                    add("vpn_establish_failed=true")
                    add("instance_name=$instanceName")
                    add("ipv4_cidr=$ipv4Cidr")
                    add("route_cidrs=${routes.joinToString(",") { it.cidr }}")
                },
                error = error,
            )
            stopSelf(startId)
        }
    }

    private fun buildVpnRoutes(
        ipv4Cidr: String,
        routeCidrs: List<String>,
    ): List<EasyTierIpv4Cidr> {
        return (listOf(ipv4Cidr) + routeCidrs)
            .mapNotNull(::normalizeEasyTierIpv4Route)
            .filterNot { route -> isDefaultEasyTierIpv4Route(route.cidr) }
            .distinctBy(EasyTierIpv4Cidr::cidr)
    }

    private fun reportConnected(
        ipv4Cidr: String,
        routeCidrs: List<String>,
        instanceName: String,
        fd: Int,
    ) {
        val nowMs = System.currentTimeMillis()
        val previous = EasyTierSessionController.currentSnapshot(applicationContext)
        val snapshot = EasyTierSessionController.persistSnapshot(
            context = applicationContext,
            snapshot = previous.copy(
                status = EasyTierConnectionStatus.CONNECTED,
                failureCategory = EasyTierFailureCategory.None,
                connectedAtMs = previous.connectedAtMs ?: nowMs,
                lastUpdatedAtMs = nowMs,
                lastErrorSummary = "",
                assignedIpv4Cidr = ipv4Cidr,
                lastSessionState = previous.lastSessionState,
                lastRoomState = previous.lastRoomState,
            ),
            extraLines = buildList {
                add("vpn_established=true")
                add("tun_fd_attached=true")
                add("runtime_instance_name=$instanceName")
                add("tun_fd=$fd")
                add("vpn_routes=${routeCidrs.joinToString(",")}")
            },
        )
        EasyTierProcessService.broadcastSnapshot(
            context = applicationContext,
            resultCode = EasyTierProcessService.RESULT_CONNECTED,
            snapshot = snapshot,
        )
    }

    private fun reportFailure(
        summary: String,
        failureCategory: EasyTierFailureCategory,
        extraLines: List<String>,
        error: Throwable? = null,
    ) {
        val snapshot = EasyTierSessionController.persistSnapshot(
            context = applicationContext,
            snapshot = EasyTierSessionController.buildFailureSnapshot(
                previous = EasyTierSessionController.currentSnapshot(applicationContext),
                summary = summary,
                failureCategory = failureCategory,
            ),
            extraLines = extraLines,
            error = error,
        )
        EasyTierProcessService.broadcastSnapshot(
            context = applicationContext,
            resultCode = EasyTierProcessService.RESULT_FAILURE,
            snapshot = snapshot,
            errorSummary = summary,
        )
    }

    private fun closeTunnel() {
        runCatching { tunnelInterface?.close() }
        tunnelInterface = null
    }
}
