package com.wirelessalien.zipxtract.nativeui

import java.security.MessageDigest
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.TimeZone
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Ordered, pure filename transformations, not a port of Pascal/ExifTool or the complete desktop app.
 * Metadata always refers to the ORIGINAL input. Positions/counts/padding use Unicode code points.
 * Extension ownership survives protected steps even when the base becomes empty ("" + ".jpg").
 * Whole-name steps explicitly repartition their result; EXTENSION edits the owned extension directly.
 * Random text is deterministic, not cryptographic. The caller must execute frozen preview targets.
 * apply performs no filesystem I/O and does not validate final filesystem leaf names/collisions.
 */
object RenamerRules {
    enum class Kind(val title: String) {
        INSERT("插入"), DELETE("删除"), REMOVE("移除"), REPLACE("替换"), REGEX("正则表达式"),
        EXTENSION("扩展名"), CASE("大小写"), SERIALIZE("编号"), CLEANUP("清理"), STRIP("剥离"),
        REARRANGE("重排"), PAD("填充"), DATE("日期"), MAPPING("映射"),
        TRANSLITERATE("转写"), RANDOM("随机"), USER_INPUT("用户输入")
    }

    enum class DateSource(val title: String) {
        MODIFIED("最后修改时间"),
        EXIF("EXIF 拍摄时间"),
        EXIF_OR_MODIFIED("EXIF 优先，缺失时用修改时间")
    }

    data class CaptureDate(val millis: Long, val zone: TimeZone)

    data class Rule(
        val kind: Kind, val enabled: Boolean = true, val skipExtension: Boolean = true,
        val text: String = "", val replacement: String = "", val mode: String = "prefix",
        val position: Int = 1, val count: Int = 0, val ignoreCase: Boolean = false,
        val start: Long = 1, val step: Long = 1, val width: Int = 3,
        val dateFormat: String = "yyyy-MM-dd HH.mm.ss", val name: String = "",
        val dateSource: DateSource = DateSource.MODIFIED
    )

    private const val MAX_RULES = 64
    private const val MAX_TEXT = 4096
    private const val MAX_OUTPUT = 16384
    private const val REGEX_NANOS = 100_000_000L
    private val insertionModes = setOf("prefix", "suffix", "position", "replace")
    private val tokenNames = setOf("file datemodified", "file name", "file basename", "file extension", "file size")
    private data class Name(var base: String, var extension: String) {
        val full: String get() = base + extension
    }
    private fun split(name: String, directory: Boolean, ownedExtension: Boolean = false): Name {
        val dot = if (directory) -1 else name.lastIndexOf('.').takeIf { it > 0 || (it == 0 && ownedExtension) } ?: -1
        return if (dot < 0) Name(name, "") else Name(name.substring(0, dot), name.substring(dot))
    }

    /** Disabled steps are inert, including invalid settings. The total chain is still limited to 64. */
    fun validate(rules: List<Rule>): List<String> {
        val errors = ArrayList<String>()
        if (rules.size > MAX_RULES) errors += "最多允许 $MAX_RULES 条规则"
        rules.take(MAX_RULES).forEachIndexed { index, rule ->
            if (rule.enabled) try { validateRule(rule) }
            catch (e: Exception) { errors += "规则${index + 1} ${rule.kind.title}：${e.message ?: "设置无效"}" }
            catch (_: StackOverflowError) { errors += "规则${index + 1} 正则表达式过于复杂" }
        }
        return Collections.unmodifiableList(errors)
    }

    private fun mode(rule: Rule): String {
        val value = rule.mode.lowercase(Locale.ROOT)
        if (value == "replace" && rule.kind == Kind.REGEX) return "all"
        if (value != "prefix") return value
        return when (rule.kind) {
            Kind.DELETE, Kind.REMOVE, Kind.REPLACE, Kind.REGEX -> "all"
            Kind.EXTENSION -> "replace"
            Kind.CASE -> "lower"
            Kind.CLEANUP -> "trim"
            Kind.STRIP -> "digits"
            Kind.REARRANGE -> "reverse"
            Kind.PAD -> "left"
            Kind.MAPPING -> "exact"
            Kind.TRANSLITERATE -> "latin"
            else -> value
        }
    }

