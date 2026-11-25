package com.wirelessalien.zipxtract

import com.wirelessalien.zipxtract.nativeui.*
import com.wirelessalien.zipxtract.nativeui.RenameFileOrder.Key
import com.wirelessalien.zipxtract.nativeui.RenameFileOrder.Settings
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RenameFileOrderTest {
    private fun names(input: List<String>, key: Key, descending: Boolean = false) =
        RenameFileOrder.sort(input.map { File(it) }, Settings(key, descending)).map { it.name }

    @Test fun naturalNumbersIncludeHugeRunsWithoutOverflow() {
        assertEquals(listOf("照片2.jpg", "照片10.jpg", "照片9999999999999999999999999.jpg"),
            names(listOf("照片9999999999999999999999999.jpg", "照片10.jpg", "照片2.jpg"), Key.NATURAL))
    }
    @Test fun originalSelectionCanBeRestoredAfterOtherSorts() {
        val original = listOf("c.txt", "a.txt", "b.txt")
        names(original, Key.NAME)
        assertEquals(original, names(original, Key.ORIGINAL))
        assertEquals(original.reversed(), names(original, Key.ORIGINAL, true))
    }
    @Test fun descendingKeepsEqualNumericNamesStable() {
        assertEquals(listOf("file10", "file02", "file2", "FILE002"),
            names(listOf("file02", "file2", "FILE002", "file10"), Key.NATURAL, true))
    }
    @Test fun alphabeticAndNaturalAreDistinct() {
        assertEquals(listOf("a10", "a2"), names(listOf("a2", "a10"), Key.NAME))
        assertEquals(listOf("a2", "a10"), names(listOf("a2", "a10"), Key.NATURAL))
    }
    @Test fun comparatorRemainsAntisymmetricAcrossUnicodeAndDigits() {
        val values = listOf("", "0", "00", "000b", "0a", "2", "02", "10", "a", "A", "😀2", "😀10", "中文9")
        for (a in values) for (b in values) {
            val ab = RenameFileOrder.naturalCompare(a, b).compareTo(0)
            assertEquals(-ab, RenameFileOrder.naturalCompare(b, a).compareTo(0))
            for (c in values) if (ab <= 0 && RenameFileOrder.naturalCompare(b, c) <= 0)
                assertTrue(RenameFileOrder.naturalCompare(a, c) <= 0)
        }
    }
    @Test fun extensionSortIsCaseInsensitiveAndStable() {
        assertEquals(listOf("README", "one.jpg", "two.JPG", "a.zip"),
            names(listOf("a.zip", "one.jpg", "two.JPG", "README"), Key.EXTENSION))
    }
    @Test fun metadataSortAndNumberingFollowTheSameIdentities() {
        val root = Files.createTempDirectory("rename-order-").toFile()
        try {
            val ten = File(root, "file10.txt").apply { writeText("longer"); setLastModified(1000000) }
            val two = File(root, "file2.txt").apply { writeText("x"); setLastModified(2000000) }
            val input = listOf(ten, two)
            assertEquals(listOf(ten, two), RenameFileOrder.sort(input, Settings(Key.MODIFIED)))
            assertEquals(listOf(two, ten), RenameFileOrder.sort(input, Settings(Key.SIZE)))
            val sorted = RenameFileOrder.sort(input, Settings(Key.NATURAL))
            // Windows does not expose fileKey; use the existing test-only identity adapter.
            // The device suite exercises the real Android filesystem identity path.
            val plan = BatchRenameEngine.previewForTesting(sorted, BatchRenameEngine.Options(rules = listOf(
                RenamerRules.Rule(RenamerRules.Kind.SERIALIZE, mode = "replace", width = 2)))) { f, _ -> f.canonicalPath }
            assertEquals(listOf(two, ten), plan.entries.map { it.source })
            assertEquals(listOf("01.txt", "02.txt"), plan.entries.map { it.target.name })
            assertTrue(plan.canExecute)
            assertEquals(listOf("file10.txt", "file2.txt"), input.map { it.name })
        } finally { root.deleteRecursively() }
    }
}
