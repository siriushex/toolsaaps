package io.aaps.copilot.report

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test

class ClinicalReportArchitectureTest {

    @Test
    fun aiReportAndExportSourcesDoNotDependOnTherapyWriters() {
        val violations = advisoryProductionSources().flatMap { source ->
            val forbidden = if (
                source.relativePath.endsWith("ClinicalReportDatasetBuilder.kt")
            ) {
                (FORBIDDEN_WRITER_SYMBOLS + FORBIDDEN_WRITER_METHODS).filterNot {
                    it == "GlucoseCalibrationRepository" ||
                        it == "glucoseCalibrationRepository"
                }
            } else {
                FORBIDDEN_WRITER_SYMBOLS + FORBIDDEN_WRITER_METHODS
            }
            forbidden
                .filter(source.text::contains)
                .map { symbol -> "${source.relativePath}: $symbol" }
        }

        assertThat(violations).isEmpty()
    }

    @Test
    fun datasetBuilderUsesCalibrationOnlyThroughReadOnlyResolution() {
        val source = source(
            "io/aaps/copilot/data/repository/ClinicalReportDatasetBuilder.kt"
        )

        assertThat(source).contains("GlucoseCalibrationRepository")
        assertThat(source).contains("glucoseCalibrationRepository::resolveGlucosePoints")
        assertThat(source).doesNotContain("addManualBloodGlucoseCheck(")
        assertThat(source).doesNotContain("resetManualCalibration(")
        assertThat(source).doesNotContain("refreshCalibrationModel(")
        assertThat(source).doesNotContain("persistCalibration")

        advisoryProductionSources()
            .filterNot {
                it.relativePath.endsWith("ClinicalReportDatasetBuilder.kt")
            }
            .forEach { production ->
                assertThat(production.text).doesNotContain("GlucoseCalibrationRepository")
            }
    }

    @Test
    fun reportExportPackageCannotAcceptRawDatasetOrPersistPdf() {
        val reportSources = productionSources()
            .filter { it.relativePath.startsWith("io/aaps/copilot/report/") }
        val forbiddenPersistenceReferences = listOf(
            Regex("""\bClinicalReportPayload\b"""),
            Regex("""\bClinicalReportDataset\b"""),
            Regex("""\bCopilotDatabase\b"""),
            Regex("""\bClinicalReportDao\b"""),
            Regex("""\bClinicalReportEntity\b"""),
            Regex("""\bandroidx\.room\b"""),
            Regex("""\bRoom\.databaseBuilder\b"""),
            Regex("""\bjava\.io\.File\b"""),
            Regex("""\bFileOutputStream\b"""),
            Regex("""\bopenFileOutput\b"""),
            Regex("""\bfilesDir\b"""),
            Regex("""\bcacheDir\b"""),
            Regex("""\bgetExternalFilesDir\b""")
        )

        val violations = reportSources.flatMap { source ->
            forbiddenPersistenceReferences
                .filter { reference -> reference.containsMatchIn(source.text) }
                .map { reference -> "${source.relativePath}: ${reference.pattern}" }
        }

        assertThat(reportSources.map(SourceFile::relativePath)).containsAtLeast(
            "io/aaps/copilot/report/ClinicalReportDocument.kt",
            "io/aaps/copilot/report/ClinicalReportExportRepository.kt",
            "io/aaps/copilot/report/ClinicalReportPdfRenderer.kt"
        )
        assertThat(violations).isEmpty()
        assertThat(source("io/aaps/copilot/report/ClinicalReportDocument.kt"))
            .contains("class ClinicalReportDocument private constructor(")
    }

    @Test
    fun productionRendererUsesNarrowSourceAndNeverMaterializesWholeReportLayout() {
        val renderer = source("io/aaps/copilot/report/ClinicalReportPdfRenderer.kt")
        val legacyRender = extractKotlinDeclaration(renderer, "render")
        val sourceRender = extractKotlinDeclaration(renderer, "renderSource")

        assertThat(renderer).contains("ClinicalPdfContentSource")
        assertThat(legacyRender).contains("ClinicalReportDocumentContentSource")
        assertThat(legacyRender).doesNotContain("layout(")
        assertThat(sourceRender).doesNotContain("ClinicalReportPdfLayout")
        assertThat(sourceRender).doesNotContain(".pages")
        assertThat(sourceRender).doesNotContain("List<ClinicalPdfLayoutPage>")
        assertThat(renderer).doesNotContain("fun layout(")
        assertThat(renderer).doesNotContain("ClinicalReportPdfLayout")
        assertThat(renderer).doesNotContain("ClinicalPdfLayoutPage")
    }