    private fun validateRule(r: Rule) {
        require(r.text.length <= MAX_TEXT && r.replacement.length <= MAX_TEXT) { "每项文字最多 $MAX_TEXT 字符" }
        require(validUnicode(r.text) && validUnicode(r.replacement)) { "文字包含不完整的 Unicode 字符" }
        require(r.mode.length <= 32 && validUnicode(r.mode)) { "模式无效" }
        require(r.position >= 1) { "位置从1开始" }
        require(r.count >= 0) { "数量不能为负数" }
        require(r.dateFormat.length in 1..128 && validUnicode(r.dateFormat)) { "日期格式长度须为1—128字符" }
        SimpleDateFormat(r.dateFormat, Locale.ROOT)
        val m = mode(r)
        val modes = when (r.kind) {
            Kind.INSERT -> setOf("prefix", "suffix", "position")
            Kind.DELETE -> setOf("all", "range", "first", "last")
            Kind.REMOVE, Kind.REPLACE -> setOf("all", "first", "last")
            Kind.REGEX -> setOf("all", "first")
            Kind.EXTENSION -> setOf("replace", "add", "remove", "lower", "upper")
            Kind.CASE -> setOf("lower", "upper", "title", "sentence", "invert")
            Kind.SERIALIZE, Kind.DATE, Kind.RANDOM, Kind.USER_INPUT -> insertionModes
            Kind.CLEANUP -> setOf("trim", "spaces", "whitespace", "control", "all")
            Kind.STRIP -> setOf("digits", "letters", "symbols", "brackets", "diacritics", "all")
            Kind.REARRANGE -> setOf("reverse", "words", "parts")
            Kind.PAD -> setOf("left", "right", "numbers")
            Kind.MAPPING -> setOf("exact", "lines")
            Kind.TRANSLITERATE -> setOf("latin", "custom")
        }
        require(m in modes) { "不支持的模式：${r.mode}" }
        when (r.kind) {
            Kind.INSERT, Kind.USER_INPUT -> checkTokens(r.text)
            Kind.DATE -> { checkTokens(r.text); checkTokens(r.replacement) }
            Kind.SERIALIZE -> {
                require(r.start >= 0 && r.step > 0 && r.width in 0..18) { "编号须起始值≥0、步长>0、位数0—18" }
                checkTokens(r.text); checkTokens(r.replacement)
            }
            Kind.REMOVE, Kind.REPLACE -> require(r.text.isNotEmpty()) { "查找文字不能为空" }
            Kind.REGEX -> { require(r.text.isNotEmpty()) { "正则表达式不能为空" }; compile(r) }
            Kind.PAD -> {
                require(r.width in 0..1024) { "填充宽度须为0—1024" }
                require(r.text.isEmpty() || cpLength(r.text) == 1) { "填充文字须为一个 Unicode 字符" }
            }
            Kind.REARRANGE -> if (m == "parts") order(r.replacement)
            Kind.MAPPING -> if (m == "lines") mappings(r.text)
            Kind.TRANSLITERATE -> if (m == "custom") {
                val keys = codePoints(r.text)
                require(keys.isNotEmpty() && keys.size == cpLength(r.replacement)) { "自定义转写的源字符与目标字符数量须相同且非空" }
                require(keys.distinct().size == keys.size) { "自定义转写的源字符不能重复" }
            }
            Kind.RANDOM -> require(r.count in 0..128) { "随机长度须为0—128（0表示8）" }
            else -> Unit
        }
    }

