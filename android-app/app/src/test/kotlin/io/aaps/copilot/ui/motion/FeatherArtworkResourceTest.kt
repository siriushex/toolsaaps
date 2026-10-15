package io.aaps.copilot.ui.motion

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class FeatherArtworkResourceTest {
    @Test fun reviewedSourcesAndGeneratedNativeResourcesMatchPortableManifest() {
        val root=File("../..").canonicalFile
        val manifest=File(root,"design/owl01/SHA256SUMS")
        val lines=manifest.readLines().filter(String::isNotBlank)
        assertTrue(lines.size>=60)
        lines.forEach { line ->
            val pair=line.split(Regex("\\s+"),limit=2)
            assertEquals(2,pair.size)
            val file=File(root,pair[1]).canonicalFile
            assertTrue(file.path.startsWith(root.path+File.separator));assertTrue(file.isFile)
            val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt()and 255) }
            assertEquals(pair[1],pair[0],hash)
        }
        assertEquals(40,File(root,"android-app/app/src/main/res/drawable").listFiles()!!.count { it.name.startsWith("copilot_glyph_") })
    }
}
