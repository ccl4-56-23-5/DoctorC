package com.wirelessalien.zipxtract

import com.wirelessalien.zipxtract.nativeui.MediaStoreFileMatch
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MediaStoreFileMatchTest {
    @Test fun onlyOneValidCanonicalDataMatchCanBeUsed() {
        val root = Files.createTempDirectory("zipdoctorc-media-match-").toFile()
        try {
            val file = File(root, "中文 +%20%25 😀 #.png")
            val exact = MediaStoreFileMatch.Row(42, file.canonicalPath, 1)
            assertEquals(exact, MediaStoreFileMatch.unique(file, listOf(exact)))
            assertNull(MediaStoreFileMatch.unique(file, emptyList()))
            assertNull(MediaStoreFileMatch.unique(file, listOf(exact, exact.copy(id = 43))))
            assertNull(MediaStoreFileMatch.unique(file, listOf(exact.copy(id = 0))))
            assertNull(MediaStoreFileMatch.unique(file, listOf(exact.copy(data = null))))
            assertNull(MediaStoreFileMatch.unique(file, listOf(exact.copy(data = ""))))
            assertNull(MediaStoreFileMatch.unique(file, listOf(exact.copy(data = file.name))))
            // Same name in another folder, a suffix match, and decoded %20 are not file identity.
            assertNull(MediaStoreFileMatch.unique(file, listOf(exact.copy(data = File(root, "other/${file.name}").path))))
            assertNull(MediaStoreFileMatch.unique(file, listOf(exact.copy(data = file.path + ".bak"))))
            assertNull(MediaStoreFileMatch.unique(file, listOf(exact.copy(data = file.path.replace("%20", " ")))))
            assertNull(MediaStoreFileMatch.unique(file, listOf(exact.copy(data = file.path + '\u0000'))))
        } finally {
            check(root.canonicalFile.name.startsWith("zipdoctorc-media-match-"))
            root.deleteRecursively()
        }
    }
}
