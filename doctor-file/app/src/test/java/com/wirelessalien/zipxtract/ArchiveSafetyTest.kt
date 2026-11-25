package com.wirelessalien.zipxtract

import com.wirelessalien.zipxtract.helper.ArchiveSafety
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ArchiveSafetyTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file(name: String, text: String = "abc"): File = File(temp.root, name).apply { parentFile.mkdirs(); writeText(text) }
    @Test fun capturesNestedUnicodeAndEmptyDirectoriesWithoutDuplicates() {
        val a = file("目录/子目录/中文.txt")
        File(temp.root, "目录/空目录").mkdirs()
        val root = File(temp.root, "目录")
        val plan = ArchiveSafety.capture(listOf(root.path, a.path, root.path))
        assertEquals(1, plan.roots.size); assertEquals(4, plan.entries.size)
        assertEquals(3L, plan.entries.single { it.file == a.canonicalFile }.size)
    }
    @Test fun blocksOutputInsideSelection() {
        file("folder/a"); val root = File(temp.root, "folder")
        val plan = ArchiveSafety.capture(listOf(root.path))
        assertThrows(IOException::class.java) { plan.assertOutputOutsideSources(File(root, "output")) }
        plan.assertOutputOutsideSources(File(temp.root, "folder-other"))
    }
    @Test fun modifiedContentWithSameLengthAndTimestampIsRetained() {
        val a = file("a"); val plan = ArchiveSafety.capture(listOf(a.path)); val time = a.lastModified()
        a.writeText("xyz"); a.setLastModified(time)
        assertThrows(IOException::class.java) { plan.deleteVerifiedSources {} }
        assertTrue(a.exists())
    }
    @Test fun newChildrenPreventAnyDeletion() {
        val a = file("folder/a"); val root = a.parentFile
        val plan = ArchiveSafety.capture(listOf(root.path)); file("folder/new")
        assertThrows(IOException::class.java) { plan.deleteVerifiedSources {} }
        assertTrue(a.exists()); assertTrue(File(root,"new").exists())
    }
    @Test fun missingSourcePreventsDeletionOfOtherSources() {
        val a = file("a"); val b = file("b")
        val plan = ArchiveSafety.capture(listOf(a.path,b.path)); b.delete()
        assertThrows(IOException::class.java) { plan.deleteVerifiedSources {} }; assertTrue(a.exists())
    }
    @Test fun verifiedInventoryDeletesOnlySelectedTree() {
        file("folder/a"); val unrelated = file("other"); val root = File(temp.root,"folder")
        val plan = ArchiveSafety.capture(listOf(root.path))
        assertTrue(plan.deleteVerifiedSources {}.isEmpty()); assertFalse(root.exists()); assertTrue(unrelated.exists())
    }
    @Test fun cancellationBeforeDeletionKeepsFiles() {
        val a = file("a"); val plan = ArchiveSafety.capture(listOf(a.path))
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            plan.deleteVerifiedSources { throw java.util.concurrent.CancellationException() }
        }; assertTrue(a.exists())
    }
    @Test fun differentParentSelectionsUseCommonAncestor() {
        val a = file("one/a"); val b = file("two/a", "other")
        val plan = ArchiveSafety.capture(listOf(a.path,b.path))
        assertEquals(setOf("one/a","two/a"),plan.entries.map { it.name }.toSet())
    }
}
