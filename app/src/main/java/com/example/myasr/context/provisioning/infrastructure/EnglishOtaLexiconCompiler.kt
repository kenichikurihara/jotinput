package com.example.myasr.context.provisioning.infrastructure

import com.example.myasr.context.english.domain.EnglishCaseModel
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.ln

/**
 * Pure-Kotlin streaming compiler and validator for OTA English vocabulary overlays
 * (`english_case_lexicon_ota.tsv`).
 *
 * Uses an O(1)-memory streaming JSON object reader (no Android `org.json` / `JsonReader`
 * dependencies so it runs identically under both Android runtime and JVM unit tests),
 * applying corpus admission guards consistent with `tools/build_english_case_model.py`:
 *   - Guard 1: Strip URLs/emails/package slugs, skip ALL-CAPS headlines and sentence-initial tokens
 *   - Guard 2: Minimum weighted evidence (`total >= 3.0f`)
 *   - Guard 5: Exclude possessives (`'s`), contractions, and internal class suffixes
 *   - Guard 6: Hard capacity cap (`MAX_OTA_ENTRIES = 15_000`)
 */
object EnglishOtaLexiconCompiler {

    const val MAX_OTA_ENTRIES = 15_000
    private const val MIN_EVIDENCE_TOTAL = 3.0f

    private val WORD_REGEX = Regex("^[A-Za-z][A-Za-z0-9'\\-]{1,28}$")
    private val ALPHA_ONLY_REGEX = Regex("^[A-Za-z]+$")
    private val URL_EMAIL_SLUG_REGEX = Regex(
        """https?://\S+|www\.\S+|[a-z0-9_.\-]+@[a-z0-9_.\-]+|\b[a-z0-9]+(?:-[a-z0-9]+){2,}\b"""
    )
    private val PROSE_TOKEN_REGEX = Regex("""(?<![/.\-_])\b([A-Za-z][A-Za-z0-9']{1,28})\b(?![/.\-_])""")
    private val SENTENCE_SPLIT_REGEX = Regex("""(?<=[.!?:;])\s+|\n+""")

    private val KOTLIN_IDENTIFIER_SUFFIXES = arrayOf(
        "Activity", "Service", "Manager", "Repository", "Engine", "Test",
        "Judgement", "Detector", "Processor", "Arbitrator", "Adapter",
        "Downloader", "Tracker", "View", "Model", "Policy", "Result",
        "Event", "Store", "Bridge", "Helper", "Dialog", "Fragment",
        "Factory", "Builder", "Parser", "Scorer", "Ranker", "Validator",
        "Cache", "State", "Action", "Params", "Config", "Utils",
        "Entry", "Node", "Token", "Candidate", "Candidates", "Table",
        "Spec", "Specs", "Report", "Mode", "Shape", "Variant", "Variants"
    )

    private val EXCLUDED_CONTRACTIONS = setOf(
        "i'm", "i've", "i'll", "i'd", "let's", "don't", "can't", "won't", "it's",
        "that's", "what's", "there's", "here's", "you're", "you've", "you'll", "you'd",
        "we're", "we've", "we'll", "we'd", "they're", "they've", "they'll", "they'd",
        "didn't", "doesn't", "isn't", "aren't", "wasn't", "weren't", "haven't",
        "hasn't", "hadn't", "wouldn't", "couldn't", "shouldn't", "mustn't", "ain't"
    )

    class HomebrewAccumulator {
        private val counts = HashMap<String, MutableMap<String, Float>>(4096)
        var objectsScanned: Int = 0
            private set

        fun ingestFormulaStream(stream: InputStream, onProgressObjects: ((Int) -> Unit)? = null) {
            streamHomebrewArray(stream) { name, caskNames, desc ->
                objectsScanned++
                if (!desc.isNullOrEmpty()) {
                    processProseText(desc)
                }
                if (!name.isNullOrEmpty() && ALPHA_ONLY_REGEX.matches(name) && name.length in 5..24) {
                    val low = name.lowercase()
                    if (low !in EXCLUDED_CONTRACTIONS &&
                        !EnglishCaseModel.isKnownLowerWord(low) &&
                        !endsWithCodeSuffix(name)
                    ) {
                        addSurfaceCount(low, name, 1.5f)
                    }
                }
                for (cn in caskNames) {
                    ingestCaskNameToken(cn)
                }
                if (objectsScanned % 500 == 0) {
                    onProgressObjects?.invoke(objectsScanned)
                }
            }
        }

