package com.wirelessalien.zipxtract

import com.wirelessalien.zipxtract.nativeui.IncomingFileNames
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class IncomingFileNamesTest {
    private val name = "中文 空格+字面%20和%25 😀 #?.zip"

    @Test fun metadataIsPreferredAndNeverUrlDecoded() {
        assertEquals(name, IncomingFileNames.resolve(name, "wrong.zip"))
        assertEquals("literal+%20%25.zip", IncomingFileNames.resolve("literal+%20%25.zip", null))
    }

    @Test fun androidAlreadyDecodedLastSegmentIsNotDecodedAgain() {
        assertEquals(name, IncomingFileNames.resolve(null, name))
        assertEquals("+%20%25.zip", IncomingFileNames.resolve(null, "+%20%25.zip"))
        assertEquals("共享文件", IncomingFileNames.resolve(null, null))
    }

    @Test fun pathsControlsAndDotNamesCannotEscapeImportDirectory() {
        assertEquals(name, IncomingFileNames.resolve("../../folder\\$name", "wrong"))
        assertEquals("safe.zip", IncomingFileNames.resolve("..\\safe\u0000\r\n\t\u0085.zip", null))
        assertEquals("safe.zip", IncomingFileNames.resolve("\u202esafe\u2066.zip", null))
        for (unsafe in listOf(".", "..", "../..", "..\\..", "/", "\\", "\u0000\r\n", " ")) {
            assertEquals("fallback+%20.zip", IncomingFileNames.resolve(unsafe, "fallback+%20.zip"))
        }
    }

    @Test fun safeLeafRetainsMeaningfulSpacesAndPercentLiterals() {
        assertEquals(" 中文 +%20%25 😀 #? ", IncomingFileNames.resolve(" 中文 +%20%25 😀 #? ", null))
        assertEquals("%2e%2e%2fescape.zip", IncomingFileNames.resolve("%2e%2e%2fescape.zip", null))
    }

    @Test fun byteLimitDoesNotSplitEmoji() {
        val resolved = IncomingFileNames.resolve("😀".repeat(100), null)
        assertTrue(resolved.toByteArray(Charsets.UTF_8).size <= 240)
        assertEquals("😀".repeat(60), resolved)
        assertFalse(resolved.endsWith("\ud83d"))
    }

    @Test fun canonicalDestinationRemainsDirectChild() {
        val root = Files.createTempDirectory("zipdoctorc-import-name-").toFile()
        try {
            // JVM tests also run on Windows, where '?' is not a legal filesystem leaf.
            // Android provider tests below retain the complete #? case on the device filesystem.
            val portableName = name.replace("?", "")
            for (candidate in listOf(portableName, "../../$portableName", "..\\$portableName", "..", "%2f..%2fescape.zip")) {
                val output = IncomingFileNames.destination(root, candidate, "safe.zip")
                assertEquals(root.canonicalFile, output.parentFile)
                assertFalse(output.name.contains('/'))
                assertFalse(output.name.contains('\\'))
            }
            assertEquals(portableName, IncomingFileNames.destination(root, portableName, null).name)
            assertFalse(File(root.parentFile, "escape.zip").exists())
        } finally {
            check(root.canonicalFile.name.startsWith("zipdoctorc-import-name-"))
            root.deleteRecursively()
        }
    }
}
