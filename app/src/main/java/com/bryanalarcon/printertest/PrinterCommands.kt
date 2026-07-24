package com.bryanalarcon.printertest

/**
 * The utility ZPL commands shared verbatim with the Mac sister tool
 * (mac_printer_tool/printer_conn.py). If a byte changes here it must change
 * there too - the whole point of the sister apps is that any print difference
 * between printers is attributable to the printer, never the tool.
 *
 * Test-content payloads do NOT live here anymore: the 49 code sets are loaded
 * at runtime from assets/code_sets/, which is a synced copy of the canonical
 * folder in mac_printer_tool/code_sets/ (see tools/sync_code_sets.py there).
 */

/** ZPL Print Start - resumes a printer paused by ^PP or ~JP. */
const val UNPAUSE_ZPL = "~PS"

/** ZPL Cancel All - flushes every buffered format. */
const val CANCEL_ALL_ZPL = "~JA"

/**
 * Feed-and-cut. An empty ^XA^XZ is discarded outright by the printer (nothing
 * to print, so no feed and no cut), so this prints the smallest possible real
 * label in cutter mode - a single 1x1-dot mark on a minimum-length label -
 * then restores the label length in a settings-only follow-up format. On a
 * cutterless printer (the DPP-450) it just feeds a tiny label.
 */
const val CUT_PAPER_ZPL = "^XA^MMC^LL32^FO16,8^GB1,1,1^FS^XZ^XA^LL1200^XZ"

/**
 * Media tracking (how the printer finds where one label ends) and print mode
 * (what happens after each label): UI label -> ZPL command. Same labels and
 * commands as the Mac tool's dropdowns.
 */
val MEDIA_TRACKING_MODES: Map<String, String> = linkedMapOf(
    "Continuous (receipt)" to "^MNN",
    "Gap/Notch labels" to "^MNY",
    "Black mark" to "^MNM",
)
val PRINT_MODES: Map<String, String> = linkedMapOf(
    "Cutter" to "^MMC",
    "Tear off" to "^MMT",
    "Peel off" to "^MMP",
    "Delayed cut" to "^MMD",
)

/**
 * Silent settings normalization. ZPL settings commands persist across labels
 * until changed or power-cycled, so tests like ^JMB (dot density), ^PMY
 * (mirror), ^PW300 (print width) and ^MU (units) otherwise corrupt every
 * following print.
 *
 * The leading tilde-commands restore the caret/delimiter/tilde control
 * characters first - they parse even if a test left the caret or delimiter
 * remapped (code sets 8 and 19 do exactly that). The format that follows
 * contains no printable fields, so nothing is printed.
 *
 * ^PW832 is the DPP-450's native 4-inch/203dpi width; the Zebra ZD421 clamps
 * it to its own 831-dot maximum, so the identical bytes are correct on both
 * printers (sister-app byte parity).
 *
 * The block re-asserts media tracking and print mode because they are
 * settings like everything else it resets - it must be rebuilt around the
 * UI's current dropdown selections or auto-restore would silently undo them
 * after the very next print.
 */
fun buildRestoreDefaults(mediaTracking: String = "^MNN", printMode: String = "^MMT"): String =
    "~CC^~CD,~CT~\n" +
        "^XA^JMA^PMN^LRN^FWN^PON^LH0,0^LS0^LT0^MUd^PW832^LL1200^MD0~SD15" +
        "^PR6,6,6^BY2,3,10^CI0" + mediaTracking + printMode + "^JZY^XZ"