    @Test
    fun streamingPaginatorTransfersTheOnlyPopulatedPageBufferToItsCallback() {
        val renderer = source("io/aaps/copilot/report/ClinicalReportPdfRenderer.kt")
        val paginator = renderer
            .substringAfter("private inner class ClinicalPdfStreamingPaginator")
            .substringBefore("private companion object")

        assertThat(paginator).contains("var pageLines = mutableListOf<ClinicalPdfLayoutLine>()")
        assertThat(paginator).contains("val completedPage = pageLines")
        assertThat(paginator).contains("pageLines = mutableListOf()")
        assertThat(paginator).contains("onPage(pageNumber, completedPage)")
        assertThat(paginator).doesNotContain("pageLines.toList()")
    }

    @Test
    fun rawClinicalPayloadCannotEnterRoomOrFilePersistence() {
        val persistenceSources = productionSources().filter { source ->
            source.relativePath.startsWith("io/aaps/copilot/data/local/") ||
                source.relativePath.endsWith("ClinicalReportExportRepository.kt")
        }
        val forbidden = listOf(
            "ClinicalReportPayload",
            "ClinicalReportDataset",
            "compactJson",
            "ClinicalPdfContentSource"
        )

        val violations = persistenceSources.flatMap { source ->
            forbidden.filter(source.text::contains).map { symbol ->
                "${source.relativePath}: $symbol"
            }
        }

        assertThat(violations).isEmpty()
    }

    @Test
    fun gatewaysUseValidatedDomainReportsAndCannotPersistOrMapPdf() {
        val gateways = gatewaySources()
        val forbiddenDownstreamTypes = listOf(
            "ClinicalReportDao",
            "ClinicalReportEntity",
            "ClinicalReportDocument",
            "ClinicalReportPdfRenderer",
            "ClinicalReportExportRepository"
        )
        val violations = gateways.flatMap { source ->
            forbiddenDownstreamTypes
                .filter(source.text::contains)
                .map { reference -> "${source.relativePath}: $reference" }
        }
        val client = gateways.single {
            it.relativePath.endsWith("ClinicalOpenAiClient.kt")
        }.text

        assertThat(violations).isEmpty()
        assertThat(client).contains("ClinicalReportSchemaParser.parse(it)")
        assertThat(client).contains("ClinicalChunkReportSchemaParser.parse(it)")
        assertThat(source("io/aaps/copilot/report/ClinicalReportDocument.kt"))
            .contains("complete: ClinicalOpenAiResult?")
    }

    @Test
    fun mainViewModelClinicalReportAndPdfHandlersCannotReachTherapyWriters() {
        val source = source("io/aaps/copilot/ui/MainViewModel.kt")
        val handlers = listOf(
            "prepareClinicalReport",
            "retryLocalClinicalReportPreparation",
            "beginClinicalReportPdfExport",
            "claimClinicalReportPdfExportTicket",
            "resolveClinicalReportPdfExport",
            "shareClinicalReportPdf",
            "sendClinicalReport",
            "cancelClinicalReport",
            "requestUnknownClinicalReportRetryConfirmation",
            "dismissUnknownClinicalReportRetryConfirmation",
            "retryUnknownClinicalReport"
        ).associateWith { name ->
            extractKotlinDeclaration(source, name)
        }

        assertNoWriterReferences(handlers)
        assertThat(handlers.getValue("resolveClinicalReportPdfExport"))
            .contains("clinicalReportPdfExportCoordinator.resolvePicker(")
        assertThat(handlers.getValue("sendClinicalReport"))
            .contains("clinicalReportCommands.send(")
        assertThat(handlers.getValue("cancelClinicalReport"))
            .contains("clinicalReportCommands.cancel()")
    }

