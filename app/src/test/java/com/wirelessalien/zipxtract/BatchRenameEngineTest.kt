package com.wirelessalien.zipxtract

import com.wirelessalien.zipxtract.nativeui.BatchRenameEngine as Engine
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** All mutations are constrained to JUnit-owned temporary directories; no Android runtime required. */
class BatchRenameEngineTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file(name: String, text: String = name) = temp.newFile(name).apply { writeText(text) }
    private fun assertNoStaging() = assertTrue(temp.root.listFiles()!!.none { it.name.startsWith("ZipDoctorC-rename-") })
    // Windows JDK reports null fileKey. This fixture models stable inode IDs, not a production
    // fallback: IDs move ONLY when this helper successfully performs a real Files.move. New files
    // at vacated paths receive new IDs; parentReplacement therefore still tests a new parent ID.
    // The registry belongs to one JUnit instance and is captured by its plans: no global hook/state.
    private val fixtureIdentities = LinkedHashMap<String, Any>()
    private fun fixturePath(file: File) = file.absoluteFile.toPath().normalize().toString().lowercase(Locale.ROOT)
    private fun fixtureIdentity(file: File, attrs: BasicFileAttributes): Any =
        attrs.fileKey() ?: fixtureIdentities.getOrPut(fixturePath(file)) { Any() }
    private fun preview(files: List<File>, options: Engine.Options = Engine.Options()): Engine.Plan =
        Engine.previewForTesting(files, options, ::fixtureIdentity)
    private fun execute(plan: Engine.Plan, onProgress: (Int, Int) -> Unit = { _, _ -> }): Engine.Result =
        Engine.executeForTesting(plan, onProgress, ::move)
    private fun describe(plan: Engine.Plan) = (plan.errors + plan.entries.flatMap { row ->
        row.errors.map { "${row.source.path}: $it" }
    }).joinToString("\n").ifEmpty { "No preview errors; targets=${plan.entries.map { it.target.name }}" }
    private fun move(from: File, to: File) {
        fixtureIdentity(from, Files.readAttributes(from.toPath(), BasicFileAttributes::class.java))
        val old = fixturePath(from); val next = fixturePath(to)
        val moved = fixtureIdentities.filterKeys { it == old || it.startsWith(old + File.separator) }
        Files.move(from.toPath(), to.toPath())
        moved.keys.forEach { fixtureIdentities.remove(it) }
        moved.forEach { (path, id) -> fixtureIdentities[next + path.substring(old.length)] = id }
    }

    @Test fun identityAdapterIsPerPlanAndProductionStillRefusesUnknownIdentity() {
        val source = file("identity", "safe")
        val options = Engine.Options(prefix = "new_")
        val controlled = preview(listOf(source), options)
        assertTrue(describe(controlled), controlled.canExecute)
        val unknown = Engine.previewForTesting(listOf(source), options) { _, _ -> null }
        assertFalse(describe(unknown), unknown.canExecute)
        assertTrue(describe(unknown), unknown.entries.single().errors.any { it.contains("身份") })
        assertFalse(Engine.execute(unknown).success)
        // A test adapter never changes what a subsequent public production preview reads.
        val native = Engine.preview(listOf(source), options)
        val sourceKey = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java).fileKey()
        val parentKey = Files.readAttributes(source.parentFile.toPath(), BasicFileAttributes::class.java).fileKey()
        assertEquals(describe(native), sourceKey != null && parentKey != null, native.canExecute)
        assertEquals("safe", source.readText()); assertNoStaging()
    }

    @Test fun previewIsDeterministicReadOnlyAndKeepsCallerNumberingOrder() {
        val first = file("乙.tar.gz", "first")
        val second = file("甲.txt", "second")
        val options = Engine.Options(prefix = "照片_", suffix = "_尾", numbering = true, start = 7, step = 2)
        val plan = preview(listOf(first, second), options)
        assertTrue(describe(plan), plan.canExecute)
        assertEquals(listOf("照片_乙.tar_尾_007.gz", "照片_甲_尾_009.txt"), plan.entries.map { it.target.name })
        assertEquals(plan.entries, preview(listOf(first, second), options).entries)
        assertEquals("first", first.readText()); assertEquals("second", second.readText())
        assertEquals(2, temp.root.listFiles()!!.size)
    }

    @Test fun literalReplacementPreservesPlusPercentUnicodeAndEmoji() {
        val source = file("中文 +字面%20%25 😀 #.zip", "bytes")
        val plan = preview(listOf(source), Engine.Options(find = "%20", replace = "+%25", suffix = "_副本"))
        assertTrue(describe(plan), plan.canExecute)
        assertEquals("中文 +字面+%25%25 😀 #_副本.zip", plan.entries.single().target.name)
        assertEquals("bytes", source.readText())
    }

    @Test fun regexGroupsAndWholeNameReplacementAreSupported() {
        val source = file("Photo12.JPG")
        val plan = preview(listOf(source), Engine.Options(find = "^photo(\\d+)\\.jpg$",
            replace = "图_${'$'}1.png", regex = true, ignoreCase = true, keepExtension = false))
        assertTrue(describe(plan), plan.canExecute)
        assertEquals("图_12.png", plan.entries.single().target.name)
    }

    @Test fun foldersAndDotfilesAreNotSplitAsExtensions() {
        val folder = temp.newFolder("album.old")
        val hidden = file(".hidden")
        val plan = preview(listOf(folder, hidden), Engine.Options(suffix = "_new"))
        assertTrue(describe(plan), plan.canExecute)
        assertEquals(listOf("album.old_new", ".hidden_new"), plan.entries.map { it.target.name })
    }

    @Test fun unchangedOrEmptyBatchCannotExecute() {
        assertFalse(preview(listOf(file("a.txt"))).canExecute)
        assertFalse(preview(emptyList()).canExecute)
        assertFalse(execute(preview(emptyList())).success)
    }

    @Test fun malformedRegexAndBadReplacementArePreviewErrors() {
        val source = file("a.txt")
        assertTrue(preview(listOf(source), Engine.Options(find = "[", regex = true)).errors.isNotEmpty())
        val badGroup = preview(listOf(source), Engine.Options(find = "(a)", replace = "${'$'}8", regex = true))
        assertFalse(badGroup.canExecute)
        assertTrue(badGroup.entries.single().errors.isNotEmpty())
        assertTrue(source.exists())
    }

    @Test fun illegalAndTraversalNamesCannotExecute() {
        val source = file("a")
        for (name in listOf("", " ", ".", "..", "../escape", "..\\escape", "a/b", "a\\b", "nul\u0000x", "line\nname", "\ud83d")) {
            val plan = preview(listOf(source), Engine.Options(find = "^.+$", replace = java.util.regex.Matcher.quoteReplacement(name), regex = true, keepExtension = false))
            assertFalse("Rejected: $name", plan.canExecute)
            assertTrue(plan.entries.single().errors.isNotEmpty())
        }
        assertFalse(preview(listOf(source), Engine.Options(prefix = "中".repeat(90))).canExecute)
        assertEquals("a", source.readText())
    }

    @Test fun numericValidationAndOverflowNeverWrap() {
        val sources = listOf(file("a"), file("b"))
        for (options in listOf(
            Engine.Options(numbering = true, start = -1), Engine.Options(numbering = true, step = 0),
            Engine.Options(numbering = true, step = -1), Engine.Options(numbering = true, width = 19)
        )) assertFalse(preview(sources, options).canExecute)
        val overflow = preview(sources, Engine.Options(numbering = true, start = Long.MAX_VALUE, step = 1))
        assertFalse(overflow.canExecute)
        assertTrue(overflow.entries[1].errors.isNotEmpty())
        assertEquals("a_0", preview(sources.take(1), Engine.Options(numbering = true, start = 0, width = 0)).entries[0].target.name)
    }

    @Test fun duplicateSourcesAndDuplicateTargetsAreRejected() {
        val a = file("a"); val b = file("b")
        assertFalse(preview(listOf(a, a), Engine.Options(prefix = "new_")).canExecute)
        val duplicates = preview(listOf(a, b), Engine.Options(find = "^.+$", replace = "same", regex = true))
        assertFalse(duplicates.canExecute)
        assertTrue(duplicates.entries.all { it.errors.any { error -> error.contains("相同目标") } })
    }

    @Test fun caseAndUnicodeEquivalentTargetCollisionsAreConservativelyRejected() {
        val a = file("A1"); val b = file("a2")
        assertFalse(preview(listOf(a, b), Engine.Options(find = "\\d", replace = "", regex = true)).canExecute)
        val composed = file("é1"); val decomposed = file("e\u03012")
        assertFalse(preview(listOf(composed, decomposed), Engine.Options(find = "\\d", replace = "", regex = true)).canExecute)
    }

    @Test fun occupiedTargetAndStationarySelectedTargetCannotBeOverwritten() {
        val source = file("a", "source"); val occupied = file("new_a", "keep")
        val plan = preview(listOf(source), Engine.Options(prefix = "new_"))
        assertFalse(plan.canExecute); assertFalse(execute(plan).success)
        assertEquals("source", source.readText()); assertEquals("keep", occupied.readText())
        val stationary = preview(listOf(source, occupied), Engine.Options(find = "^a$", replace = "new_a", regex = true))
        assertFalse(stationary.canExecute)
    }

    @Test fun selectedDirectoryAndItsDescendantAreRejected() {
        val parent = temp.newFolder("parent")
        val child = File(parent, "child.txt").apply { writeText("child") }
        val plan = preview(listOf(parent, child), Engine.Options(prefix = "new_"))
        assertFalse(plan.canExecute)
        assertTrue(plan.entries.all { it.errors.any { error -> error.contains("子项目") } })
        assertEquals("child", child.readText())
    }

    @Test fun symlinkSourceAndDanglingTargetAreRejectedWhenSupported() {
        val source = file("source", "safe")
        val link = File(temp.root, "link")
        val dangling = File(temp.root, "new_source")
        try {
            Files.createSymbolicLink(link.toPath(), source.toPath())
            Files.createSymbolicLink(dangling.toPath(), File(temp.root, "missing").toPath())
        } catch (e: Exception) { assumeNoException(e); return }
        assertFalse(preview(listOf(link), Engine.Options(prefix = "new_")).canExecute)
        assertFalse(preview(listOf(source), Engine.Options(prefix = "new_")).canExecute)
        assertEquals("safe", source.readText())
    }

    @Test fun duplicateHardlinkIdentityIsRejectedWhenSupported() {
        val source = file("a", "safe"); val alias = File(temp.root, "b")
        try { Files.createLink(alias.toPath(), source.toPath()) } catch (e: Exception) { assumeNoException(e); return }
        fixtureIdentities[fixturePath(alias)] = fixtureIdentity(source,
            Files.readAttributes(source.toPath(), BasicFileAttributes::class.java))
        assertFalse(preview(listOf(source, alias), Engine.Options(prefix = "new_")).canExecute)
    }

    @Test fun executesTwoPhaseSwapWithoutLosingBytesAndReportsProgress() {
        val ab = file("ab.txt", "AB"); val ba = file("ba.txt", "BA")
        val plan = preview(listOf(ab, ba), Engine.Options(find = "^(.)(.)$", replace = "${'$'}2${'$'}1", regex = true))
        assertTrue(describe(plan), plan.canExecute)
        val progress = ArrayList<Pair<Int, Int>>()
        val result = execute(plan) { done, total -> progress += done to total }
        assertTrue(result.message, result.success)
        assertEquals(2, result.renamedCount)
        assertEquals("BA", ab.readText()); assertEquals("AB", ba.readText())
        assertEquals((0..4).map { it to 4 }, progress)
        assertNoStaging()
    }

    @Test fun executesThreeWayCycle() {
        val files = listOf(file("abc", "1"), file("bca", "2"), file("cab", "3"))
        val result = execute(preview(files, Engine.Options(find = "^(.)(.)(.)$", replace = "${'$'}2${'$'}3${'$'}1", regex = true)))
        assertTrue(result.message, result.success)
        assertEquals(listOf("3", "1", "2"), files.map { it.readText() })
        assertNoStaging()
    }

    @Test fun directoriesCanSwapAndContentsRemainUntouched() {
        val ab = temp.newFolder("ab"); val ba = temp.newFolder("ba")
        File(ab, "one").writeText("ONE"); File(ba, "two").writeText("TWO")
        val result = execute(preview(listOf(ab, ba), Engine.Options(find = "^(.)(.)$", replace = "${'$'}2${'$'}1", regex = true)))
        assertTrue(result.message, result.success)
        assertEquals("TWO", File(ab, "two").readText()); assertEquals("ONE", File(ba, "one").readText())
        assertNoStaging()
    }

    @Test fun caseOnlyRenameUsesStagingEvenOnCaseInsensitiveFilesystem() {
        val source = file("a.txt", "safe")
        val plan = preview(listOf(source), Engine.Options(find = "a", replace = "A"))
        assertTrue(describe(plan), plan.canExecute)
        val result = execute(plan)
        assertTrue(result.message, result.success)
        assertEquals("A.txt", File(temp.root, "A.txt").toPath().toRealPath().fileName.toString())
        assertEquals("safe", File(temp.root, "A.txt").readText())
        assertNoStaging()
    }

    @Test fun changedSourceAndNewlyOccupiedTargetAbortBeforeFirstMove() {
        val source = file("a", "old")
        val plan = preview(listOf(source), Engine.Options(prefix = "new_"))
        source.writeText("new-longer-content")
        assertFalse(execute(plan).success)
        val fresh = preview(listOf(source), Engine.Options(prefix = "new_"))
        val outsider = File(temp.root, "new_a").apply { writeText("do-not-touch") }
        assertFalse(execute(fresh).success)
        assertEquals("new-longer-content", source.readText()); assertEquals("do-not-touch", outsider.readText())
        assertNoStaging()
    }

    @Test fun sourceReplacementWithSameSizeAndTimeStillFailsIdentityValidation() {
        val source = file("a", "one")
        val plan = preview(listOf(source), Engine.Options(prefix = "new_"))
        val oldTime = Files.getLastModifiedTime(source.toPath())
        val replacement = file("replacement", "two")
        Files.setLastModifiedTime(replacement.toPath(), oldTime)
        Files.delete(source.toPath()); move(replacement, source)
        assertFalse(execute(plan).success)
        assertEquals("two", source.readText()); assertNoStaging()
    }

    @Test fun directoryContentTimeChangeAfterPreviewIsRejected() {
        val folder = temp.newFolder("folder")
        val plan = preview(listOf(folder), Engine.Options(prefix = "new_"))
        Files.setLastModifiedTime(folder.toPath(), FileTime.fromMillis(1000L))
        assertFalse(execute(plan).success)
        assertTrue(folder.isDirectory); assertNoStaging()
    }

    @Test fun stageAndFinalizeFailuresRollBackAllNames() {
        for (failureCall in listOf(2, 4)) {
            val root = temp.newFolder("case$failureCall")
            val a = File(root, "a").apply { writeText("A") }; val b = File(root, "b").apply { writeText("B") }
            val plan = preview(listOf(a, b), Engine.Options(prefix = "new_"))
            var calls = 0
            val result = Engine.executeForTesting(plan) { from, to ->
                if (++calls == failureCall) throw IOException("injected failure")
                move(from, to)
            }
            assertFalse(result.success); assertTrue(result.message, result.rolledBack)
            assertTrue(result.locations.all { it.restored })
            assertEquals("A", a.readText()); assertEquals("B", b.readText())
            assertEquals(setOf("a", "b"), root.listFiles()!!.map { it.name }.toSet())
        }
    }

    @Test fun directorySwapRollsBackAfterFirstOrSecondFinalizedMove() {
        for (after in 1..2) assertDirectoryCycleRollback(listOf("ab", "ba"), after)
    }

    @Test fun threeDirectoryCycleRollsBackAfterSecondFinalizedMove() {
        assertDirectoryCycleRollback(listOf("abc", "bca", "cab"), 2)
    }

    private fun assertDirectoryCycleRollback(names: List<String>, after: Int) {
        val root = temp.newFolder("directoryRollback${names.size}_$after")
        val directories = names.mapIndexed { index, name ->
            File(root, name).apply {
                assertTrue(mkdir())
                File(this, "root.bin").writeBytes(byteArrayOf(index.toByte(), 0, -1, 42))
                val child = File(this, "子%20+😀").apply { assertTrue(mkdir()) }
                File(child, "payload.txt").writeText("original-$index-$name")
            }
        }
        val pattern = if (names.size == 2) "^(.)(.)$" else "^(.)(.)(.)$"
        val replacement = if (names.size == 2) "${'$'}2${'$'}1" else "${'$'}2${'$'}3${'$'}1"
        val plan = preview(directories, Engine.Options(find = pattern, replace = replacement, regex = true))
        assertTrue(describe(plan), plan.canExecute)
        var calls = 0
        val result = Engine.executeForTesting(plan) { from, to ->
            move(from, to)
            if (++calls == names.size + after) throw IOException("directory finalize $after moved then failed")
        }
        assertFalse(result.success); assertTrue(result.message, result.rolledBack)
        assertEquals(0, result.renamedCount)
        assertTrue(result.locations.all { it.restored && it.current?.path == it.source.path })
        // Exact root membership also proves that no staging name was left behind.
        assertEquals(names.toSet(), root.listFiles()!!.map { it.name }.toSet())
        directories.forEachIndexed { index, directory ->
            assertTrue(directory.isDirectory)
            assertEquals(setOf("root.bin", "子%20+😀"), directory.listFiles()!!.map { it.name }.toSet())
            assertArrayEquals(byteArrayOf(index.toByte(), 0, -1, 42), File(directory, "root.bin").readBytes())
            val child = File(directory, "子%20+😀")
            assertEquals(listOf("payload.txt"), child.listFiles()!!.map { it.name })
            assertEquals("original-$index-${names[index]}", File(child, "payload.txt").readText())
        }
    }

    @Test fun failureAfterSuccessfulMoveIsReconciledAndRolledBack() {
        val source = file("a", "A")
        var calls = 0
        val result = Engine.executeForTesting(preview(listOf(source), Engine.Options(prefix = "new_"))) { from, to ->
            move(from, to)
            if (++calls == 2) throw IOException("provider moved then reported failure")
        }
        assertFalse(result.success); assertTrue(result.message, result.rolledBack)
        assertEquals("A", source.readText()); assertNoStaging()
    }

    @Test fun incompleteRollbackPreservesOutsiderAndReportsExactRecoveryPath() {
        val source = file("a", "ORIGINAL")
        var calls = 0
        val result = Engine.executeForTesting(preview(listOf(source), Engine.Options(prefix = "new_"))) { from, to ->
            if (++calls == 2) {
                source.writeText("OUTSIDER")
                throw IOException("destination failure and original path occupied")
            }
            move(from, to)
        }
        assertFalse(result.success); assertFalse(result.rolledBack)
        assertEquals("OUTSIDER", source.readText())
        val location = result.locations.single()
        assertFalse(location.restored); assertNotNull(location.current)
        val current = requireNotNull(location.current)
        assertEquals("ORIGINAL", current.readText())
        assertTrue(result.message.contains(current.path))
        assertTrue(current.name.startsWith("ZipDoctorC-rename-"))
    }

    @Test fun parentReplacementIsRejectedEvenWhenSourceIdentityAndMetadataArePreserved() {
        val parent = temp.newFolder("parent")
        val source = File(parent, "a").apply { writeText("original") }
        val plan = preview(listOf(source), Engine.Options(prefix = "new_"))
        assertTrue(describe(plan), plan.canExecute)
        val stamp = plan.stamps.values.single()
        val oldParent = File(temp.root, "old-parent")
        move(parent, oldParent)
        Files.createDirectory(parent.toPath())
        move(File(oldParent, "a"), source)
        assertEquals(stamp.key, fixtureIdentity(source, Files.readAttributes(source.toPath(), BasicFileAttributes::class.java)))
        assertNotEquals(stamp.parentKey, fixtureIdentity(parent, Files.readAttributes(parent.toPath(), BasicFileAttributes::class.java)))
        val result = execute(plan)
        assertFalse(result.success)
        assertEquals("original", source.readText())
        assertEquals(listOf("a"), parent.listFiles()!!.map { it.name })
    }

    @Test fun targetAppearingAfterStagingIsPreservedAndSourceIsRestored() {
        val source = file("a", "ORIGINAL")
        val target = File(temp.root, "new_a")
        var calls = 0
        val result = Engine.executeForTesting(preview(listOf(source), Engine.Options(prefix = "new_"))) { from, to ->
            move(from, to)
            if (++calls == 1) target.writeText("OUTSIDER")
        }
        assertFalse(result.success); assertTrue(result.message, result.rolledBack)
        assertEquals("ORIGINAL", source.readText()); assertEquals("OUTSIDER", target.readText())
        assertNoStaging()
    }

    @Test fun androidLeafCharactersAreNotUriDecodedOrWindowsSanitized() {
        val source = file("literal+%20%25")
        val plan = preview(listOf(source), Engine.Options(suffix = " 😀 #?"))
        // A Windows-host filesystem may reject '?'; the planned Android name must remain untouched.
        assertEquals("literal+%20%25 😀 #?", plan.entries.single().target.name)
        assertEquals("literal+%20%25", source.readText())
    }

    @Test fun callbackFailureCannotInterruptTransaction() {
        val source = file("a", "A")
        val result = execute(preview(listOf(source), Engine.Options(prefix = "new_"))) { _, _ -> error("UI gone") }
        assertTrue(result.message, result.success)
        assertEquals("A", File(temp.root, "new_a").readText()); assertNoStaging()
    }
}
