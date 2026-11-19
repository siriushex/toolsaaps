package integration.storage

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.*

/** Stock namespace/framing contract over the server's advertised Engine.IO polling transport. */
internal class StoragePollingPeer(private val origin: String, private val access: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private var sid: String? = null
    private fun exchange(packet: String? = null): String {
        val suffix = sid?.let { "&sid=$it" }.orEmpty()
        val request = HttpRequest.newBuilder(URI("$origin/socket.io/?EIO=4&transport=polling$suffix"))
            .timeout(Duration.ofSeconds(7))
        if (packet == null) request.GET() else request.header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString(packet))
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).let {
            check(it.statusCode() == 200)
            it.body()
        }
    }
    fun subscribe() {
        val opened = exchange()
        check(opened.startsWith("0"))
        sid = Json.parseToJsonElement(opened.drop(1)).jsonObject.getValue("sid").jsonPrimitive.content
        exchange("40/storage,")
        check(exchange().startsWith("40/storage,"))
        val body = buildJsonArray {
            add("subscribe")
            add(buildJsonObject {
                put("accessToken", access)
                put("collections", buildJsonArray {
                    listOf("devicestatus", "entries", "profile", "treatments", "foods", "settings").forEach { add(it) }
                })
            })
        }
        exchange("42/storage,1$body")
        val ack = exchange().removePrefix("43/storage,1")
        check(Json.parseToJsonElement(ack).jsonArray.single().jsonObject["success"] == JsonPrimitive(true))
    }
    fun awaitDocument(identifier: String): JsonObject {
        repeat(4) {
            val packets = exchange().split('\u001e')
            if ("2" in packets) exchange("3")
            packets.filter { it.startsWith("42/storage,") }.forEach { packet ->
                val event = Json.parseToJsonElement(packet.removePrefix("42/storage,")).jsonArray
                if (event[0].jsonPrimitive.content !in setOf("create", "update")) return@forEach
                val wrapper = event[1].jsonObject
                val doc = wrapper["doc"]?.jsonObject ?: return@forEach
                check(doc.getValue("srvModified").jsonPrimitive.long > 0)
                if (wrapper["colName"] == JsonPrimitive("settings") && doc["identifier"] == JsonPrimitive(identifier)) return doc
            }
        }
        error("expected storage document unavailable")
    }
    fun close() {
        if (sid != null) runCatching { exchange("1") }
        sid = null
    }
}