    @Test
    fun appContainerClinicalWiringCannotInjectTherapyWriters() {
        val source = source("io/aaps/copilot/service/AppContainer.kt")
        val wiring = listOf(
            "clinicalAiCredentialSource",
            "clinicalAiGatewayFactory",
            "clinicalReportDatasetBuilder",
            "clinicalReportRepository",
            "clinicalReportPdfRenderer",
            "clinicalPdfStorageRepository",
            "clinicalPdfShareRepository"
        ).associateWith { name ->
            extractKotlinDeclaration(source, name)
        }
        val datasetBuilder = wiring.getValue("clinicalReportDatasetBuilder")

        assertNoWriterReferences(
            wiring.filterKeys { it != "clinicalReportDatasetBuilder" }
        )
        assertNoWriterReferences(
            mapOf("clinicalReportDatasetBuilder" to datasetBuilder),
            allowedReferences = setOf("glucoseCalibrationRepository")
        )
        assertThat(datasetBuilder).contains("ClinicalReportDatasetBuilder(")
        assertThat(datasetBuilder).contains("glucoseCalibrationRepository")
        assertThat(datasetBuilder).contains("ClinicalTargetManagerEvidenceSource.load(")
        assertThat(datasetBuilder).contains("::clinicalEvidenceBetween")
        assertThat(datasetBuilder).doesNotContain("targetManagerDao().between(")
        assertThat(datasetBuilder).doesNotContain("contextEventSyncCoordinator")
        assertThat(wiring.getValue("clinicalReportRepository"))
            .contains("gatewayFactory = clinicalAiGatewayFactory")
        assertThat(wiring.getValue("clinicalReportPdfRenderer"))
            .contains("ClinicalReportPdfRenderer()")
        assertThat(wiring.getValue("clinicalPdfStorageRepository"))
            .contains("ClinicalPdfStorageRepository(")
        assertThat(wiring.getValue("clinicalPdfShareRepository"))
            .contains("ClinicalPdfShareRepository(appContext)")
    }

    @Test
    fun aiReportConstructorsCannotReceiveTherapyWriterDependencies() {
        val classes = mapOf(
            "io/aaps/copilot/data/repository/ClinicalOpenAiClient.kt" to
                "ClinicalOpenAiClient",
            "io/aaps/copilot/data/repository/AnthropicClinicalAiGateway.kt" to
                "AnthropicClinicalAiGateway",
            "io/aaps/copilot/data/repository/GeminiClinicalAiGateway.kt" to
                "GeminiClinicalAiGateway",
            "io/aaps/copilot/data/repository/OpenAiCompatibleClinicalAiGateway.kt" to
                "OpenAiCompatibleClinicalAiGateway",
            "io/aaps/copilot/data/repository/ClinicalAiGatewayFactory.kt" to
                "ClinicalAiGatewayFactory",
            "io/aaps/copilot/data/repository/ClinicalReportRepository.kt" to
                "ClinicalReportRepository",
            "io/aaps/copilot/report/ClinicalReportDocument.kt" to
                "ClinicalReportDocument",
            "io/aaps/copilot/report/ClinicalReportPdfRenderer.kt" to
                "ClinicalReportPdfRenderer",
            "io/aaps/copilot/storage/ClinicalPdfStorageRepository.kt" to
                "ClinicalPdfStorageRepository",
            "io/aaps/copilot/storage/ClinicalPdfShareRepository.kt" to
                "ClinicalPdfShareRepository"
        )
        val constructors = classes.mapValues { (path, className) ->
            extractKotlinConstructorDeclarations(source(path), className)
                .joinToString("\n")
        }

        assertNoWriterReferences(constructors)

        val datasetConstructors = extractKotlinConstructorDeclarations(
            source("io/aaps/copilot/data/repository/ClinicalReportDatasetBuilder.kt"),
            "ClinicalReportDatasetBuilder"
        )
        assertNoWriterReferences(
            mapOf("ClinicalReportDatasetBuilder" to datasetConstructors.joinToString("\n")),
            allowedReferences = setOf(
                "GlucoseCalibrationRepository",
                "glucoseCalibrationRepository"
            )
        )
        assertThat(datasetConstructors.joinToString("\n"))
            .contains("glucoseCalibrationRepository::resolveGlucosePoints")
    }