    fun apply(originalName: String, directory: Boolean, index: Int, modifiedMillis: Long, size: Long,
              rules: List<Rule>, exifDate: CaptureDate? = null): String {
        require(index >= 0 && size >= 0) { "选择索引与文件大小不能为负数" }
        require(originalName.length <= MAX_OUTPUT && validUnicode(originalName)) { "原名称过长或包含不完整的 Unicode 字符" }
        require(rules.size <= MAX_RULES) { "最多允许 $MAX_RULES 条规则" }
        val chain = rules.toList()
        val errors = validate(chain)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        val original = split(originalName, directory)
        var name = original.copy()
        val budget = Budget()
        fun expand(text: String, rule: Rule) = tokens(text) { token ->
            when (token) {
                "file name" -> originalName
                "file basename" -> original.base
                "file extension" -> original.extension.removePrefix(".")
                "file size" -> size.toString()
                "file datemodified" -> SimpleDateFormat(rule.dateFormat, Locale.ROOT).format(Date(modifiedMillis))
                else -> throw IllegalArgumentException("不支持元数据占位符：:$token:")
            }
        }
        chain.forEachIndexed { ordinal, r ->
            if (r.enabled) try {
                budget.check()
                val m = mode(r)
                if (r.kind == Kind.EXTENSION) {
                    if (!directory) name.extension = when (m) {
                        "remove" -> ""
                        "lower" -> name.extension.lowercase(Locale.ROOT)
                        "upper" -> name.extension.uppercase(Locale.ROOT)
                        "add" -> if (name.extension.isNotEmpty()) name.extension else extension(r.text)
                        else -> extension(r.text)
                    }
                } else {
                    val target = if (r.skipExtension || directory) name.base else name.full
                    val result = when (r.kind) {
                        Kind.INSERT, Kind.USER_INPUT -> insert(target, expand(r.text, r), m, r.position)
                        Kind.DELETE -> delete(target, m, r.position, r.count)
                        Kind.REMOVE -> literal(target, r.text, "", m, r.ignoreCase, budget)
                        Kind.REPLACE -> literal(target, r.text, r.replacement, m, r.ignoreCase, budget)
                        Kind.REGEX -> budget.runRegex { regex(target, r, budget) }
                        Kind.CASE -> changeCase(target, m)
                        Kind.SERIALIZE -> {
                            val number = Math.addExact(r.start, Math.multiplyExact(index.toLong(), r.step))
                            insert(target, expand(r.text, r) + number.toString().padStart(r.width, '0') + expand(r.replacement, r), m, r.position)
                        }
                        Kind.DATE -> {
                            val capture = when (r.dateSource) {
                                DateSource.MODIFIED -> null
                                DateSource.EXIF -> requireNotNull(exifDate) { "没有可读取的 EXIF 拍摄时间" }
                                DateSource.EXIF_OR_MODIFIED -> exifDate
                            }
                            val format = SimpleDateFormat(r.dateFormat, Locale.ROOT)
                            if (capture != null) format.timeZone = capture.zone
                            val date = format.format(Date(capture?.millis ?: modifiedMillis))
                            insert(target, expand(r.text, r) + date + expand(r.replacement, r), m, r.position)
                        }
                        Kind.CLEANUP -> cleanup(target, m)
                        Kind.STRIP -> strip(target, m)
                        Kind.REARRANGE -> rearrange(target, r, m)
                        Kind.PAD -> pad(target, r, m, budget)
                        Kind.MAPPING -> {
                            val pairs = if (m == "lines") mappings(r.text) else listOf(r.text to r.replacement)
                            pairs.firstOrNull { target.equals(it.first, r.ignoreCase) }?.second ?: target
                        }
                        Kind.TRANSLITERATE -> transliterate(target, r, m)
                        Kind.RANDOM -> insert(target, randomText(originalName, index, modifiedMillis, size, ordinal, r), m, r.position)
                        Kind.EXTENSION -> error("Handled above")
                    }
                    require(result.length <= MAX_OUTPUT && validUnicode(result)) { "规则结果过长或包含不完整的 Unicode 字符" }
                    if (r.skipExtension || directory) name.base = result
                    else name = split(result, false, name.extension.isNotEmpty())
                }
                require(name.full.length <= MAX_OUTPUT) { "规则结果过长" }
            } catch (e: Exception) {
                throw IllegalArgumentException("规则${ordinal + 1} ${r.kind.title}：${e.message ?: "处理失败"}", e)
            } catch (_: StackOverflowError) {
                throw IllegalArgumentException("规则${ordinal + 1} 正则表达式过于复杂，请简化表达式")
            }
        }
        return name.full
    }