        fun ingestCaskStream(stream: InputStream, onProgressObjects: ((Int) -> Unit)? = null) {
            streamHomebrewArray(stream) { name, caskNames, desc ->
                objectsScanned++
                if (!desc.isNullOrEmpty()) {
                    processProseText(desc)
                }
                if (!name.isNullOrEmpty()) {
                    ingestCaskNameToken(name)
                }
                for (cn in caskNames) {
                    ingestCaskNameToken(cn)
                }
                if (objectsScanned % 500 == 0) {
                    onProgressObjects?.invoke(objectsScanned)
                }
            }
        }

        private fun ingestCaskNameToken(rawName: String) {
            val tok = rawName.trim()
            if (tok.length in 4..24 &&
                !tok.contains(' ') &&
                WORD_REGEX.matches(tok) &&
                ALPHA_ONLY_REGEX.matches(tok) &&
                tok[0].isUpperCase() &&
                tok.any { it.isLowerCase() }
            ) {
                val low = tok.lowercase()
                if (low !in EXCLUDED_CONTRACTIONS && !endsWithCodeSuffix(tok)) {
                    if (isCamelOrMixed(tok) || !EnglishCaseModel.isKnownLowerWord(low)) {
                        addSurfaceCount(low, tok, 3.5f)
                    }
                }
            }
        }

        private fun processProseText(rawText: String) {
            val cleaned = URL_EMAIL_SLUG_REGEX.replace(rawText, " ").trim()
            if (cleaned.isEmpty()) return
            for (sent in SENTENCE_SPLIT_REGEX.split(cleaned)) {
                val toks = PROSE_TOKEN_REGEX.findAll(sent).map { it.groupValues[1] }.toList()
                if (toks.size >= 2) {
                    var alphaCount = 0
                    var upperCount = 0
                    for (t in toks) {
                        if (t.any { it.isLetter() }) {
                            alphaCount++
                            if (t.length > 1 && t.all { !it.isLetter() || it.isUpperCase() }) {
                                upperCount++
                            }
                        }
                    }
                    if (alphaCount >= 2 && upperCount.toFloat() / alphaCount.toFloat() > 0.40f) {
                        continue
                    }
                }

                for (idx in toks.indices) {
                    if (idx == 0) continue // Guard 1: skip sentence-initial capitalization bias
                    val tok = toks[idx]
                    val low = tok.lowercase()
                    if (low.length < 4 || low.length > 28) continue
                    if (low.endsWith("'s") || low in EXCLUDED_CONTRACTIONS) continue
                    if (!WORD_REGEX.matches(tok) || endsWithCodeSuffix(tok)) continue
                    // Guard 3: protect short bundled lowercase core words from OTA hijacking
                    if (low.length <= 4 && EnglishCaseModel.isKnownLowerWord(low)) continue
                    val weight = if (isCamelOrMixed(tok)) 2.0f else if (tok.any { it.isUpperCase() } && tok.any { it.isLowerCase() }) 1.5f else 1.0f
                    addSurfaceCount(low, tok, weight)
                }
            }
        }

        private fun addSurfaceCount(low: String, surf: String, weight: Float) {
            val surfMap = counts.getOrPut(low) { HashMap(4) }
            surfMap[surf] = (surfMap[surf] ?: 0.0f) + weight
        }

        fun writeToOtaTsvFile(outputFile: File, maxEntries: Int = MAX_OTA_ENTRIES): Int {
            data class Row(val key: String, val total: Float, val line: String)

            val rows = ArrayList<Row>(counts.size.coerceAtMost(maxEntries + 1024))
            for ((low, surfCounts) in counts) {
                if (low.length < 4 || low.length > 28) continue
                if (low.length <= 4 && EnglishCaseModel.isKnownLowerWord(low)) continue

                var total = 0.0f
                for (v in surfCounts.values) total += v
                if (total < MIN_EVIDENCE_TOTAL) continue

                val sortedSurfs = surfCounts.entries.sortedByDescending { it.value }
                val variants = ArrayList<Pair<String, Float>>(4)
                var hasNonLower = false
                for (entry in sortedSurfs.take(4)) {
                    val p = entry.value / total
                    if (p >= 0.03f) {
                        variants.add(entry.key to p)
                        if (entry.key != low) {
                            hasNonLower = true
                        }
                    }
                }
                if (variants.isEmpty()) continue
                if (!hasNonLower && EnglishCaseModel.isKnownWord(low)) continue

                var pSum = 0.0f
                for ((_, p) in variants) pSum += p
                if (pSum <= 0.0f) continue

                val logFreq = ln(1.0 + total.toDouble()).toFloat()
                val varStr = variants.joinToString(",") { (s, p) ->
                    "$s:${String.format(java.util.Locale.US, "%.4f", p / pSum)}"
                }
                val line = "$low\t${String.format(java.util.Locale.US, "%.3f", logFreq)}\t$varStr"
                rows.add(Row(low, total, line))
            }

            rows.sortWith(compareByDescending<Row> { it.total }.thenBy { it.key })
            val capped = if (rows.size > maxEntries) rows.subList(0, maxEntries) else rows
            val finalSorted = capped.sortedBy { it.key }

            writeLinesAtomically(outputFile, finalSorted.map { it.line })
            return finalSorted.size
        }
    }