    @Test
    fun kotlinDeclarationExtractorIgnoresLiteralsAndInspectsTemplateExpressions() {
        val synthetic = """
            class Sample {
                fun target() {
                    val regular = "{ not structure }"
                    val writerName = "runAutomationCycle("
                    val raw = ""${'"'}{ raw }""${'"'}
                    val char = '}'
                    // } line comment mentioning AutomationRepository
                    /* outer { /* nested } */ still comment } */
                    listOf(1).map { value -> "{ ${'$'}value }" }
                }

                fun writer() {
                    runAutomationCycle()
                }

                fun templateWriter() {
                    val result =
                        "status=${'$'}{container.actionRepository.submitCarbs(command)}"
                }

                fun identifierTemplateWriter() {
                    val result = "repo=${'$'}automationRepository"
                }

                fun templateHarmless() {
                    val result =
                        "status=${'$'}{buildString {
                            append("literal submitCarbs( { }")
                            // runAutomationCycle( }
                            /* submitCarbs( { /* runAutomationCycle( } */ } */
                            listOf(command).forEach { item ->
                                append("item=${'$'}item")
                            }
                        }}"
                }

                private val targetProperty =
                    factory(
                        block = { "{ property }" }
                    )

                private val writerProperty = runAutomationCycle()
            }
        """.trimIndent()

        val function = extractKotlinDeclaration(synthetic, "target")
        val templateWriter = extractKotlinDeclaration(synthetic, "templateWriter")
        val identifierTemplateWriter =
            extractKotlinDeclaration(synthetic, "identifierTemplateWriter")
        val templateHarmless = extractKotlinDeclaration(synthetic, "templateHarmless")
        val property = extractKotlinDeclaration(synthetic, "targetProperty")

        assertThat(function).contains("listOf(1).map")
        assertThat(function).contains("\"{ ${'$'}value }\"")
        assertThat(function).contains("\"runAutomationCycle(\"")
        assertThat(function).doesNotContain("fun writer")
        assertThat(templateWriter)
            .contains("${'$'}{container.actionRepository.submitCarbs(command)}")
        assertThat(templateWriter).doesNotContain("targetProperty")
        assertThat(identifierTemplateWriter).contains("${'$'}automationRepository")
        assertThat(identifierTemplateWriter).doesNotContain("templateHarmless")
        assertThat(templateHarmless).contains("listOf(command).forEach")
        assertThat(templateHarmless).doesNotContain("templateWriter")
        assertThat(templateHarmless).doesNotContain("targetProperty")
        assertThat(property).contains("block =")
        assertThat(property).doesNotContain("writerProperty")
        assertThat(property).doesNotContain("runAutomationCycle")
        assertNoWriterReferences(mapOf("target" to function))
        assertNoWriterReferences(mapOf("templateHarmless" to templateHarmless))
        assertThrows(AssertionError::class.java) {
            assertNoWriterReferences(
                mapOf("writer" to extractKotlinDeclaration(synthetic, "writer"))
            )
        }
        assertThrows(AssertionError::class.java) {
            assertNoWriterReferences(mapOf("templateWriter" to templateWriter))
        }
        assertThrows(AssertionError::class.java) {
            assertNoWriterReferences(
                mapOf("identifierTemplateWriter" to identifierTemplateWriter)
            )
        }
    }

    private fun advisoryProductionSources(): List<SourceFile> {
        return productionSources().filter { source ->
            val name = source.relativePath.substringAfterLast('/')
            name.startsWith("ClinicalReport") ||
                name == "ClinicalOpenAiClient.kt" ||
                name.contains("ClinicalAiGateway") ||
                name == "ClinicalAiProviderConfig.kt" ||
                name.startsWith("ClinicalAiCredential") ||
                name == "ClinicalSummaryCalculator.kt" ||
                name == "BoundedClinicalPayloadParser.kt" ||
                source.relativePath.startsWith("io/aaps/copilot/report/")
        }
            .sortedBy(SourceFile::relativePath)
    }

    private fun gatewaySources(): List<SourceFile> = advisoryProductionSources()
        .filter {
            it.relativePath.endsWith("ClinicalOpenAiClient.kt") ||
                it.relativePath.substringAfterLast('/').contains("ClinicalAiGateway")
        }

