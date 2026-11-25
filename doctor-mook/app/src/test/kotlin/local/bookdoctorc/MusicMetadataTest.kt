package local.bookdoctorc

import org.junit.Assert.*
import org.junit.Test

class MusicMetadataTest {
    @Test fun lyricTimestampParsesFractionOffsetAndMultiplePositions() {
        val lyrics = MusicMetadata.text("[offset:-150]\n[00:13.32]第二行\n[00:07][00:10.2]第一行")
        assertEquals(listOf(6850L, 10050L, 13170L), lyrics.map { it.millis })
        assertEquals(listOf("第一行", "第一行", "第二行"), lyrics.map { it.text })
    }

    @Test fun plainWordsDoNotGetInventedSeekPositions() {
        val lyrics = MusicMetadata.text("一行歌词\n再一行")
        assertEquals("一行歌词\n再一行", lyrics.single().text)
        assertNull(lyrics.single().millis)
    }
}
