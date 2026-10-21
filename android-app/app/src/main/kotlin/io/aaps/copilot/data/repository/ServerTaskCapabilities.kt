package io.aaps.copilot.data.repository

import kotlinx.serialization.json.JsonObject

/** Select one independently advertised route; global text readiness cannot enable images. */
internal object ServerTaskCapabilities {
    const val ENVELOPE_REVISION = "server-codex-ai-capabilities-r1"
    fun select(body: ByteArray, kind: String): JsonObject? {
        val value = HistoryJson.parse(body, 65_536)
        if ("routes" !in value) {
            return value.takeIf { strings(it.getValue("task_kinds")) == listOf(kind) }
        }
        HistoryJson.fields(value, setOf("revision", "inference_enabled", "task_kinds",
            "input_modalities", "output_modalities", "routes"))
        check(HistoryJson.string(value.getValue("revision")) == ENVELOPE_REVISION)
        val kinds = strings(value.getValue("task_kinds"))
        check(kinds.isNotEmpty() && kinds.size == kinds.toSet().size &&
            kinds.all { it in setOf("CHAT", "MEAL_PHOTO") })
        val routes = HistoryJson.obj(value.getValue("routes"))
        check(routes.keys == kinds.toSet())
        val inputs = mutableListOf<String>()
        val outputs = mutableListOf<String>()
        var anyReady = false
        kinds.forEach { name ->
            val route = HistoryJson.obj(routes.getValue(name))
            check(strings(route.getValue("task_kinds")) == listOf(name))
            val input = if (name == "CHAT") "TEXT" else "IMAGE"
            val output = if (name == "CHAT") "TEXT" else "MEAL_ESTIMATE"
            check(strings(route.getValue("input_modalities")) == listOf(input))
            check(strings(route.getValue("output_modalities")) == listOf(output))
            inputs += input; outputs += output
            val routeReady = HistoryJson.bool(route.getValue("inference_enabled"))
            anyReady = anyReady || routeReady
        }
        check(strings(value.getValue("input_modalities")) == inputs)
        check(strings(value.getValue("output_modalities")) == outputs)
        check(HistoryJson.bool(value.getValue("inference_enabled")) == anyReady)
        return routes[kind]?.let(HistoryJson::obj)
    }
    private fun strings(value: kotlinx.serialization.json.JsonElement): List<String> =
        HistoryJson.array(value).map(HistoryJson::string)
}