    private fun source(relativePath: String): String =
        productionSources().single { it.relativePath == relativePath }.text

    private fun assertNoWriterReferences(
        blocks: Map<String, String>,
        allowedReferences: Set<String> = emptySet()
    ) {
        val forbidden = (FORBIDDEN_WRITER_SYMBOLS + FORBIDDEN_WRITER_METHODS)
            .filterNot(allowedReferences::contains)
        val violations = blocks.flatMap { (name, block) ->
            val mask = kotlinCodeMask(block)
            val codeOnly = block.mapIndexed { index, character ->
                if (mask[index]) character else ' '
            }.joinToString("")
            forbidden.filter(codeOnly::contains).map { reference -> "$name: $reference" }
        }
        assertThat(violations).isEmpty()
    }

    private fun extractKotlinDeclaration(source: String, name: String): String {
        val declarations = Regex(
            """(?m)^[ \t]*(?:(?:public|private|protected|internal|open|final|""" +
                """override|suspend|inline|tailrec|operator|infix|external|lateinit)""" +
                """[ \t]+)*(?:fun|val|var)[ \t]+${Regex.escape(name)}\b"""
        ).findAll(source).toList()
        require(declarations.size == 1) {
            "Expected one Kotlin declaration named $name, found ${declarations.size}"
        }
        return extractDeclarationAt(source, declarations.single().range.first)
    }

    private fun extractKotlinConstructorDeclarations(
        source: String,
        className: String
    ): List<String> {
        val classMatch = Regex(
            """\bclass[ \t]+${Regex.escape(className)}\b"""
        ).find(source) ?: error("Kotlin class not found: $className")
        val mask = kotlinCodeMask(source)
        val classBodyOpen = findTopLevelBodyOpen(
            source = source,
            mask = mask,
            from = classMatch.range.last + 1
        )
        val classBodyClose = matchingDelimiter(source, mask, classBodyOpen, '{', '}')
        val declarations = mutableListOf(
            source.substring(classMatch.range.first, classBodyOpen).trimEnd()
        )
        Regex("""\bconstructor[ \t]*\(""")
            .findAll(source, classBodyOpen + 1)
            .takeWhile { it.range.first < classBodyClose }
            .filter { mask[it.range.first] }
            .forEach { constructor ->
                declarations += extractConstructorAt(
                    source = source,
                    mask = mask,
                    start = constructor.range.first,
                    classBodyClose = classBodyClose
                )
            }
        return declarations
    }

    private fun extractDeclarationAt(source: String, start: Int): String {
        val mask = kotlinCodeMask(source)
        var parentheses = 0
        var brackets = 0
        for (index in start until source.length) {
            if (!mask[index]) continue
            when (source[index]) {
                '(' -> parentheses += 1
                ')' -> parentheses -= 1
                '[' -> brackets += 1
                ']' -> brackets -= 1
                '{' -> if (parentheses == 0 && brackets == 0) {
                    val end = matchingDelimiter(source, mask, index, '{', '}')
                    return source.substring(start, end + 1)
                }
                '=' -> if (parentheses == 0 && brackets == 0) {
                    val end = expressionEnd(source, mask, start, index + 1)
                    return source.substring(start, end).trimEnd()
                }
            }
            require(parentheses >= 0 && brackets >= 0) {
                "Unbalanced Kotlin declaration"
            }
        }
        error("Kotlin declaration body not found")
    }

    private fun findTopLevelBodyOpen(
        source: String,
        mask: BooleanArray,
        from: Int
    ): Int {
        var parentheses = 0
        var brackets = 0
        for (index in from until source.length) {
            if (!mask[index]) continue
            when (source[index]) {
                '(' -> parentheses += 1
                ')' -> parentheses -= 1
                '[' -> brackets += 1
                ']' -> brackets -= 1
                '{' -> if (parentheses == 0 && brackets == 0) return index
            }
        }
        error("Kotlin class body not found")
    }

