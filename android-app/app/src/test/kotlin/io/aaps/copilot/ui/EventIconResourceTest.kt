package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.aaps.copilot.domain.events.CompensationEventType
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class EventIconResourceTest {
    @Test
    fun everyCompensationEventTypeHasExactlyOneCompactIcon() {
        assertThat(EventIconResources.byType.keys).containsExactlyElementsIn(CompensationEventType.entries)

        val ids = EventIconResources.byType.values.map { it.resourceId }
        assertThat(ids).containsNoDuplicates()

        EventIconResources.byType.values.forEach { icon ->
            assertThat(icon.resourceId).isGreaterThan(0)
            assertThat(icon.domain).isNotEmpty()
            assertThat(icon.source).isEqualTo(EventIconSource.GENERATED_WEBP)
        }
    }

    @Test
    fun generatedResourcesAreLossless128WebpWith512AlphaMasters() {
        EventIconResources.byType.values.forEach { icon ->
            val webp = File("src/main/res/drawable-nodpi/${icon.resourceName}.webp")
            assertWithMessage(icon.resourceName).that(webp.exists()).isTrue()
            assertWithMessage(icon.resourceName).that(webp.length()).isLessThan(40_000L)
            val header = webp.readBytes()
            assertThat(header.copyOfRange(0, 4).decodeToString()).isEqualTo("RIFF")
            assertThat(header.copyOfRange(8, 16).decodeToString()).isEqualTo("WEBPVP8L")
            val webpHeader = losslessWebpHeader(webp)
            assertThat(webpHeader.dimensions).isEqualTo(128 to 128)
            assertWithMessage(icon.resourceName).that(webpHeader.usesAlpha).isTrue()

            val master = File("../../design/event-icons/masters/${icon.resourceName}.png")
            val png = pngHeader(master)
            assertWithMessage(icon.resourceName).that(png.first).isEqualTo(512 to 512)
            assertWithMessage(icon.resourceName).that(png.second).isAnyOf(4, 6)
        }
        assertThat(File("../../artifacts/event-icons/event-icons-contact-sheet.png").exists()).isTrue()
    }

    @Test
    fun generatedResourcesHaveNoFallbackAndMatchPortableChecksumManifest() {
        val expectedNames = EventIconResources.byType.values.map { it.resourceName }.toSet()
        val eventResources = File("src").walkTopDown()
            .filter { it.isFile && it.nameWithoutExtension.startsWith("event_") }
            .filter { it.parentFile.name.startsWith("drawable") }
            .toList()
        assertThat(eventResources.map(File::nameWithoutExtension).toSet())
            .containsExactlyElementsIn(expectedNames)
        assertThat(eventResources.map(File::extension).toSet()).containsExactly("webp")
        assertThat(eventResources.groupingBy(File::nameWithoutExtension).eachCount().values)
            .containsExactlyElementsIn(List(expectedNames.size) { 1 })

        val repositoryRoot = File("../..").canonicalFile
        val checksumFile = File(repositoryRoot, "artifacts/event-icons/SHA256SUMS")
        val entries = checksumFile.readLines().filter(String::isNotBlank).associate { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            parts[1].removePrefix("*") to parts[0]
        }
        val expectedPaths = expectedNames.flatMap { name ->
            listOf(
                "design/event-icons/masters/$name.png",
                "android-app/app/src/main/res/drawable-nodpi/$name.webp"
            )
        }.toSet() + "artifacts/event-icons/event-icons-contact-sheet.png"
        assertThat(entries.keys).containsExactlyElementsIn(expectedPaths)
        entries.forEach { (path, expectedHash) ->
            assertWithMessage(path).that(sha256(File(repositoryRoot, path))).isEqualTo(expectedHash)
        }
    }

    @Test
    fun eventIconsRemainUntintedAtEveryEventsUiCallSite() {
        listOf(
            File("src/main/kotlin/io/aaps/copilot/ui/foundation/screens/EventsScreen.kt"),
            File("src/main/kotlin/io/aaps/copilot/ui/foundation/components/EventTimelineRail.kt")
        ).forEach { source ->
            val text = source.readText()
            val iconUses = Regex("painter\\s*=\\s*painterResource\\(EventIconResources").findAll(text).count()
            val untintedIcons = Regex("tint\\s*=\\s*Color\\.Unspecified").findAll(text).count()
            assertThat(iconUses).isGreaterThan(0)
            assertThat(untintedIcons).isAtLeast(iconUses)
        }
    }

    @Test
    fun eventsUiHasCompleteEnglishAndRussianAccessibleStrings() {
        val required = listOf(
            "events_title",
            "events_filter_all",
            "events_filter_manual",
            "events_filter_aaps",
            "events_filter_auto",
            "events_active_section",
            "events_recent_24h_section",
            "events_show_on_graph",
            "events_marker_description",
            "events_marker_more_description",
            "events_notebook_description",
            "events_active_badge_description",
            "events_action_context_description",
            "events_action_meal_description",
            "events_action_activity_description",
            "events_action_blood_check_description",
            "events_action_diagnostic_description",
            "events_chart_description",
            "events_source_manual",
            "events_source_aaps",
            "events_source_automatic",
            "events_type_meal",
            "events_type_activity",
            "events_type_stress",
            "events_type_illness",
            "events_type_sleep",
            "events_type_hormonal",
            "events_type_medication",
            "events_type_alcohol",
            "events_type_sensor",
            "events_type_infusion",
            "events_type_custom",
            "events_type_cycle",
            "events_cycle_phase_menstruation",
            "events_cycle_phase_follicular",
            "events_cycle_phase_ovulation",
            "events_cycle_phase_luteal"
        )
        val english = File("src/main/res/values/strings.xml").readText()
        val russian = File("src/main/res/values-ru/strings.xml").readText()

        required.forEach { name ->
            assertThat(english).contains("name=\"$name\"")
            assertThat(russian).contains("name=\"$name\"")
        }
    }

    @Test
    fun notebookActionUsesLocalizedResourceInsteadOfHardCodedEnglish() {
        val root = File("src/main/kotlin/io/aaps/copilot/ui/foundation/CopilotFoundationRoot.kt").readText()
        assertThat(root).doesNotContain("contentDescription = \"Events\"")
        assertThat(root).contains("R.string.events_notebook_description")
    }
    private fun losslessWebpHeader(file: File): LosslessWebpHeader {
        val bytes = file.readBytes()
        assertThat(bytes[20].toInt() and 0xff).isEqualTo(0x2f)
        val packed = (bytes[21].toInt() and 0xff) or
            ((bytes[22].toInt() and 0xff) shl 8) or
            ((bytes[23].toInt() and 0xff) shl 16) or
            ((bytes[24].toInt() and 0xff) shl 24)
        return LosslessWebpHeader(
            dimensions = ((packed and 0x3fff) + 1) to (((packed shr 14) and 0x3fff) + 1),
            usesAlpha = ((packed ushr 28) and 1) == 1
        )
    }

    private fun pngHeader(file: File): Pair<Pair<Int, Int>, Int> {
        val bytes = file.readBytes()
        assertThat(bytes.copyOfRange(1, 4).decodeToString()).isEqualTo("PNG")
        assertThat(bytes.copyOfRange(12, 16).decodeToString()).isEqualTo("IHDR")
        fun int32(offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
        return (int32(16) to int32(20)) to (bytes[25].toInt() and 0xff)
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class LosslessWebpHeader(
        val dimensions: Pair<Int, Int>,
        val usesAlpha: Boolean
    )
}
