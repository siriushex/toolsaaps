package integration.mealpreview

import app.aaps.core.nssdk.localmodel.clientcontrol.*
import app.aaps.core.nssdk.utils.ClientControlCrypto
import kotlinx.serialization.json.Json

/** Offline codec test against the compiled AAPS SDK. Never dispatches the decoded request. */
fun main() {
    val request = Json.decodeFromString<SignedEnvelope>(checkNotNull(readlnOrNull()))
    val key = ByteArray(32) { it.toByte() }
    try {
        check(ClientControlCrypto.verifyEnvelope(key, request))
        val msg = Json.decodeFromString<ClientControlMessage>(request.payload) as ClientControlMessage.BatchPrepare
        val one = msg.actions.single()
        check(one == BatchActionDto(type = BatchActionDto.TYPE_BOLUS, carbs = 12, notes = "copilot:manual:meal:sample"))
        val result = BolusPreview(79, lines = listOf(ConfirmationLineDto("NORMAL", "12 g")),
            manualMeal = ManualMealPreviewDto(one.notes, one.carbs, 0.0))
        val ack = ClientControlCrypto.signAck(key, AckEnvelope(request.clientId, request.counter, AckPhase.Done, AckStatus.Ok,
            payload = Json.encodeToString(BolusPreview.serializer(), result), timestamp = request.timestamp + 1, signature = ""))
        print(Json.encodeToString(AckEnvelope.serializer(), ack))
    } finally { key.fill(0) }
}
