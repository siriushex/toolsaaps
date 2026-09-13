package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AapsCarbHistorySourceTest {

    @Test
    fun requestRegistersFirstAndUsesExactActionsComponentPermissionAndExtras() = runTest {
        val transport = FakeTransport { request ->
            emit(statusResponse(request.nonce(), "BUSY"))
        }
        val source = source(transport)

        val result = source.load(FROM_TS, THROUGH_TS, 0L, 0L, 50)

        assertThat(result).isEqualTo(AapsCarbHistoryResult.Busy)
        assertThat(transport.events).containsExactly("register", "send", "unregister").inOrder()
        assertThat(transport.registeredAction)
            .isEqualTo(AapsCarbHistoryContract.RESPONSE_ACTION)
        assertThat(transport.registeredPermission)
            .isEqualTo(AapsCarbHistoryContract.RESPONSE_PERMISSION)
        assertThat(transport.sent).hasSize(1)
        assertThat(transport.sent.single()).isEqualTo(
            AapsCarbHistoryWireRequest(
                action = AapsCarbHistoryContract.REQUEST_ACTION,
                componentPackage = AapsCarbHistoryContract.AAPS_PACKAGE,
                componentClass = AapsCarbHistoryContract.AAPS_RECEIVER_CLASS,
                extras = mapOf(
                    "nonce" to NONCE,
                    "fromTs" to FROM_TS,
                    "throughTs" to THROUGH_TS,
                    "afterTimestamp" to 0L,
                    "afterId" to 0L,
                    "limit" to 50
                )
            )
        )
    }

    @Test
    fun exactCorrelatedPayloadReturnsPageAndUnregisters() = runTest {
        val transport = FakeTransport { request ->
            emit(payloadResponse(request.nonce(), validPayload()))
        }

        val result = source(transport).load(FROM_TS, THROUGH_TS, 0L, 0L, 50)

        assertThat(result).isInstanceOf(AapsCarbHistoryResult.Page::class.java)
        val page = (result as AapsCarbHistoryResult.Page).value
        assertThat(page.rows).containsExactly(validRow())
        assertThat(page.nextTimestamp).isEqualTo(ROW_TS)
        assertThat(page.nextId).isEqualTo(7L)
        assertThat(page.hasMore).isFalse()
        assertThat(page.generatedAt).isEqualTo(THROUGH_TS + 1_000L)
        assertThat(page.revisionId).isEqualTo(42L)
        assertThat(transport.unregisterCount).isEqualTo(1)
    }

    @Test
    fun wrongNonceIsIgnoredAndDoesNotCauseAnotherRequest() = runTest {
        val transport = FakeTransport { request ->
            emit(statusResponse("other-nonce-00000000", "ERROR"))
            emit(payloadResponse(request.nonce(), validPayload()))
        }

        val result = source(transport).load(FROM_TS, THROUGH_TS, 0L, 0L, 50)

        assertThat(result).isInstanceOf(AapsCarbHistoryResult.Page::class.java)
        assertThat(transport.sent).hasSize(1)
        assertThat(transport.unregisterCount).isEqualTo(1)
    }

    @Test
    fun onlyBusyTimeoutAndErrorStatusesAreAcceptedAsTypedFailures() = runTest {
        val expected = mapOf(
            "BUSY" to AapsCarbHistoryResult.Busy,
            "TIMEOUT" to AapsCarbHistoryResult.Timeout,
            "ERROR" to AapsCarbHistoryResult.Error
        )

        expected.forEach { (status, expectedResult) ->
            val transport = FakeTransport { request ->
                emit(statusResponse(request.nonce(), status))
            }

            assertThat(source(transport).load(FROM_TS, THROUGH_TS, 0L, 0L, 50))
                .isEqualTo(expectedResult)
            assertThat(transport.unregisterCount).isEqualTo(1)
        }
    }

    @Test
    fun responseExtrasMustIncludeValidRevisionForPayloadOrExactStatusKeys() = runTest {
        val invalidExtras = listOf(
            mapOf("nonce" to NONCE),
            mapOf("nonce" to NONCE, "status" to "BUSY", "extra" to 1),
            mapOf("nonce" to NONCE, "payload" to validPayload(), "status" to "BUSY"),
            mapOf("nonce" to NONCE, "status" to "RETRY"),
            mapOf("nonce" to NONCE, "payload" to 42),
            mapOf("nonce" to NONCE, "payload" to validPayload()),
            mapOf(
                "nonce" to NONCE,
                "payload" to validPayload(),
                "revisionId" to "42"
            ),
            mapOf(
                "nonce" to NONCE,
                "payload" to validPayload(),
                "revisionId" to -1L
            )
        )

        invalidExtras.forEach { extras ->
            val transport = FakeTransport { emit(AapsCarbHistoryWireResponse(
                AapsCarbHistoryContract.RESPONSE_ACTION,
                extras
            )) }

            assertThat(source(transport).load(FROM_TS, THROUGH_TS, 0L, 0L, 50))
                .isEqualTo(AapsCarbHistoryResult.Invalid)
            assertThat(transport.unregisterCount).isEqualTo(1)
        }
    }

    @Test
    fun malformedOrOversizedPayloadIsInvalid() = runTest {
        val payloads = listOf(
            "{}",
            " ".repeat(AapsCarbHistoryContract.MAX_PAYLOAD_CHARS + 1),
            "\"${"é".repeat(100_000)}\""
        )

        payloads.forEach { payload ->
            val transport = FakeTransport { request ->
                emit(payloadResponse(request.nonce(), payload))
            }

            assertThat(source(transport).load(FROM_TS, THROUGH_TS, 0L, 0L, 50))
                .isEqualTo(AapsCarbHistoryResult.Invalid)
            assertThat(transport.unregisterCount).isEqualTo(1)
        }
    }

    @Test
    fun jsonMustHaveExactRootAndRowKeysAndCanonicalEncoding() = runTest {
        val payloads = listOf(
            validPayload().replace(
                "\"hasMore\":false}",
                "\"hasMore\":false,\"unexpected\":0}"
            ),
            validPayload().replace(
                "\"nightscoutIdTruncated\":false}",
                "\"nightscoutIdTruncated\":false,\"unexpected\":0}"
            ),
            " ${validPayload()}",
            validPayload().replace("\"amount\":12.5", "\"amount\":1.25e1"),
            validPayload().replace("\"nonce\":\"1", "\"nonce\":\"\\u0031")
        )

        payloads.forEach { payload ->
            val transport = FakeTransport { request ->
                emit(payloadResponse(request.nonce(), payload))
            }

            assertThat(source(transport).load(FROM_TS, THROUGH_TS, 0L, 0L, 50))
                .isEqualTo(AapsCarbHistoryResult.Invalid)
        }
    }

    @Test
    fun gsonEscapedLineSeparatorsParseAndPreserveMetadataAndDigests() = runTest {
        val notes = "meal\u2028note\u2029end"
        val nightscoutId = "ns\u2028id\u2029value"
        val expectedRow = validRow(
            notes = notes,
            notesSha256 = sha256(notes),
            nightscoutId = nightscoutId,
            nightscoutIdSha256 = sha256(nightscoutId)
        )
        val literalPayload = validPayload(rows = listOf(expectedRow))
        val gsonPayload = literalPayload
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")

        val result = loadPayload(gsonPayload)

        assertThat(result).isInstanceOf(AapsCarbHistoryResult.Page::class.java)
        val parsedRow = (result as AapsCarbHistoryResult.Page).value.rows.single()
        assertThat(parsedRow).isEqualTo(expectedRow)
        assertThat(parsedRow.notesSha256).isEqualTo(sha256(notes))
        assertThat(parsedRow.nightscoutIdSha256).isEqualTo(sha256(nightscoutId))
        assertInvalidPayload(literalPayload)
    }

    @Test
    fun pageMustMatchExactWindowNonceAndSafeGeneratedAtSkew() = runTest {
        val payloads = listOf(
            validPayload(nonce = "different-nonce-000000"),
            validPayload(fromTs = FROM_TS + 1L),
            validPayload(throughTs = THROUGH_TS - 1L),
            validPayload(generatedAt = THROUGH_TS - 1L),
            validPayload(generatedAt = THROUGH_TS + TEN_MINUTES_MS + 1L)
        )

        payloads.forEach { payload ->
            val transport = FakeTransport { request ->
                emit(payloadResponse(request.nonce(), payload))
            }

            assertThat(source(transport).load(FROM_TS, THROUGH_TS, 0L, 0L, 50))
                .isEqualTo(AapsCarbHistoryResult.Invalid)
        }
    }

    @Test
    fun rowsMustBeStrictlyOrderedInsideWindowAndAfterExclusiveCursor() = runTest {
        val cursorTs = ROW_TS
        val cursorId = 6L
        val invalidRows = listOf(
            listOf(validRow(timestamp = FROM_TS - 1L)),
            listOf(validRow(timestamp = THROUGH_TS + 1L)),
            listOf(validRow(id = cursorId, timestamp = cursorTs)),
            listOf(validRow(id = 8L, timestamp = ROW_TS), validRow(id = 7L, timestamp = ROW_TS)),
            listOf(validRow(), validRow())
        )

        invalidRows.forEach { rows ->
            val transport = FakeTransport { request ->
                emit(payloadResponse(
                    request.nonce(),
                    validPayload(
                        rows = rows,
                        nextTimestamp = rows.last().timestamp,
                        nextId = rows.last().id
                    )
                ))
            }

            assertThat(source(transport).load(
                FROM_TS,
                THROUGH_TS,
                cursorTs,
                cursorId,
                50
            )).isEqualTo(AapsCarbHistoryResult.Invalid)
        }
    }

    @Test
    fun rowsEnforceIdentityAmountDurationAndFiniteNumericTypes() = runTest {
        val invalidRows = listOf(
            validRow(id = 0L),
            validRow(version = -1),
            validRow(dateCreated = 0L),
            validRow(referenceId = 0L),
            validRow(duration = -1L),
            validRow(duration = TEN_HOURS_MS + 1L)
        )
        val invalidNumericPayloads = listOf(
            validPayload().replace("\"amount\":12.5", "\"amount\":\"12.5\""),
            validPayload().replace("\"amount\":12.5", "\"amount\":1e999"),
            validPayload().replace("\"duration\":0", "\"duration\":0.0"),
            validPayload().replace("\"id\":7", "\"id\":7.0")
        )

        invalidRows.forEach { row ->
            assertInvalidPayload(validPayload(rows = listOf(row)))
        }
        invalidNumericPayloads.forEach { payload ->
            assertInvalidPayload(payload)
        }
    }

    @Test
    fun negativeCorrectionAndExactAmountBoundsAreAccepted() = runTest {
        listOf(-15.0, -400.0, 400.0).forEach { amount ->
            val result = loadPayload(validPayload(rows = listOf(validRow(amount = amount))))

            assertThat(result).isInstanceOf(AapsCarbHistoryResult.Page::class.java)
            assertThat((result as AapsCarbHistoryResult.Page).value.rows.single().amount)
                .isEqualTo(amount)
        }
    }

    @Test
    fun amountsOutsideExactBoundsAreInvalid() = runTest {
        listOf(-400.01, 400.01).forEach { amount ->
            assertInvalidPayload(validPayload(rows = listOf(validRow(amount = amount))))
        }
    }

    @Test
    fun metadataRequiresBoundedUtf8AndConsistentHashAndTruncationFlags() = runTest {
        val note = "meal"
        val noteHash = sha256(note)
        val validMetadata = validRow(
            notes = note,
            notesSha256 = noteHash,
            nightscoutId = "ns-1",
            nightscoutIdSha256 = sha256("ns-1")
        )
        assertThat(loadPayload(validPayload(rows = listOf(validMetadata))))
            .isInstanceOf(AapsCarbHistoryResult.Page::class.java)

        val invalidRows = listOf(
            validRow(notes = note, notesSha256 = null),
            validRow(notes = note, notesSha256 = sha256("other")),
            validRow(notes = note, notesSha256 = noteHash, notesTruncated = true),
            validRow(notes = null, notesSha256 = noteHash),
            validRow(nightscoutId = "ns-1", nightscoutIdSha256 = null),
            validRow(notes = "a".repeat(8 * 1_024 + 1), notesSha256 = sha256("x")),
            validRow(
                nightscoutId = "a".repeat(1_024 + 1),
                nightscoutIdSha256 = sha256("x")
            )
        )

        invalidRows.forEach { row ->
            assertInvalidPayload(validPayload(rows = listOf(row)))
        }
    }

    @Test
    fun truncatedMetadataRequiresNearLimitPrefixAndOriginalDigest() = runTest {
        val visible = "a".repeat(8 * 1_024)
        val row = validRow(
            notes = visible,
            notesSha256 = sha256("$visible-original"),
            notesTruncated = true
        )

        assertThat(loadPayload(validPayload(rows = listOf(row))))
            .isInstanceOf(AapsCarbHistoryResult.Page::class.java)

        assertInvalidPayload(validPayload(rows = listOf(
            row.copy(notes = "short")
        )))
        assertInvalidPayload(validPayload(rows = listOf(
            row.copy(notesSha256 = sha256(visible))
        )))
    }

    @Test
    fun pageRejectsMoreThanTwoHundredRowsEvenWhenRequestLimitIsHigher() = runTest {
        val rows = (1L..201L).map { id ->
            validRow(id = id, timestamp = ROW_TS + id)
        }

        assertInvalidPayload(
            validPayload(
                rows = rows,
                nextTimestamp = rows.last().timestamp,
                nextId = rows.last().id,
                hasMore = true
            ),
            limit = 200
        )
    }

    @Test
    fun nextCursorMustEqualLastRowOrOriginalCursorAndHasMoreNeedsRows() = runTest {
        val invalidPayloads = listOf(
            validPayload(nextId = 8L),
            validPayload(nextTimestamp = ROW_TS + 1L),
            validPayload(rows = emptyList(), nextTimestamp = 0L, nextId = 1L),
            validPayload(rows = emptyList(), nextTimestamp = 0L, nextId = 0L, hasMore = true)
        )

        invalidPayloads.forEach { assertInvalidPayload(it) }

        val empty = validPayload(
            rows = emptyList(),
            nextTimestamp = 0L,
            nextId = 0L,
            hasMore = false
        )
        assertThat(loadPayload(empty)).isInstanceOf(AapsCarbHistoryResult.Page::class.java)
    }

    @Test
    fun invalidRequestDoesNotRegisterOrSend() = runTest {
        val transport = FakeTransport()

        val result = source(transport).load(
            fromTs = THROUGH_TS,
            throughTs = FROM_TS,
            afterTimestamp = 0L,
            afterId = 0L,
            limit = 201
        )

        assertThat(result).isEqualTo(AapsCarbHistoryResult.Invalid)
        assertThat(transport.events).isEmpty()

        val unicodeNonceTransport = FakeTransport()
        val unicodeNonceSource = AapsCarbHistorySource(
            transport = unicodeNonceTransport,
            timeoutMs = 1L,
            nonceFactory = { "é2345678-1234-1234-1234-123456789012" }
        )
        assertThat(unicodeNonceSource.load(FROM_TS, THROUGH_TS, 0L, 0L, 50))
            .isEqualTo(AapsCarbHistoryResult.Invalid)
        assertThat(unicodeNonceTransport.events).isEmpty()
    }

    @Test
    fun registrationOrSendFailureReturnsUnavailableAndCleansUp() = runTest {
        val registerFailure = FakeTransport(registerFailure = IllegalStateException("missing"))
        assertThat(source(registerFailure).load(FROM_TS, THROUGH_TS, 0L, 0L, 50))
            .isEqualTo(AapsCarbHistoryResult.Unavailable)
        assertThat(registerFailure.unregisterCount).isEqualTo(0)

        val sendFailure = FakeTransport(sendFailure = IllegalStateException("missing"))
        assertThat(source(sendFailure).load(FROM_TS, THROUGH_TS, 0L, 0L, 50))
            .isEqualTo(AapsCarbHistoryResult.Unavailable)
        assertThat(sendFailure.unregisterCount).isEqualTo(1)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun transportTimeoutAndCallerCancellationAlwaysUnregister() = runTest {
        val timeoutTransport = FakeTransport()
        val timeoutResult = async {
            source(timeoutTransport, timeoutMs = 1_000L)
                .load(FROM_TS, THROUGH_TS, 0L, 0L, 50)
        }
        advanceUntilIdle()

        assertThat(timeoutResult.await()).isEqualTo(AapsCarbHistoryResult.TransportTimeout)
        assertThat(timeoutTransport.unregisterCount).isEqualTo(1)

        val cancellationTransport = FakeTransport()
        val cancelled = async {
            source(cancellationTransport, timeoutMs = 60_000L)
                .load(FROM_TS, THROUGH_TS, 0L, 0L, 50)
        }
        runCurrent()
        cancelled.cancelAndJoin()

        assertThat(cancellationTransport.unregisterCount).isEqualTo(1)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun malformedExtrasExtractionIsIgnoredUntilTimeoutAndUnregisters() = runTest {
        val validExtras = mapOf<String, Any?>(
            "nonce" to NONCE,
            "status" to "BUSY"
        )
        val validResponse = extractAapsCarbHistoryWireResponse(
            action = { AapsCarbHistoryContract.RESPONSE_ACTION },
            extras = { FakeExtrasReader(validExtras) }
        )
        assertThat(validResponse).isEqualTo(AapsCarbHistoryWireResponse(
            AapsCarbHistoryContract.RESPONSE_ACTION,
            validExtras
        ))

        val malformedReaders = listOf<() -> AapsCarbHistoryWireResponse?>(
            {
                extractAapsCarbHistoryWireResponse(
                    action = { AapsCarbHistoryContract.RESPONSE_ACTION },
                    extras = { throw IllegalStateException("extras") }
                )
            },
            {
                extractAapsCarbHistoryWireResponse(
                    action = { AapsCarbHistoryContract.RESPONSE_ACTION },
                    extras = {
                        FakeExtrasReader(validExtras, keyFailure = IllegalStateException("keySet"))
                    }
                )
            },
            {
                extractAapsCarbHistoryWireResponse(
                    action = { AapsCarbHistoryContract.RESPONSE_ACTION },
                    extras = {
                        FakeExtrasReader(validExtras, getFailure = IllegalStateException("get"))
                    }
                )
            }
        )
        malformedReaders.forEach { extract ->
            assertThat(extract()).isNull()
        }

        val transport = FakeTransport {
            malformedReaders.first().invoke()?.let(::emit)
        }
        val result = async {
            source(transport, timeoutMs = 1_000L)
                .load(FROM_TS, THROUGH_TS, 0L, 0L, 50)
        }
        advanceUntilIdle()

        assertThat(result.await()).isEqualTo(AapsCarbHistoryResult.TransportTimeout)
        assertThat(transport.unregisterCount).isEqualTo(1)
    }

    @Test
    fun cancellationBeforeRegisterReturnsDisposesLateRegistrationWithoutSending() = runTest {
        val transport = BlockingRegisterTransport()
        val load = async(Dispatchers.Default) {
            AapsCarbHistorySource(
                transport = transport,
                timeoutMs = 60_000L,
                nonceFactory = { NONCE }
            ).load(FROM_TS, THROUGH_TS, 0L, 0L, 50)
        }
        transport.registerEntered.await()

        load.cancel(CancellationException("cancel while register is blocked"))
        assertThat(load.isCancelled).isTrue()
        transport.allowRegisterToReturn()
        load.join()

        assertThat(load.isCompleted).isTrue()
        assertThat(transport.unregisterCount.get()).isEqualTo(1)
        assertThat(transport.activeRegistrationCount.get()).isEqualTo(0)
        assertThat(transport.sendCount.get()).isEqualTo(0)
    }

    @Test
    fun lifecycleCancellationWinningBeforeSendClaimPreventsSendAndDisposesOnce() {
        val unregisterCount = AtomicInteger()
        val lifecycle = AapsCarbHistoryLifecycle { it.unregister() }

        assertThat(lifecycle.publish(AapsCarbHistoryRegistration {
            unregisterCount.incrementAndGet()
        })).isTrue()
        assertThat(lifecycle.finish()).isTrue()

        assertThat(lifecycle.tryStartSending()).isFalse()
        assertThat(lifecycle.finish()).isFalse()
        assertThat(unregisterCount.get()).isEqualTo(1)
    }

    @Test
    fun lifecycleSendClaimWinningAllowsSendAndCancellationDisposesOnce() {
        val unregisterCount = AtomicInteger()
        val lifecycle = AapsCarbHistoryLifecycle { it.unregister() }

        assertThat(lifecycle.publish(AapsCarbHistoryRegistration {
            unregisterCount.incrementAndGet()
        })).isTrue()
        assertThat(lifecycle.tryStartSending()).isTrue()

        assertThat(lifecycle.finish()).isTrue()
        assertThat(lifecycle.finish()).isFalse()
        assertThat(lifecycle.tryStartSending()).isFalse()
        assertThat(unregisterCount.get()).isEqualTo(1)
    }

    private suspend fun assertInvalidPayload(payload: String, limit: Int = 50) {
        assertThat(loadPayload(payload, limit)).isEqualTo(AapsCarbHistoryResult.Invalid)
    }

    private suspend fun loadPayload(
        payload: String,
        limit: Int = 50
    ): AapsCarbHistoryResult {
        val transport = FakeTransport { request ->
            emit(payloadResponse(request.nonce(), payload))
        }
        return source(transport).load(FROM_TS, THROUGH_TS, 0L, 0L, limit)
    }

    private fun source(
        transport: FakeTransport,
        timeoutMs: Long = 30_000L
    ): AapsCarbHistorySource = AapsCarbHistorySource(
        transport = transport,
        timeoutMs = timeoutMs,
        nonceFactory = { NONCE }
    )

    private fun payloadResponse(
        nonce: String,
        payload: String,
        revisionId: Long = 42L
    ) =
        AapsCarbHistoryWireResponse(
            action = AapsCarbHistoryContract.RESPONSE_ACTION,
            extras = mapOf(
                "nonce" to nonce,
                "payload" to payload,
                "revisionId" to revisionId
            )
        )

    private fun statusResponse(nonce: String, status: String) =
        AapsCarbHistoryWireResponse(
            action = AapsCarbHistoryContract.RESPONSE_ACTION,
            extras = mapOf("nonce" to nonce, "status" to status)
        )

    private fun validPayload(
        nonce: String = NONCE,
        fromTs: Long = FROM_TS,
        throughTs: Long = THROUGH_TS,
        generatedAt: Long = THROUGH_TS + 1_000L,
        rows: List<AapsCarbHistoryRow> = listOf(validRow()),
        nextTimestamp: Long = rows.lastOrNull()?.timestamp ?: 0L,
        nextId: Long = rows.lastOrNull()?.id ?: 0L,
        hasMore: Boolean = false
    ): String = buildString {
        append("{\"nonce\":\"").append(nonce)
        append("\",\"fromTs\":").append(fromTs)
        append(",\"throughTs\":").append(throughTs)
        append(",\"generatedAt\":").append(generatedAt)
        append(",\"rows\":[")
        rows.forEachIndexed { index, row ->
            if (index > 0) append(',')
            append(rowJson(row))
        }
        append("],\"nextTimestamp\":").append(nextTimestamp)
        append(",\"nextId\":").append(nextId)
        append(",\"hasMore\":").append(hasMore)
        append('}')
    }

    private fun rowJson(row: AapsCarbHistoryRow): String = buildString {
        append("{\"id\":").append(row.id)
        append(",\"version\":").append(row.version)
        append(",\"dateCreated\":").append(row.dateCreated)
        append(",\"isValid\":").append(row.isValid)
        append(",\"referenceId\":").append(row.referenceId ?: "null")
        append(",\"timestamp\":").append(row.timestamp)
        append(",\"duration\":").append(row.duration)
        append(",\"amount\":").append(row.amount)
        append(",\"notes\":").append(jsonString(row.notes))
        append(",\"notesSha256\":").append(jsonString(row.notesSha256))
        append(",\"notesTruncated\":").append(row.notesTruncated)
        append(",\"nightscoutId\":").append(jsonString(row.nightscoutId))
        append(",\"nightscoutIdSha256\":").append(jsonString(row.nightscoutIdSha256))
        append(",\"nightscoutIdTruncated\":").append(row.nightscoutIdTruncated)
        append('}')
    }

    private fun jsonString(value: String?): String =
        value?.let {
            buildString {
                append('"')
                it.forEach { char ->
                    when (char) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(char)
                    }
                }
                append('"')
            }
        } ?: "null"

    private fun validRow(
        id: Long = 7L,
        version: Int = 2,
        dateCreated: Long = ROW_TS - 1_000L,
        isValid: Boolean = true,
        referenceId: Long? = null,
        timestamp: Long = ROW_TS,
        duration: Long = 0L,
        amount: Double = 12.5,
        notes: String? = null,
        notesSha256: String? = null,
        notesTruncated: Boolean = false,
        nightscoutId: String? = null,
        nightscoutIdSha256: String? = null,
        nightscoutIdTruncated: Boolean = false
    ) = AapsCarbHistoryRow(
        id = id,
        version = version,
        dateCreated = dateCreated,
        isValid = isValid,
        referenceId = referenceId,
        timestamp = timestamp,
        duration = duration,
        amount = amount,
        notes = notes,
        notesSha256 = notesSha256,
        notesTruncated = notesTruncated,
        nightscoutId = nightscoutId,
        nightscoutIdSha256 = nightscoutIdSha256,
        nightscoutIdTruncated = nightscoutIdTruncated
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private class FakeTransport(
        private val registerFailure: Throwable? = null,
        private val sendFailure: Throwable? = null,
        private val onSend: FakeTransport.(AapsCarbHistoryWireRequest) -> Unit = {}
    ) : AapsCarbHistoryTransport {
        val events = mutableListOf<String>()
        val sent = mutableListOf<AapsCarbHistoryWireRequest>()
        var registeredAction: String? = null
        var registeredPermission: String? = null
        var unregisterCount = 0
        private var receiver: ((AapsCarbHistoryWireResponse) -> Unit)? = null

        override fun register(
            action: String,
            permission: String,
            receiver: (AapsCarbHistoryWireResponse) -> Unit
        ): AapsCarbHistoryRegistration {
            registerFailure?.let { throw it }
            events += "register"
            registeredAction = action
            registeredPermission = permission
            this.receiver = receiver
            return AapsCarbHistoryRegistration {
                events += "unregister"
                unregisterCount++
                this.receiver = null
            }
        }

        override fun send(request: AapsCarbHistoryWireRequest) {
            events += "send"
            sent += request
            sendFailure?.let { throw it }
            onSend(request)
        }

        fun emit(response: AapsCarbHistoryWireResponse) {
            receiver?.invoke(response)
        }
    }

    private class FakeExtrasReader(
        private val values: Map<String, Any?>,
        private val keyFailure: RuntimeException? = null,
        private val getFailure: RuntimeException? = null
    ) : AapsCarbHistoryExtrasReader {
        override fun keySet(): Set<String> {
            keyFailure?.let { throw it }
            return values.keys
        }

        override fun get(key: String): Any? {
            getFailure?.let { throw it }
            return values[key]
        }
    }

    private class BlockingRegisterTransport : AapsCarbHistoryTransport {
        val registerEntered = CompletableDeferred<Unit>()
        val unregisterCount = AtomicInteger()
        val activeRegistrationCount = AtomicInteger()
        val sendCount = AtomicInteger()
        private val registerRelease = CountDownLatch(1)

        override fun register(
            action: String,
            permission: String,
            receiver: (AapsCarbHistoryWireResponse) -> Unit
        ): AapsCarbHistoryRegistration {
            registerEntered.complete(Unit)
            check(registerRelease.await(5, TimeUnit.SECONDS)) {
                "Timed out waiting for register release"
            }
            activeRegistrationCount.incrementAndGet()
            return AapsCarbHistoryRegistration {
                unregisterCount.incrementAndGet()
                activeRegistrationCount.decrementAndGet()
            }
        }

        override fun send(request: AapsCarbHistoryWireRequest) {
            sendCount.incrementAndGet()
        }

        fun allowRegisterToReturn() {
            registerRelease.countDown()
        }
    }

    private companion object {
        const val NONCE = "12345678-1234-1234-1234-123456789012"
        const val FROM_TS = 1_800_000_000_000L
        const val THROUGH_TS = FROM_TS + 24L * 60L * 60L * 1_000L
        const val ROW_TS = FROM_TS + 60_000L
        const val TEN_MINUTES_MS = 10L * 60L * 1_000L
        const val TEN_HOURS_MS = 10L * 60L * 60L * 1_000L
    }
}
