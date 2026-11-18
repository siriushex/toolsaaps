package integration.finalsettings

import app.aaps.core.nssdk.networking.NsLocalSettingsPolling
import app.aaps.core.nssdk.networking.NsLoopbackUploadIdentity
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Test-only peer. No default TLS changes, REST client, reconnect or therapy handler. */
fun main() {
    val ok = runCatching {
        val port = System.getenv("SETTINGS_TEST_PORT").toInt()
        val identity = NsLoopbackUploadIdentity(File(System.getenv("SETTINGS_TEST_CA")).readBytes(),
            File(System.getenv("SETTINGS_TEST_LEAF")).readBytes())
        NsLocalSettingsPolling(identity, port).use { calls ->
            val socket = IO.socket("https://127.0.0.1:$port/storage", IO.Options().apply {
                callFactory = calls
                forceNew = true
                multiplex = false
                transports = arrayOf("polling")
                upgrade = false
                reconnection = false
            })
            val result = CompletableFuture<Boolean>()
            val acked = CompletableFuture<Boolean>()
            try {
                socket.on(Socket.EVENT_CONNECT_ERROR) { acked.complete(false); result.complete(false) }
                socket.on(Socket.EVENT_CONNECT) {
                    val body = JSONObject().put("accessToken", System.getenv("SETTINGS_TEST_ACCESS"))
                        .put("collections", JSONArray(listOf("settings")))
                    socket.emit("subscribe", body, Ack { args ->
                        val reply = args.singleOrNull() as? JSONObject
                        acked.complete(reply?.optBoolean("success") == true &&
                            reply.optJSONArray("collections")?.let { it.length() == 1 && it.optString(0) == "settings" } == true)
                    })
                }
                socket.on("create") { args ->
                    val event = args.singleOrNull() as? JSONObject
                    result.complete(event?.optString("colName") == "settings" &&
                        event.optJSONObject("doc")?.optString("identifier") == "pinned-settings-proof")
                }
                socket.connect()
                check(acked.get(12, TimeUnit.SECONDS) && result.get(12, TimeUnit.SECONDS))
            } finally {
                socket.off()
                socket.close()
            }
        }
    }.isSuccess
    println(if (ok) "PINNED_SETTINGS_OK" else "PINNED_SETTINGS_FAILED")
    exitProcess(if (ok) 0 else 1)
}