    private fun extractConstructorAt(
        source: String,
        mask: BooleanArray,
        start: Int,
        classBodyClose: Int
    ): String {
        var parentheses = 0
        var brackets = 0
        for (index in start until classBodyClose) {
            if (!mask[index]) continue
            when (source[index]) {
                '(' -> parentheses += 1
                ')' -> parentheses -= 1
                '[' -> brackets += 1
                ']' -> brackets -= 1
                '{' -> if (parentheses == 0 && brackets == 0) {
                    return source.substring(start, index).trimEnd()
                }
                '\n' -> if (
                    parentheses == 0 &&
                    brackets == 0 &&
                    expressionTerminatesAtNewline(source, index, lineIndent(source, start))
                ) {
                    return source.substring(start, index).trimEnd()
                }
            }
        }
        error("Kotlin constructor boundary not found")
    }

    private fun expressionEnd(
        source: String,
        mask: BooleanArray,
        declarationStart: Int,
        expressionStart: Int
    ): Int {
        var parentheses = 0
        var brackets = 0
        var braces = 0
        val declarationIndent = lineIndent(source, declarationStart)
        for (index in expressionStart until source.length) {
            if (!mask[index]) continue
            when (source[index]) {
                '(' -> parentheses += 1
                ')' -> parentheses -= 1
                '[' -> brackets += 1
                ']' -> brackets -= 1
                '{' -> braces += 1
                '}' -> {
                    if (braces == 0) return index
                    braces -= 1
                }
                '\n' -> if (
                    parentheses == 0 &&
                    brackets == 0 &&
                    braces == 0 &&
                    expressionTerminatesAtNewline(source, index, declarationIndent)
                ) {
                    return index
                }
            }
            require(parentheses >= 0 && brackets >= 0 && braces >= 0) {
                "Unbalanced Kotlin expression"
            }
        }
        return source.length
    }

    private fun expressionTerminatesAtNewline(
        source: String,
        newline: Int,
        declarationIndent: Int
    ): Boolean {
        var cursor = newline + 1
        while (cursor < source.length) {
            val lineEnd = source.indexOf('\n', cursor).let {
                if (it == -1) source.length else it
            }
            val line = source.substring(cursor, lineEnd)
            if (line.isNotBlank()) {
                val indent = line.indexOfFirst { !it.isWhitespace() }
                    .let { if (it == -1) Int.MAX_VALUE else it }
                val trimmed = line.trimStart()
                return indent <= declarationIndent &&
                    !trimmed.startsWith(".") &&
                    !trimmed.startsWith("?:") &&
                    !trimmed.startsWith("&&") &&
                    !trimmed.startsWith("||")
            }
            cursor = lineEnd + 1
        }
        return true
    }

