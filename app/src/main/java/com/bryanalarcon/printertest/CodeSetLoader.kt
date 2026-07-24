package com.bryanalarcon.printertest

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONObject

/**
 * One sendable test unit. Most sections are a single socket write; [writes]
 * with more than one entry are sent as separate writes spaced [gapMs] apart
 * (code set 18's paced ~JP/~JA sequences).
 */
data class ZplSection(
    val label: String,
    val writes: List<String>,
    val gapMs: Long = 0L
)

/** One numbered code set: display name, sections, and shared UI text. */
data class CodeSet(
    val name: String,
    val sections: List<ZplSection>,
    val number: Int? = null,
    val description: String? = null,
    val button: String? = null,
    // A MEDIA_TRACKING_MODES / PRINT_MODES key that this set's own payload
    // configures on the printer (e.g. code set 34 sends ^MNW). Sending the
    // set should sync the UI's mode selectors to match, so auto-restore
    // reasserts the mode the test just set instead of reverting it.
    val setsMedia: String? = null,
    val setsPrintMode: String? = null
)

/**
 * Loads the ZPL test content from assets/code_sets/ - a synced copy of the
 * canonical folder shared with the Mac sister tool (mac_printer_tool). Both
 * apps parse the identical on-disk convention, which is what guarantees they
 * send byte-identical ZPL:
 *
 *   NN_description.zpl          -> single-section code set
 *   NN_description/1_label.zpl  -> multi-section code set
 *   meta.json                   -> descriptions, button labels, section labels
 *
 * A line of exactly `#gap <ms>` splits a section into separate socket writes
 * with that delay between them. ONLY that exact form is a directive - other
 * lines starting with `#` are real payload (code set 11's ~DB font data
 * contains `#0025...` sub-descriptor lines), so there is no comment syntax.
 *
 * Every parsing rule here mirrors mac_printer_tool/code_sets.py line for
 * line; if one changes, the other must change identically.
 */
object CodeSetLoader {

    private const val ROOT = "code_sets"
    private val PREFIX = Regex("^(\\d+)_(.*)$")
    private val GAP = Regex("^#gap (\\d+)$")

    fun load(context: Context): List<CodeSet> {
        val assets = context.assets
        val entries = assets.list(ROOT) ?: return emptyList()
        val meta = loadMeta(assets)

        return entries
            .filter { PREFIX.matches(it) || PREFIX.matches(it.removeSuffix(".zpl")) }
            .sortedBy { PREFIX.find(it)?.groupValues?.get(1)?.toInt() ?: Int.MAX_VALUE }
            .mapNotNull { entry -> loadEntry(assets, entry, meta) }
    }

    private fun loadEntry(assets: AssetManager, entry: String, meta: JSONObject?): CodeSet? {
        val isFile = entry.endsWith(".zpl")
        val stem = if (isFile) entry.removeSuffix(".zpl") else entry
        val m = PREFIX.find(stem) ?: return null
        val number = m.groupValues[1].toInt()
        val name = "${number}. ${m.groupValues[2].replace('_', ' ')}"

        val setMeta = meta?.optJSONObject(number.toString())
        val sectionLabels = setMeta?.optJSONObject("sections")

        val sections: List<ZplSection> = if (isFile) {
            val (writes, gap) = parseSection(readAsset(assets, "$ROOT/$entry"))
            listOf(ZplSection(label = name, writes = writes, gapMs = gap))
        } else {
            val files = (assets.list("$ROOT/$entry") ?: return null)
                .filter { it.endsWith(".zpl") }
                .sortedBy { PREFIX.find(it.removeSuffix(".zpl"))?.groupValues?.get(1)?.toInt() ?: Int.MAX_VALUE }
            if (files.isEmpty()) return null
            files.map { f ->
                val secStem = f.removeSuffix(".zpl")
                val sm = PREFIX.find(secStem)
                val index = sm?.groupValues?.get(1) ?: secStem
                // meta.json override first; else "1_print_config" -> "Print config"
                // (first letter upper, the rest lower - identical to Python's
                // str.capitalize() used by the Mac loader).
                val label = sectionLabels?.optString(index)?.takeIf { it.isNotEmpty() }
                    ?: sm?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }
                        ?.replace('_', ' ')?.lowercase()
                        ?.replaceFirstChar { it.uppercase() }
                    ?: "Section $secStem"
                val (writes, gap) = parseSection(readAsset(assets, "$ROOT/$entry/$f"))
                ZplSection(label = label, writes = writes, gapMs = gap)
            }
        }

        return CodeSet(
            name = name,
            sections = sections,
            number = number,
            description = setMeta?.optString("description")?.takeIf { it.isNotEmpty() },
            button = setMeta?.optString("button")?.takeIf { it.isNotEmpty() },
            setsMedia = setMeta?.optString("sets_media")?.takeIf { it.isNotEmpty() },
            setsPrintMode = setMeta?.optString("sets_print_mode")?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * Splits file content on `#gap <ms>` directive lines. Writes are the line
     * runs between directives, joined with \n and without a trailing newline,
     * so the bytes sent do not depend on whether the file ends with a newline.
     */
    fun parseSection(raw: String): Pair<List<String>, Long> {
        val writes = mutableListOf<String>()
        var gapMs = 0L
        var current = mutableListOf<String>()
        for (rawLine in raw.split("\n")) {
            val line = rawLine.removeSuffix("\r")
            val m = GAP.matchEntire(line)
            if (m != null) {
                gapMs = m.groupValues[1].toLong()
                writes.add(current.joinToString("\n"))
                current = mutableListOf()
            } else {
                current.add(line)
            }
        }
        while (current.isNotEmpty() && current.last().isEmpty()) current.removeAt(current.size - 1)
        writes.add(current.joinToString("\n"))
        return writes.filter { it.isNotEmpty() } to gapMs
    }

    private fun loadMeta(assets: AssetManager): JSONObject? = try {
        JSONObject(readAsset(assets, "$ROOT/meta.json"))
    } catch (e: Exception) {
        null
    }

    private fun readAsset(assets: AssetManager, path: String): String =
        assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
