package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UamTagCodecTest {

    @Test
    fun tagParseAndRecognition() {
        val note = "UAM_ENGINE|id=abc-123|seq=2|ver=1|mode=BOOST|"
        val parsed = parseUamTag(note)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.id).isEqualTo("abc-123")
        assertThat(parsed.seq).isEqualTo(2)
        assertThat(parsed.ver).isEqualTo(1)
        assertThat(parsed.mode).isEqualTo("BOOST")
    }
}
