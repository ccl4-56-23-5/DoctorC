package com.wirelessalien.zipxtract

import com.wirelessalien.zipxtract.nativeui.RenamerRules
import com.wirelessalien.zipxtract.nativeui.RenamerRules.Kind
import com.wirelessalien.zipxtract.nativeui.RenamerRules.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure rule-chain behaviour of the 1.0.9 "ReNamer Lite" panel.
 * These are JVM tests: no file is touched, so the metadata assertions prove that the rules read
 * the ORIGINAL modification time supplied by the caller rather than "now" or a post-rename value.
 */
class RenamerRulesTest {
    private fun stamp(millis: Long) = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(Date(millis))

    private fun apply(name: String, vararg rules: Rule, index: Int = 0, modified: Long = 0L,
                      size: Long = 0L, directory: Boolean = false) =
        RenamerRules.apply(name, directory, index, modified, size, rules.toList())

    @Test
    fun screenshotChainDeletesThenInsertsOriginalDateAndSuffixes() {
        val modified = 1_709_616_429_000L
        val result = apply(
            "报告.pdf", 
            Rule(Kind.DELETE, mode = "all"),
            Rule(Kind.INSERT, text = ":File DateModified:", mode = "prefix"),
            Rule(Kind.INSERT, text = "聚餐", mode = "suffix"),
            Rule(Kind.INSERT, text = " ", mode = "suffix"),
            modified = modified
        )
        // Delete acts on the base name only, the two insertions keep the ".pdf" extension intact.
        assertEquals("${stamp(modified)}聚餐 .pdf", result)
    }

    @Test
    fun dateRuleUsesOriginalTimeNotThePreviewMoment() {
        val early = 946_684_800_000L
        val late = 1_600_000_000_000L
        val rule = Rule(Kind.INSERT, text = ":File DateModified:", mode = "prefix")
        assertEquals("${stamp(early)}a.jpg", apply("a.jpg", rule, modified = early))
        assertEquals("${stamp(late)}a.jpg", apply("a.jpg", rule, modified = late))
        assertNotEquals(apply("a.jpg", rule, modified = early), apply("a.jpg", rule, modified = late))
    }

    @Test
    fun disabledRulesAreInertEvenWhenInvalid() {
        val broken = Rule(Kind.REGEX, text = "(", enabled = false)
        assertTrue(RenamerRules.validate(listOf(broken)).isEmpty())
        assertEquals("name.txt", apply("name.txt", broken))
    }