    /**
     * Compiles Homebrew formula and cask JSON metadata streams directly into an OTA TSV overlay file.
     */
    fun compileFromHomebrewStreams(
        formulaStream: InputStream?,
        caskStream: InputStream?,
        outputFile: File,
        maxEntries: Int = MAX_OTA_ENTRIES,
        onProgressObjects: ((Int) -> Unit)? = null
    ): Int {
        val acc = HomebrewAccumulator()
        formulaStream?.let { acc.ingestFormulaStream(it, onProgressObjects) }
        caskStream?.let { acc.ingestCaskStream(it, onProgressObjects) }
        return acc.writeToOtaTsvFile(outputFile, maxEntries)
    }

    /**
     * Validates and atomically installs a pre-built 3-column TSV overlay (`key\tlogFreq\tvariants`).
     */
    fun installPrebuiltTsvStream(tsvStream: InputStream, outputFile: File, maxEntries: Int = MAX_OTA_ENTRIES): Int {
        val validLines = ArrayList<String>(1024)
        BufferedReader(InputStreamReader(tsvStream, Charsets.UTF_8)).useLines { lines ->
            for (raw in lines) {
                if (validLines.size >= maxEntries) break
                val trimmed = raw.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val parts = trimmed.split('\t')
                if (parts.size < 3) continue
                val key = parts[0].trim().lowercase()
                val lf = parts[1].toFloatOrNull() ?: continue
                if (key.length !in 2..28 || !lf.isFinite() || lf <= 0.0f) continue
                val variantsOk = parts[2].split(',').any { vp ->
                    val c = vp.lastIndexOf(':')
                    c > 0 && c < vp.length - 1 && (vp.substring(c + 1).toFloatOrNull() ?: 0f) > 0f
                }
                if (variantsOk) {
                    validLines.add("$key\t${parts[1].trim()}\t${parts[2].trim()}")
                }
            }
        }
        writeLinesAtomically(outputFile, validLines)
        return validLines.size
    }

