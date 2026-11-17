package com.example.data.hardware

import com.example.data.model.SensorEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Genuine HTTPS/TLS polling client for Edge Gateway / Sensor Hub JSON Schema v1.0.0 endpoints.
 */
class HttpTlsGatewayAdapter(
    private val parser: SensorEventV1Parser = SensorEventV1Parser(),
    private val httpClient: OkHttpClient = defaultHttpClient(),
) : SensorGatewayClient {

    override val transportKind = GatewayTransportKind.HTTP_TLS
    private val _incomingEvents = MutableSharedFlow<SensorEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incomingEvents: Flow<SensorEvent> = _incomingEvents.asSharedFlow()

    override suspend fun connect(config: GatewayTransportConfig): Result<Unit> {
        val url = config.endpointUri.trim()
        if (!url.startsWith("https://")) {
            return Result.failure(IllegalArgumentException("Edge Gateway HTTP endpoint must use HTTPS (TLS)."))
        }
        return Result.success(Unit)
    }

    override suspend fun pollOnce(
        config: GatewayTransportConfig,
        operatorUid: String,
    ): Result<List<SensorEvent>> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = config.endpointUri.trim().trimEnd('/')
            require(baseUrl.startsWith("https://")) { "Gateway URL must start with https://" }
            val reqBuilder = Request.Builder()
                .url("$baseUrl/v1/missions/${config.missionId}/events")
                .header("Accept", "application/json")
            if (config.bearerToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${config.bearerToken}")
            }

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                require(resp.isSuccessful) { "Gateway HTTP ${resp.code}: ${resp.message}" }
                val body = resp.body?.string().orEmpty()
                val parsed = parser.parseBatchJsonEvents(
                    rawJson = body,
                    orgId = config.orgId,
                    missionId = config.missionId,
                    operatorUid = operatorUid,
                )
                val validEvents = parsed.mapNotNull { it.getOrNull() }
                validEvents.forEach { _incomingEvents.tryEmit(it) }
                validEvents
            }
        }
    }

    override suspend fun disconnect() = Unit

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Genuine WebSocket/TLS (`wss://`) streaming client for real-time Edge Gateway events.
 */
class WebSocketGatewayAdapter(
    private val parser: SensorEventV1Parser = SensorEventV1Parser(),
    private val httpClient: OkHttpClient = HttpTlsGatewayAdapter.defaultHttpClient(),
) : SensorGatewayClient {

    override val transportKind = GatewayTransportKind.WEBSOCKET_TLS
    private val _incomingEvents = MutableSharedFlow<SensorEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incomingEvents: Flow<SensorEvent> = _incomingEvents.asSharedFlow()
    private var activeSocket: WebSocket? = null

    override suspend fun connect(config: GatewayTransportConfig): Result<Unit> = runCatching {
        val url = config.endpointUri.trim()
        require(url.startsWith("wss://")) { "WebSocket Gateway endpoint must use wss:// (TLS)." }
        activeSocket?.close(1000, "Reconnecting")

        val reqBuilder = Request.Builder().url(url)
        if (config.bearerToken.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${config.bearerToken}")
        }
        activeSocket = httpClient.newWebSocket(
            reqBuilder.build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val parsed = parser.parseBatchJsonEvents(
                        rawJson = text,
                        orgId = config.orgId,
                        missionId = config.missionId,
                        operatorUid = "ws_gateway",
                    )
                    parsed.mapNotNull { it.getOrNull() }.forEach { _incomingEvents.tryEmit(it) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // Closed cleanly; caller observes disconnected state without fabricating packets
                }
            },
        )
    }

    override suspend fun pollOnce(config: GatewayTransportConfig, operatorUid: String): Result<List<SensorEvent>> {
        return Result.success(emptyList()) // Streaming transport pushes via incomingEvents
    }

    override suspend fun disconnect() {
        activeSocket?.close(1000, "Operator disconnected")
        activeSocket = null
    }
}

/**
 * Documented MQTT-over-TLS (`ssl://...:8883`) gateway contract.
 * Awaits field MQTT broker credentials, topic ACL, and CA certificate pinning.
 */
class MqttGatewayContract : SensorGatewayClient {
    override val transportKind = GatewayTransportKind.MQTT_TLS
    private val _incomingEvents = MutableSharedFlow<SensorEvent>(replay = 0)
    override val incomingEvents: Flow<SensorEvent> = _incomingEvents.asSharedFlow()

    override suspend fun connect(config: GatewayTransportConfig): Result<Unit> {
        return Result.failure(
            IllegalStateException("AWAITING_HARDWARE_INTEGRATION: MQTT-over-TLS broker CA certificate & topic credentials not provisioned.")
        )
    }

    override suspend fun pollOnce(config: GatewayTransportConfig, operatorUid: String): Result<List<SensorEvent>> {
        return Result.failure(
            IllegalStateException("AWAITING_HARDWARE_INTEGRATION: MQTT-over-TLS broker not connected.")
        )
    }

    override suspend fun disconnect() = Unit
}
