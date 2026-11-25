package com.wirelessalien.zipxtract

import com.wirelessalien.zipxtract.helper.ExtractionOutput
import com.wirelessalien.zipxtract.helper.SafeExtractionEngine
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ExtractionPathsTest {
    @Test fun traversalAbsoluteDriveAndNulAreRejected() {
        for (name in listOf("../outside", "a/../../outside", "a\\..\\outside", "/absolute", "\\\\server\\file", "C:/file", "x\u0000y"))
            assertTrue(name, runCatching { ExtractionOutput.normalize(name) }.isFailure)
    }
    @Test fun harmlessTarDotSegmentsNormalizeWithoutDecodingFilenames() {
        assertEquals("中文/a +%20.txt", ExtractionOutput.normalize("./中文//a +%20.txt"))
        assertEquals("", ExtractionOutput.normalize("./", true))
        assertTrue(runCatching { ExtractionOutput.normalize("./", false) }.isFailure)
    }
    @Test fun masterZipAndOutputStemAreChosenCorrectly() {
        assertEquals("archive.zip", SafeExtractionEngine.firstVolume(File("archive.z01")).name)
        assertEquals("archive.zip.001", SafeExtractionEngine.firstVolume(File("archive.zip.003")).name)
        assertEquals("archive", SafeExtractionEngine.outputStem("archive.tar.gz"))
        assertEquals("archive", SafeExtractionEngine.outputStem("archive.7z.001"))
    }
}