    fun describe(rule: Rule): String {
        val r = rule; val m = mode(r)
        val body = when (r.kind) {
            Kind.DELETE -> if (m == "all") "删除当前名称" else "删除 $m，第${r.position}字，数量${r.count}"
            Kind.INSERT -> "插入${describeInput(r.text)}，${insertionLabel(m)}"
            Kind.REMOVE -> "移除「${short(r.text)}」($m)"
            Kind.REPLACE, Kind.REGEX -> "${r.kind.title}「${short(r.text)}」→「${short(r.replacement)}」($m)"
            Kind.SERIALIZE -> {
                val before = r.text.takeLastWhile { it == ' ' }.length
                val after = r.replacement.takeWhile { it == ' ' }.length
                val spaces = buildString {
                    if (before > 0) append("，编号前 $before 个空格")
                    if (after > 0) append("，编号后 $after 个空格")
                }
                "编号：起始 ${r.start}-步长 ${r.step}-位数 ${r.width}$spaces，${insertionLabel(m)}"
            }
            Kind.DATE -> "插入${r.dateSource.title} ${short(r.dateFormat)}，${insertionLabel(m)}"
            Kind.RANDOM -> "确定性随机 ${if (r.count == 0) 8 else r.count}字，${insertionLabel(m)}"
            Kind.USER_INPUT -> "用户输入${describeInput(r.text)}，${insertionLabel(m)}"
            Kind.TRANSLITERATE -> if (m == "latin") "基础拉丁转写/去重音（非汉语拼音）" else "自定义逐字符转写"
            else -> "${r.kind.title} ($m)${if (r.text.isEmpty()) "" else "「${short(r.text)}」"}"
        }
        return (if (r.enabled) "" else "已停用 · ") + body +
            if (r.kind == Kind.EXTENSION) "" else if (r.skipExtension) "；忽略扩展名" else "；包含扩展名"
    }

    private fun describeInput(text: String): String {
        val first = text.takeWhile { it == ' ' }.length
        if (first > 0 && first == text.length) return " $first 个空格"
        val last = text.takeLastWhile { it == ' ' }.length
        val edges = buildList {
            if (first > 0) add("开头 $first 个空格")
            if (last > 0) add("末尾 $last 个空格")
        }
        val note = if (edges.isEmpty()) "" else edges.joinToString("、", "（", "）")
        return "$note「${short(text)}」"
    }

    private fun insertionLabel(m: String) = when (m) {
        "prefix" -> "前缀"; "suffix" -> "后缀"; "position" -> "位置插入"; else -> "替换当前名称"
    }
    private fun short(text: String) = if (cpLength(text) <= 60) text else cpSlice(text, 0, 60) + "…"
    private fun extension(text: String) = text.removePrefix(".").let { if (it.isEmpty()) "" else ".$it" }
    private fun insert(value: String, text: String, m: String, position: Int): String = when (m) {
        "prefix" -> text + value; "suffix" -> value + text; "replace" -> text
        else -> { val at = value.offsetByCodePoints(0, (position - 1).coerceAtMost(cpLength(value))); value.substring(0, at) + text + value.substring(at) }
    }
    private fun delete(value: String, m: String, position: Int, count: Int): String {
        val length = cpLength(value)
        return when (m) {
            "all" -> ""
            "first" -> cpSlice(value, (if (count == 0) 1 else count).coerceAtMost(length), length)
            "last" -> cpSlice(value, 0, (length - if (count == 0) 1 else count).coerceAtLeast(0))
            else -> {
                val start = (position - 1).coerceAtMost(length)
                val end = if (count == 0) length else (start.toLong() + count).coerceAtMost(length.toLong()).toInt()
                cpSlice(value, 0, start) + cpSlice(value, end, length)
            }
        }
    }