    fun countValidEntries(file: File): Int {
        if (!file.isFile || file.length() == 0L) return 0
        return try {
            var count = 0
            BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    if (line.isNotEmpty() && !line.startsWith("#") && line.indexOf('\t') > 0) {
                        count++
                    }
                }
            }
            count
        } catch (_: Exception) {
            0
        }
    }

    private fun writeLinesAtomically(outputFile: File, lines: List<String>) {
        outputFile.parentFile?.mkdirs()
        val tmpFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
        tmpFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (line in lines) {
                writer.write(line)
                writer.newLine()
            }
        }
        if (outputFile.exists()) {
            outputFile.delete()
        }
        if (!tmpFile.renameTo(outputFile)) {
            tmpFile.copyTo(outputFile, overwrite = true)
            tmpFile.delete()
        }
    }

    private fun isCamelOrMixed(word: String): Boolean {
        return word.any { it.isLowerCase() } && word.drop(1).any { it.isUpperCase() }
    }

    private fun endsWithCodeSuffix(word: String): Boolean {
        for (suffix in KOTLIN_IDENTIFIER_SUFFIXES) {
            if (word.endsWith(suffix)) return true
        }
        return false
    }

    private class JsonScanner(reader: BufferedReader) {
        private val br = reader
        private var current: Int = br.read()

        fun peek(): Int = current

        fun advance(): Int {
            val prev = current
            current = br.read()
            return prev
        }

        fun skipWhitespace() {
            while (current != -1 && (current == ' '.code || current == '\n'.code || current == '\r'.code || current == '\t'.code)) {
                advance()
            }
        }

        fun readString(): String {
            if (current == '"'.code) advance()
            val sb = StringBuilder(32)
            while (current != -1) {
                val c = advance()
                if (c == '"'.code) {
                    return sb.toString()
                }
                if (c == '\\'.code) {
                    val esc = advance()
                    if (esc == -1) break
                    when (esc) {
                        '"'.code -> sb.append('"')
                        '\\'.code -> sb.append('\\')
                        '/'.code -> sb.append('/')
                        'b'.code -> sb.append('\b')
                        'f'.code -> sb.append('\u000C')
                        'n'.code -> sb.append('\n')
                        'r'.code -> sb.append('\r')
                        't'.code -> sb.append('\t')
                        'u'.code -> {
                            var codeUnit = 0
                            var ok = true
                            for (k in 0 until 4) {
                                val h = advance()
                                val d = when (h) {
                                    in '0'.code..'9'.code -> h - '0'.code
                                    in 'a'.code..'f'.code -> h - 'a'.code + 10
                                    in 'A'.code..'F'.code -> h - 'A'.code + 10
                                    else -> {
                                        ok = false
                                        0
                                    }
                                }
                                codeUnit = (codeUnit shl 4) or d
                            }
                            if (ok) sb.append(codeUnit.toChar())
                        }
                        else -> sb.append(esc.toChar())
                    }
                } else {
                    sb.append(c.toChar())
                }
            }
            return sb.toString()
        }

        fun skipValue() {
            skipWhitespace()
            when (current) {
                '"'.code -> {
                    advance()
                    while (current != -1) {
                        val c = advance()
                        if (c == '"'.code) return
                        if (c == '\\'.code) advance()
                    }
                }
                '{'.code -> {
                    advance()
                    while (current != -1) {
                        skipWhitespace()
                        if (current == '}'.code) {
                            advance()
                            return
                        }
                        if (current == ','.code) {
                            advance()
                            continue
                        }
                        if (current == '"'.code) {
                            skipValue()
                            skipWhitespace()
                            if (current == ':'.code) advance()
                            skipValue()
                        } else {
                            advance()
                        }
                    }
                }
                '['.code -> {
                    advance()
                    while (current != -1) {
                        skipWhitespace()
                        if (current == ']'.code) {
                            advance()
                            return
                        }
                        if (current == ','.code) {
                            advance()
                            continue
                        }
                        skipValue()
                    }
                }
                else -> {
                    while (current != -1 &&
                        current != ','.code &&
                        current != '}'.code &&
                        current != ']'.code &&
                        current != ' '.code &&
                        current != '\n'.code &&
                        current != '\r'.code &&
                        current != '\t'.code
                    ) {
                        advance()
                    }
                }
            }
        }
    }

    private fun streamHomebrewArray(
        stream: InputStream,
        onItem: (name: String?, caskNames: List<String>, desc: String?) -> Unit
    ) {
        val scanner = JsonScanner(BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 65536))
        scanner.skipWhitespace()
        if (scanner.peek() != '['.code) return
        scanner.advance()

        val caskNames = ArrayList<String>(4)
        while (scanner.peek() != -1) {
            scanner.skipWhitespace()
            when (scanner.peek()) {
                ']'.code -> {
                    scanner.advance()
                    return
                }
                ','.code -> {
                    scanner.advance()
                    continue
                }
                '{'.code -> {
                    scanner.advance()
                    var itemName: String? = null
                    var itemDesc: String? = null
                    caskNames.clear()

                    while (scanner.peek() != -1) {
                        scanner.skipWhitespace()
                        val ch = scanner.peek()
                        if (ch == '}'.code) {
                            scanner.advance()
                            break
                        }
                        if (ch == ','.code) {
                            scanner.advance()
                            continue
                        }
                        if (ch != '"'.code) {
                            scanner.advance()
                            continue
                        }
                        val key = scanner.readString()
                        scanner.skipWhitespace()
                        if (scanner.peek() == ':'.code) scanner.advance()
                        scanner.skipWhitespace()

                        when (key) {
                            "name" -> {
                                when (scanner.peek()) {
                                    '"'.code -> itemName = scanner.readString()
                                    '['.code -> {
                                        scanner.advance()
                                        while (scanner.peek() != -1) {
                                            scanner.skipWhitespace()
                                            when (scanner.peek()) {
                                                ']'.code -> {
                                                    scanner.advance()
                                                    break
                                                }
                                                ','.code -> scanner.advance()
                                                '"'.code -> caskNames.add(scanner.readString())
                                                else -> scanner.skipValue()
                                            }
                                        }
                                    }
                                    else -> scanner.skipValue()
                                }
                            }
                            "desc" -> {
                                if (scanner.peek() == '"'.code) {
                                    itemDesc = scanner.readString()
                                } else {
                                    scanner.skipValue()
                                }
                            }
                            else -> scanner.skipValue()
                        }
                    }

                    onItem(itemName, caskNames, itemDesc)
                }
                else -> scanner.skipValue()
            }
        }
    }
}