    private fun matchingDelimiter(
        source: String,
        mask: BooleanArray,
        openIndex: Int,
        open: Char,
        close: Char
    ): Int {
        var depth = 0
        for (index in openIndex until source.length) {
            if (!mask[index]) continue
            when (source[index]) {
                open -> depth += 1
                close -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        error("Unclosed Kotlin delimiter at $openIndex")
    }

    private fun lineIndent(source: String, index: Int): Int {
        val lineStart = source.lastIndexOf('\n', index - 1).let {
            if (it == -1) 0 else it + 1
        }
        var cursor = lineStart
        while (cursor < source.length && source[cursor].isWhitespace() && source[cursor] != '\n') {
            cursor += 1
        }
        return cursor - lineStart
    }

    private fun kotlinCodeMask(source: String): BooleanArray =
        KotlinCodeMaskLexer(source).scan()

    private class KotlinCodeMaskLexer(
        private val source: String
    ) {
        private val mask = BooleanArray(source.length)
        private var index = 0

        fun scan(): BooleanArray {
            scanCode()
            check(index == source.length)
            return mask
        }

        private fun scanCode(templateBraceDepth: Int? = null) {
            var braces = templateBraceDepth
            while (index < source.length) {
                val current = source[index]
                val next = source.getOrNull(index + 1)
                when {
                    current == '/' && next == '/' -> scanLineComment()
                    current == '/' && next == '*' -> scanBlockComment()
                    source.startsWith("\"\"\"", index) -> scanString(raw = true)
                    current == '"' -> scanString(raw = false)
                    current == '\'' -> scanChar()
                    else -> {
                        mask[index] = true
                        when {
                            braces != null && current == '{' -> braces += 1
                            braces != null && current == '}' -> {
                                braces -= 1
                                index += 1
                                if (braces == 0) return
                                continue
                            }
                        }
                        index += 1
                    }
                }
            }
            require(braces == null) {
                "Unterminated Kotlin template expression"
            }
        }

        private fun scanString(raw: Boolean) {
            val delimiterLength = if (raw) 3 else 1
            index += delimiterLength
            while (index < source.length) {
                when {
                    raw && source.startsWith("\"\"\"", index) -> {
                        index += delimiterLength
                        return
                    }
                    !raw && source[index] == '"' -> {
                        index += delimiterLength
                        return
                    }
                    !raw && source[index] == '\\' -> {
                        index = (index + 2).coerceAtMost(source.length)
                    }
                    source[index] == '$' -> scanTemplate()
                    else -> index += 1
                }
            }
            error("Unterminated Kotlin string")
        }

        private fun scanTemplate() {
            val next = source.getOrNull(index + 1)
            when {
                next == '{' -> {
                    mask[index] = true
                    mask[index + 1] = true
                    index += 2
                    scanCode(templateBraceDepth = 1)
                }
                next != null && Character.isJavaIdentifierStart(next) -> {
                    mask[index] = true
                    index += 1
                    while (
                        index < source.length &&
                        Character.isJavaIdentifierPart(source[index])
                    ) {
                        mask[index] = true
                        index += 1
                    }
                }
                else -> index += 1
            }
        }

        private fun scanLineComment() {
            index += 2
            while (index < source.length && source[index] != '\n') {
                index += 1
            }
            if (index < source.length) {
                mask[index] = true
                index += 1
            }
        }

        private fun scanBlockComment() {
            var depth = 1
            index += 2
            while (index < source.length && depth > 0) {
                when {
                    source.startsWith("/*", index) -> {
                        depth += 1
                        index += 2
                    }
                    source.startsWith("*/", index) -> {
                        depth -= 1
                        index += 2
                    }
                    else -> {
                        if (source[index] == '\n') mask[index] = true
                        index += 1
                    }
                }
            }
            require(depth == 0) {
                "Unterminated Kotlin block comment"
            }
        }

        private fun scanChar() {
            index += 1
            while (index < source.length) {
                when (source[index]) {
                    '\\' -> index = (index + 2).coerceAtMost(source.length)
                    '\'' -> {
                        index += 1
                        return
                    }
                    else -> index += 1
                }
            }
            error("Unterminated Kotlin character literal")
        }
    }

    private fun productionSources(): List<SourceFile> {
        val root = File("src/main/kotlin").takeIf(File::exists)
            ?: File("app/src/main/kotlin")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file ->
                SourceFile(
                    relativePath = file.relativeTo(root).invariantSeparatorsPath,
                    text = file.readText()
                )
            }
            .toList()
    }

    private data class SourceFile(
        val relativePath: String,
        val text: String
    )

    private companion object {
        val FORBIDDEN_WRITER_SYMBOLS = listOf(
            "AutomationRepository",
            "automationRepository",
            "TargetManagerRepository",
            "targetManagerRepository",
            "GlucoseCalibrationRepository",
            "glucoseCalibrationRepository",
            "NightscoutActionRepository",
            "nightscoutActionRepository",
            "ActionCommandDao",
            "actionCommandDao",
            "TherapyAction",
            "UamExportCoordinator",
            "ActionCommandUamExportReservationStore",
            "UamExportReservationStore",
            "AapsCarbGateway",
            "TargetCommandDispatcher",
            "TherapyActionTransportGate",
            "ActionCommandEntity",
            "ActionCommand",
            "ActionProposal",
            "TargetCommandCandidate",
            "NightscoutApi",
            "NightscoutTreatmentRequest",
            "io.aaps.copilot.data.remote.nightscout"
        )
        val FORBIDDEN_WRITER_METHODS = listOf(
            "submitTempTarget(",
            "submitOrRetryTempTarget(",
            "submitCarbs(",
            "postUamCarbEntryStatic(",
            "postTreatment(",
            "evaluateAndDispatch(",
            "runAutomationCycle(",
            "addManualBloodGlucoseCheck(",
            "resetManualCalibration("
        )
    }
}