    @Test
    fun invalidEnabledRuleIsReportedOnceAndBlocksApply() {
        val invalid = Rule(Kind.REGEX, text = "(")
        assertTrue(RenamerRules.validate(listOf(invalid)).isNotEmpty())
        try {
            apply("name.txt", invalid)
            fail("Invalid regex must not be applied")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("正则"))
        }
    }

    @Test
    fun skipExtensionProtectsTheFinalSuffixWhileFullNameModeAppendsAfterIt() {
        val protectedRule = Rule(Kind.INSERT, text = "_1", mode = "suffix", skipExtension = true)
        val wholeName = Rule(Kind.INSERT, text = "_1", mode = "suffix", skipExtension = false)
        assertEquals("photo_1.jpg", apply("photo.jpg", protectedRule))
        assertEquals("photo.jpg_1", apply("photo.jpg", wholeName))
    }

    @Test
    fun deleteFirstKeepsTheRestAndTheExtension() {
        assertEquals("hoto.jpg", apply("photo.jpg", Rule(Kind.DELETE, mode = "first")))
        assertEquals("phot.jpg", apply("photo.jpg", Rule(Kind.DELETE, mode = "last")))
    }

    @Test
    fun hiddenAndDotlessNamesAreSplitAsWholeNames() {
        // ".gitignore" has no owned extension, so deleting everything really leaves nothing.
        assertEquals("", apply(".gitignore", Rule(Kind.DELETE, mode = "all")))
        assertEquals("", apply("README", Rule(Kind.DELETE, mode = "all")))
        // ... but an extensionless file must still accept a new name from a following rule.
        assertEquals("new", apply("README", Rule(Kind.DELETE, mode = "all"),
            Rule(Kind.INSERT, text = "new", mode = "prefix")))
    }

    @Test
    fun ruleOrderChangesTheResult() {
        val ab = Rule(Kind.REPLACE, text = "a", replacement = "b")
        val bc = Rule(Kind.REPLACE, text = "b", replacement = "c")
        assertEquals("ccc.txt", apply("aaa.txt", ab, bc))
        assertEquals("bbb.txt", apply("aaa.txt", bc, ab))
    }

    @Test
    fun serializeUsesTheSelectionIndexAndKeepsWidth() {
        val rule = Rule(Kind.SERIALIZE, mode = "prefix", start = 5, step = 2, width = 3, text = "IMG_")
        assertEquals("IMG_005x.jpg", apply("x.jpg", rule, index = 0))
        assertEquals("IMG_009x.jpg", apply("x.jpg", rule, index = 2))
    }

    @Test
    fun aSecondSpaceComesFromAnotherLiteralFieldAndTheMiddleDotIsANameCharacter() {
        val caption = Rule(Kind.USER_INPUT, mode = "suffix", text = "·初试")
        val separator = Rule(Kind.INSERT, mode = "suffix", text = " ")
        val number = Rule(Kind.SERIALIZE, mode = "suffix", width = 2)
        assertEquals("照片·初试 01.jpg", apply("照片.jpg", caption, separator, number))
        val numberedSpace = number.copy(text = " ")
        assertEquals("照片·初试  01.jpg", apply("照片.jpg", caption, separator, numberedSpace))
        assertTrue(RenamerRules.describe(numberedSpace).contains("编号前 1 个空格"))

        val captionSpace = caption.copy(text = "·初试 ")
        assertEquals("照片·初试  01.jpg", apply("照片.jpg", captionSpace, separator, number))
        assertTrue(RenamerRules.describe(captionSpace).contains("末尾 1 个空格"))
    }

    @Test
    fun codePointPositionsHandleEmojiAndChinese() {
        assertEquals("ab", apply("\uD83D\uDE00ab", Rule(Kind.DELETE, mode = "first")))
        assertEquals("聚餐_1.txt", apply("聚餐.txt", Rule(Kind.INSERT, text = "_1", mode = "suffix")))
        assertEquals("AA.txt", apply("aa.txt", Rule(Kind.CASE, mode = "upper", skipExtension = true)))
    }

    @Test
    fun simpleKindsBehaveAsDescribed() {
        assertEquals("ABC.txt", apply("abc.txt", Rule(Kind.CASE, mode = "upper")))
        assertEquals("ab.txt", apply("a1b2.txt", Rule(Kind.STRIP, mode = "digits")))
        assertEquals("cafe.txt", apply("caf\u00E9.txt", Rule(Kind.TRANSLITERATE, mode = "latin")))
        assertEquals("b.txt", apply("a.txt", Rule(Kind.MAPPING, mode = "exact", text = "a", replacement = "b")))
        assertEquals("a-b.txt", apply("a b.txt", Rule(Kind.REPLACE, text = " ", replacement = "-")))
    }

    @Test
    fun unknownMetadataTokenIsRejected() {
        try {
            apply("x.txt", Rule(Kind.INSERT, text = ":File ExifDate:", mode = "prefix"))
            fail("Unknown metadata tokens must not be silently dropped")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("占位符"))
        }
    }

    @Test
    fun everyKindHasAChineseTitleAndDescription() {
        for (kind in Kind.values()) {
            assertTrue(kind.title.isNotBlank())
            val description = RenamerRules.describe(Rule(kind))
            assertTrue(description.isNotBlank())
        }
    }

    @Test
    fun ruleLimitIsEnforced() {
        val rules = List(65) { Rule(Kind.INSERT, text = "x") }
        assertTrue(RenamerRules.validate(rules).any { it.contains("64") })
    }
}