    private fun literal(value: String, find: String, replacement: String, m: String, ignore: Boolean, budget: Budget): String {
        val last = if (m == "last") value.lastIndexOf(find, ignoreCase = ignore) else -1
        var from = 0
        val out = StringBuilder()
        while (from <= value.length) {
            budget.check()
            val at = if (m == "last") last else value.indexOf(find, from, ignore)
            if (at < from) break
            append(out, value.substring(from, at)); append(out, replacement)
            from = at + find.length
            if (m != "all") break
        }
        append(out, value.substring(from))
        return out.toString()
    }
    private fun compile(r: Rule) = Pattern.compile(r.text,
        if (r.ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0)
    private fun regex(value: String, r: Rule, budget: Budget): String {
        val matcher = compile(r).matcher(BudgetText(value, budget))
        val out = StringBuilder(); var from = 0
        while (matcher.find()) {
            budget.check()
            append(out, value.substring(from, matcher.start()))
            replacement(out, r.replacement, matcher)
            from = matcher.end()
            if (mode(r) == "first") break
        }
        append(out, value.substring(from))
        return out.toString()
    }
    /**
     * Java replacement syntax, expanded incrementally so $0 repetition cannot allocate unbounded output.
     * Named-group access is API26+, which is exactly the minimum of the only caller
     * (BatchRenameEngine refuses API24/25 before any preview or execution).
     */
    @android.annotation.TargetApi(26)
    private fun replacement(out: StringBuilder, template: String, matcher: Matcher) {
        var i = 0
        while (i < template.length) when (val ch = template[i++]) {
            '\\' -> { require(i < template.length) { "正则替换末尾不能是反斜线" }; append(out, template[i++].toString()) }
            '$' -> {
                require(i < template.length) { "正则替换缺少分组引用" }
                val group: String? = if (template[i] == '{') {
                    val end = template.indexOf('}', i + 1)
                    require(end >= 0) { "命名分组缺少右花括号" }
                    val name = template.substring(i + 1, end); i = end + 1
                    matcher.group(name)
                } else {
                    require(template[i] in '0'..'9') { "正则替换分组引用无效" }
                    var number = template[i++].digitToInt()
                    require(number <= matcher.groupCount()) { "正则替换分组不存在：$number" }
                    while (i < template.length && template[i] in '0'..'9') {
                        val candidate = number.toLong() * 10 + template[i].digitToInt()
                        if (candidate > matcher.groupCount()) break
                        number = candidate.toInt(); i++
                    }
                    matcher.group(number)
                }
                append(out, group ?: "")
            }
            else -> append(out, ch.toString())
        }
    }

    private fun changeCase(value: String, mode: String): String {
        if (mode == "lower") return value.lowercase(Locale.ROOT)
        if (mode == "upper") return value.uppercase(Locale.ROOT)
        var begin = true
        val out = StringBuilder()
        for (cp in codePoints(value)) {
            val text = cpString(cp)
            val changed = when (mode) {
                "invert" -> if (Character.isUpperCase(cp)) text.lowercase(Locale.ROOT) else if (Character.isLowerCase(cp)) text.uppercase(Locale.ROOT) else text
                else -> if (begin && Character.isLetter(cp)) text.uppercase(Locale.ROOT) else text.lowercase(Locale.ROOT)
            }
            append(out, changed)
            if (mode == "title") begin = !Character.isLetterOrDigit(cp)
            else if (Character.isLetterOrDigit(cp)) begin = false
            else if (cp in listOf(46, 33, 63, 0x3002, 0xff01, 0xff1f)) begin = true
        }
        return out.toString()
    }
    private fun white(cp: Int) = Character.isWhitespace(cp) || Character.isSpaceChar(cp)
    private fun cleanup(value: String, m: String): String = when (m) {
        "trim" -> value.trim { white(it.code) }
        "whitespace" -> filter(value) { !white(it) }
        "control" -> filter(value) { !Character.isISOControl(it) }
        else -> {
            val out = StringBuilder(); var space = false
            for (cp in codePoints(value)) if (white(cp)) {
                if (!space) append(out, " "); space = true
            } else {
                if (m != "all" || !Character.isISOControl(cp)) append(out, cpString(cp))
                space = false
            }
            if (m == "all") out.toString().trim() else out.toString()
        }
    }
    private fun strip(value: String, m: String): String = when (m) {
        "digits" -> filter(value) { !Character.isDigit(it) }
        "letters" -> filter(value) { !Character.isLetter(it) }
        "symbols" -> filter(value) { Character.isLetterOrDigit(it) || white(it) }
        "all" -> filter(value) { Character.isLetter(it) }
        "diacritics" -> deaccent(value)
        else -> {
            val opens = "([{<（【《"; val closes = ")]}>）】》"
            val out = StringBuilder(); var depth = 0
            for (cp in codePoints(value)) when {
                cp <= 0xffff && opens.indexOf(cp.toChar()) >= 0 -> depth++
                cp <= 0xffff && closes.indexOf(cp.toChar()) >= 0 && depth > 0 -> depth--
                depth == 0 -> append(out, cpString(cp))
            }
            out.toString()
        }
    }
    private fun order(value: String): List<Int> {
        require(value.isNotBlank()) { "请输入逗号分隔的一基片段序号" }
        val fields = value.split(',')
        require(fields.size <= 1024) { "片段序号过多" }
        return fields.map { it.trim().toIntOrNull()?.takeIf { index -> index > 0 }
            ?: throw IllegalArgumentException("片段序号须为大于0的整数") }
    }
    private fun rearrange(value: String, r: Rule, m: String): String = when (m) {
        "reverse" -> codePoints(value).asReversed().joinToString("") { cpString(it) }
        "words" -> value.trim().split(Regex("\\s+")).asReversed().joinToString(" ")
        else -> {
            val delimiter = r.text.ifEmpty { "_" }; val parts = value.split(delimiter)
            val selected = order(r.replacement)
            require(selected.all { it <= parts.size }) { "片段序号超出名称实际片段数" }
            val out = StringBuilder()
            selected.forEachIndexed { i, part -> if (i > 0) append(out, delimiter); append(out, parts[part - 1]) }
            out.toString()
        }
    }
    private fun pad(value: String, r: Rule, m: String, budget: Budget): String {
        val unit = r.text.ifEmpty { "0" }
        fun padded(part: String, right: Boolean): String {
            val padding = unit.repeat((r.width - cpLength(part)).coerceAtLeast(0))
            return if (right) part + padding else padding + part
        }
        if (m != "numbers") return padded(value, m == "right")
        val out = StringBuilder(); var at = 0
        while (at < value.length) {
            budget.check()
            if (value[at] in '0'..'9') {
                val start = at
                while (at < value.length && value[at] in '0'..'9') at++
                append(out, padded(value.substring(start, at), false))
            } else {
                val cp = value.codePointAt(at); append(out, cpString(cp)); at += Character.charCount(cp)
            }
        }
        return out.toString()
    }
    private fun mappings(value: String): List<Pair<String, String>> {
        val lines = value.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.isNotEmpty() && lines.size <= 256) { "映射表须为1—256行，格式为原名=>新名" }
        return lines.map { line ->
            val at = line.indexOf("=>")
            require(at > 0) { "映射表每行须为非空原名=>新名" }
            line.substring(0, at) to line.substring(at + 2)
        }
    }
    private fun deaccent(value: String) = filter(Normalizer.normalize(value, Normalizer.Form.NFD)) {
        Character.getType(it) !in listOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())
    }
    private fun transliterate(value: String, r: Rule, m: String): String {
        if (m == "custom") {
            val to = codePoints(r.replacement); val map = codePoints(r.text).mapIndexed { i, cp -> cp to to[i] }.toMap()
            return codePoints(value).joinToString("") { cpString(map[it] ?: it) }
        }
        val latin = mapOf(0xdf to "ss", 0x1e9e to "SS", 0xe6 to "ae", 0xc6 to "AE", 0x153 to "oe", 0x152 to "OE",
            0xf8 to "o", 0xd8 to "O", 0x142 to "l", 0x141 to "L", 0xfe to "th", 0xde to "TH", 0xf0 to "d", 0xd0 to "D")
        return deaccent(codePoints(value).joinToString("") { latin[it] ?: cpString(it) })
    }
    private fun randomText(original: String, index: Int, modified: Long, size: Long, ordinal: Int, r: Rule): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in listOf(original, index.toString(), modified.toString(), size.toString(), ordinal.toString(), r.toString())) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            for (shift in listOf(24, 16, 8, 0)) digest.update((bytes.size ushr shift).toByte())
            digest.update(bytes)
        }
        val hash = digest.digest(); var seed = 0L
        for (i in 0..7) seed = (seed shl 8) xor (hash[i].toLong() and 255L)
        val random = Random(seed)
        val alphabet = codePoints(r.text.ifEmpty { "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" }).distinct()
        return (0 until if (r.count == 0) 8 else r.count).joinToString("") { cpString(alphabet[random.nextInt(alphabet.size)]) }
    }

    private fun checkTokens(value: String) { tokens(value) { token ->
        require(token in tokenNames) { "不支持元数据占位符：:$token:" }; ""
    } }
    /** :: escapes a colon; expanded token values are never scanned a second time. */
    private fun tokens(value: String, resolve: (String) -> String): String {
        val out = StringBuilder(); var at = 0
        while (at < value.length) {
            if (value[at] != ':') { append(out, value[at++].toString()); continue }
            if (at + 1 < value.length && value[at + 1] == ':') { append(out, ":"); at += 2; continue }
            val end = value.indexOf(':', at + 1)
            if (end < 0) {
                require(!value.substring(at + 1).trimStart().startsWith("File", true)) { "元数据占位符缺少结束冒号" }
                append(out, value.substring(at)); break
            }
            val token = value.substring(at + 1, end).trim().lowercase(Locale.ROOT)
            append(out, resolve(token)); at = end + 1
        }
        return out.toString()
    }
    private fun append(out: StringBuilder, value: String) {
        require(out.length.toLong() + value.length <= MAX_OUTPUT) { "规则结果过长（最多 $MAX_OUTPUT 字符）" }
        out.append(value)
    }
    private fun cpLength(value: String) = value.codePointCount(0, value.length)
    private fun cpSlice(value: String, start: Int, end: Int) = value.substring(value.offsetByCodePoints(0, start), value.offsetByCodePoints(0, end))
    private fun cpString(cp: Int) = String(Character.toChars(cp))
    private fun codePoints(value: String): List<Int> {
        val result = ArrayList<Int>(); var at = 0
        while (at < value.length) { val cp = value.codePointAt(at); result += cp; at += Character.charCount(cp) }
        return result
    }
    private fun filter(value: String, keep: (Int) -> Boolean): String {
        val out = StringBuilder()
        for (cp in codePoints(value)) if (keep(cp)) append(out, cpString(cp))
        return out.toString()
    }
    private fun validUnicode(value: String): Boolean {
        var at = 0
        while (at < value.length) {
            val ch = value[at++]
            if (Character.isHighSurrogate(ch)) {
                if (at >= value.length || !Character.isLowSurrogate(value[at++])) return false
            } else if (Character.isLowSurrogate(ch)) return false
        }
        return true
    }
    private class Budget {
        private var remaining = REGEX_NANOS
        private var deadline: Long? = null
        fun check() {
            require(!Thread.currentThread().isInterrupted && (deadline?.let { System.nanoTime() <= it } ?: true)) {
                "规则处理超时或已取消，请简化正则表达式"
            }
        }
        fun <T> runRegex(block: () -> T): T {
            check()
            require(remaining > 0) { "正则处理超时，请简化正则表达式" }
            val end = System.nanoTime() + remaining
            deadline = end
            return try { block() } finally {
                remaining = (end - System.nanoTime()).coerceAtLeast(0L)
                deadline = null
            }
        }
    }
    private class BudgetText(private val value: String, private val budget: Budget) : CharSequence {
        private var reads = 0
        private fun check() { if (++reads % 64 == 0) budget.check() }
        override val length: Int get() { check(); return value.length }
        override fun get(index: Int): Char { check(); return value[index] }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            check(); return BudgetText(value.substring(startIndex, endIndex), budget)
        }
        override fun toString(): String { check(); return value }
    }
}
