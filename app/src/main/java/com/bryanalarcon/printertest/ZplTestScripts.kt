package com.bryanalarcon.printertest

/**
 * ZPL test payloads transcribed from IPC Mobile's "Code Sets for Testing DPP-450
 * ZPL Emulation" document (03-2024_Testing_CodeSets.pdf).
 *
 * The document groups each Code Set into sections that must be run INDEPENDENTLY
 * ("Do not try to run multiple sections for any Code Set"), so each section here is
 * a separate sendable payload. Ligature corruption from the PDF text extraction has
 * been repaired ("TesAng" -> "Testing", "leW" -> "left", etc.); the ZPL commands
 * themselves are kept as documented, including intentional oddities such as the
 * ^CC caret change, ^CD delimiter change, ~JA cancel-all, and ^FH hex escapes.
 *
 * KOTLIN NOTES FOR READING THIS FILE (written for a Swift developer):
 *
 * - This file has no class at the top level. Kotlin allows free standing
 *   properties and functions in a file, like top level lets in a Swift file.
 *   ALL_CODE_SETS at the bottom is just a global constant the UI reads.
 *
 * - Strings wrapped in triple quotes (three double-quote characters) are raw
 *   strings, Swift's #""" multiline strings. Backslashes and quotes inside them
 *   are literal, which matters because ZPL is full of characters that would
 *   otherwise need escaping. .trimIndent() strips the leading indentation that
 *   exists only to make the Kotlin source pretty; the printer receives the
 *   text starting flush left.
 *
 * - "$name" or "${expression}" inside ANY Kotlin string (raw ones included) is
 *   string interpolation, Swift's \(name). That is how $MISSION and $A3200 get
 *   spliced into payloads below.
 *
 * - "private val" limits visibility to this file. "val x = ..." infers the type
 *   from the right hand side, like Swift's let with type inference.
 *
 * - listOf(a, b, c) builds an immutable List, Swift's [a, b, c] as a let array.
 */
/**
 * One sendable test unit. Most sections are a single socket write; [writes] with
 * more than one entry are sent as separate writes spaced [gapMs] apart, for tests
 * where command timing/placement matters (e.g. probing the ~JP lockup in CS18).
 *
 * "data class" auto-generates equals, hashCode, toString and copy() from the
 * constructor properties, like a Swift struct's memberwise conveniences.
 * The properties are declared directly in the constructor parentheses; that is
 * the idiomatic Kotlin shorthand for "take these values and store them".
 * The secondary constructor lets the common case pass one string instead of a list.
 */
data class ZplSection(
    val label: String,
    val writes: List<String>,
    val gapMs: Long = 0L // default value, so two-argument calls are fine
) {
    constructor(label: String, zpl: String) : this(label, listOf(zpl))
}

/** ZPL Print Start - resumes a printer paused by ^PP or ~JP (code sets 13/18). */
const val UNPAUSE_ZPL = "~PS"

/**
 * ZPL Cancel All - flushes every buffered format. Recovery tool for a stuck
 * queue (e.g. the double ~JP lockup in code set 18): Clear queue, then Unpause.
 */
const val CANCEL_ALL_ZPL = "~JA"

/**
 * Silent settings normalization. ZPL settings commands persist across labels
 * until changed or power-cycled, so tests like ^JMB (dot density), ^PMY (mirror),
 * ^PW300 (print width) and ^MU (units) otherwise corrupt every following print.
 *
 * The leading tilde-commands restore the caret/delimiter/tilde control characters
 * first - they parse even if a test left the caret or delimiter remapped (CS8/CS19).
 * The format that follows contains no printable fields, so nothing is printed.
 * Deliberately excludes ~PS (would defeat the pause tests) and ~JA (would cancel
 * queued output).
 */
val RESTORE_DEFAULTS_ZPL = "~CC^~CD,~CT~\n" +
    "^XA^JMA^PMN^LRN^FWN^PON^LH0,0^LS0^LT0^MUd^PW832^LL1200^MD0~SD15^PR6,6,6^BY2,3,10^CI0^MNN^MMT^JZY^XZ"

/**
 * One numbered Code Set from the document: a display name plus its sections.
 * The secondary constructor covers the many single-section sets, wrapping the
 * lone payload in a section labeled "Send".
 */
data class CodeSet(val name: String, val sections: List<ZplSection>) {
    constructor(name: String, zpl: String) : this(name, listOf(ZplSection("Send", zpl)))
}

private const val MISSION =
    "Zebra Technologies Corporation strives to be the expert supplier of innovative " +
        "solutions to specialty demand labeling and ticketing problems of business and " +
        "government. We will attract and retain the best people who will understand our " +
        "customer's needs and provide them with systems, hardware, software, consumables " +
        "and service offering the best value, high quality, and reliable performance, " +
        "all delivered in a timely manner."

private val A3200 = "A".repeat(3200)
private val A1600 = "A".repeat(1600)
private val A320 = "A".repeat(320)

private val DG_TESTING = """
~DGR:Testing_DG.GRF,00080,010,
FFFFFFFFFFFFFFFFFFFF
8000FFFF0000FFFF0001
8000FFFF0000FFFF0001
8000FFFF0000FFFF0001
FFFF0000FFFF0000FFFF
FFFF0000FFFF0000FFFF
FFFF0000FFFF0000FFFF
FFFFFFFFFFFFFFFFFFFF
""".trimIndent()

private val DG_ID_DELETING = """
~DGR:ID_Deleting.GRF,00080,010,
FFFFFFFFFFFFFFFFFFFF
8000FFFF0000FFFF0001
8000FFFF0000FFFF0001
8000FFFF0000FFFF0001
FFFF0000FFFF0000FFFF
FFFF0000FFFF0000FFFF
FFFF0000FFFF0000FFFF
FFFFFFFFFFFFFFFFFFFF
""".trimIndent()

val ALL_CODE_SETS: List<CodeSet> = listOf(

    CodeSet(
        "1 - Barcodes 0 to 5",
        """
        ^XA^CFA,30,15^LL1200
        ^FX B0, Aztec Bar Code
        ^FO50,25^FDAztec Bar Code^FS
        ^FO50,50^B0N,6,N,2,N,1,0^FDTest Aztec Barcode^FS
        ^FX B1, Code 11 Bar Code
        ^FO50,200^FDCode 11 Bar Code^FS
        ^FO50,240^B1N,Y,50,Y,N^FD12345^FS
        ^FX B2, Interleaved 2 of 5 Bar Code
        ^FO50,400^FDInterleaved 2 of 5 Bar Code^FS
        ^FO50,440^B2N,50, Y, N,N^FD12345^FS
        ^FX B3, Code 39 Bar Code
        ^FO50,600^FDCode 39 Bar Code^FS
        ^FO50,640^B3N,Y,50,Y,N^FD123ABC^FS
        ^FX B4, Code 49 Bar Code
        ^FO50,785^FDCode 49 Bar Code^FS
        ^FO50,850^B4N,20,A,A^FD12345ABCDE^FS
        ^FX B5, Planet Code Bar Code
        ^FO50,1000^FDPlanet Code Bar Code^FS
        ^FO50,1040^B5N,50,Y,N^FD12345678901^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "2 - Barcodes 0 to 5 (with text using FB)",
        """
        ^XA^CFA,30,15^LL1200
        ^FX B0, Aztec Bar Code
        ^FO50,25^FB900^FDAztec Bar Code^FS
        ^FO50,50^B0N,6,N,2,N,1,0^FDTest Aztec Barcode^FS
        ^FX B1, Code 11 Bar Code
        ^FO50,200^FB900^FDCode 11 Bar Code^FS
        ^FO50,240^B1N,Y,50,Y,N^FD12345^FS
        ^FX B2, Interleaved 2 of 5 Bar Code
        ^FO50,400^FB900^FDInterleaved 2 of 5 Bar Code^FS
        ^FO50,440^B2N,50, Y, N,N^FD12345^FS
        ^FX B3, Code 39 Bar Code
        ^FO50,600^FB900^FDCode 39 Bar Code^FS
        ^FO50,640^B3N,Y,50,Y,N^FD123ABC^FS
        ^FX B4, Code 49 Bar Code
        ^FO50,785^FB900^FDCode 49 Bar Code^FS
        ^FO50,850^B4N,20,A,A^FD12345ABCDE^FS
        ^FX B5, Planet Code Bar Code
        ^FO50,1000^FB900^FDPlanet Code Bar Code^FS
        ^FO50,1040^B5N,50,Y,N^FD12345678901^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "3 - Barcodes 7 through C",
        """
        ^XA^CFA,30,15^LL1200
        ^FX B7, PDF417 Barcode
        ^FO50,25^FDPDF417 Barcode^FS
        ^FO50,60^B7N,3,5,,30,N^FDZebra Technologies Corporation strives to be the expert supplier of innovative solutions to speciality demand labeling and ticketing problems of business and government.^FS
        ^FX B8, EAN-8 Bar Code
        ^FO50,200^FDEAN-8 Bar Code^FS
        ^FO50,240^B8N,50,Y,N^FD1234567^FS
        ^FX B9, UPC-E Bar Code
        ^FO50,400^FDUPC-E Bar Code^FS
        ^FO75,440^B9N,50,Y,N,Y^FD0123453^FS
        ^FX BA, Code 93 Bar Code
        ^FO50,600^FDCode 93 Bar Code^FS
        ^FO50,640^BAN,50,Y,N,N^FD12345ABCDE^FS
        ^FX BB, Coda Block Bar Code
        ^FO50,800^FDCoda Block Bar Code^FS
        ^FO50,840^BBN,20,,25,44,E^FDZebra Technologies Corporation strives to be the expert supplier of innovative solutions to speciality demand labeling and ticketing problems of business and government^FS
        ^FX BC, Code 128 Bar Code
        ^FO50,1000^FDCode 128 Bar Code^FS
        ^FO50,1040^BCN,50,Y,N,N^FD123456^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "4 - Barcodes D through K",
        """
        ^XA^CFA,30,15^LL1200
        ^FX BD, UPS Maxicode Bar Code
        ^FO50,25^FDUPS Maxicode Bar Code ---->^FS
        ^FO550,25^BD^FH^FD001840152382802[)>_1E01_1D961Z00004951_1DUPSN_1D_06X610_1D159_1D1234567_1D1/1_1D_1DY_1D634 ALPHA DR_1DPITTSBURGH_1DPA_1E_04^FS
        ^FX BE, EAN-13 Bar Code
        ^FO50,200^FDEAN-13 Bar Code^FS
        ^FO70,240^BEN,50,Y,N^FD000012345678^FS
        ^FX BF, Micro PDF 417 Bar Code
        ^FO50,400^FDMicro PDF 417 Bar Code^FS
        ^FO50,440^BFN,6,3^FDABCDEFGHIJKLMNOPQRSTUV^FS
        ^FX BI, Industrial 2 of 5 Bar Code
        ^FO50,600^FDIndustrial 2 of 5 Bar Code^FS
        ^FO50,640^BIN,100,Y,N^FD123456^FS
        ^FX BJ, Standard 2 of 5 Bar Code
        ^FO50,800^FDStandard 2 of 5 Bar Code^FS
        ^FO50,840^BJN,50, Y,N^FD123456^FS
        ^FX BK, ANSI Codabar Bar Code
        ^FO50,1000^FDANSI Codabar Bar Code^FS
        ^FO50,1040^BKN,N,50,Y,N,A,A^FD123456^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "5 - Barcode Maxi Code only",
        """
        ^XA^CFA,30,15^LL600
        ^FX BD, UPS Maxicode Bar Code
        ^FO50,25^FDUPS Maxicode Bar Code^FS
        ^FO50,100^BD^FH^FD001840152382802[)>_1E01_1D961Z00004951_1DUPSN_1D_06X610_1D159_1D1234567_1D1/1_1D_1DY_1D634 ALPHA DR_1DPITTSBURGH_1DPA_1E_04^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "6 - Barcodes L through R",
        """
        ^XA^CFA,30,15^LL1200
        ^FX BL, LOGMARS Bar Code
        ^FO50,25^FDLOGMARS Bar Code^FS
        ^FO50,60^BLN,50,N^FD12AB^FS
        ^FX BM, MSI Bar Code
        ^FO50,200^FDMSI Bar Code^FS
        ^FO50,240^BMN,B,50,Y,N,N^FD123456^FS
        ^FX BO, Aztec Bar Code Parameters
        ^FO50,400^FDAztec Bar Code Parameters^FS
        ^FO50,440^BOR,7,N,1,N,1,0^FD 7.THis is a testing label 7^FS
        ^FX BP, Plessy Bar Code
        ^FO50,600^FDPlessy Bar Code^FS
        ^FO50,640^BPN,N,50,Y,N^FD12345^FS
        ^FX BQ, QR Code Bar Code
        ^FO50,800^FDQR Code Bar Code^FS
        ^FO50,830^BQN,2,6^FDMM,AAC-42^FS
        ^FX BR, RSS (Reduced Space Symbology) Bar Code
        ^FO50,1000^FDRSS (Reduced Space Symbology) Bar Code^FS
        ^FO50,1040^BRN,7,1,2,22^FD12345678901|this is composite info^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "7 - Barcodes S through Z",
        """
        ^XA^CFA,30,15^LL1200
        ^FX BS, UPC-EAN Extensions
        ^FO50,25^FDUPC-EAN Extensions^FS
        ^FO50,60^BSN,35,Y,N^FD12345^FS
        ^FX BT, TLC 39 Bar Code
        ^FO50,200^FDTLC 39 Bar Code^FS
        ^FO50,240^BT^FD123456, ABCd12345678901234, 5551212,88899^FS
        ^FX BU, UPC-A Bar Code
        ^FO50,400^FDUPC-A Bar Code^FS
        ^FO100,440^BUN,35^FD09000002198^FS
        ^FX BX, Data Matrix Bar Code
        ^FO50,600^FDData Matrix Bar Code^FS
        ^FO50,640^BXN,5,200^FDIPC Mobile Barcode Test^FS
        ^FX BZ, POSTNET Bar Code
        ^FO50,800^FDPOSTNET Bar Code^FS
        ^FO50,840^BZN,35,Y,N^FD12345^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "8 - Fonts 1",
        """
        ^XA^CFA30,15^LL1200
        ^FO50,50^FDCommand Usage of "A":^FS
        ^FO50,85^ABN,40,10^FD"A": Testing Scaling/Bitmapping^FS
        ^FO400,85^FDText Returned to Default^FS
        ^A@N,30,20,B:CYRI_UB.FNT
        ^FO50,200^FDZebra Printer Fonts^FS
        ^A@N,20,20
        ^FO50,230^FD"A@": the "B:CYRI_UB.FNT"^FS
        ^CC/
        /FO50,300/FD"CC": Just changed the carets in the code!!!/FS
        /CC^
        ^CD%
        ^FO50%400^FD"CD": Just changed the delimiter to percentage symbol!^FS
        ^CD,
        ^CFA,20,20
        ^FO50,450^FB700,4,,,^FDFONT A: ABCDEFGHIJKLMNOPQRST UVWXYZ0123456789^FS
        ^CFB,30,15
        ^FO50,500^FB700,4,,,^FDFONT B: ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789^FS
        ^CFC,25,10
        ^FO50,600^FB700,4,,,^FDFONT C: ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789^FS
        ^CFD,28,12
        ^FO50,650^FB700,4,,,^FDFONT D: ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789^FS
        ^CFE,40,9
        ^FO50,700^FB700,4,,,^FDFONT E: ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789^FS
        ^CFF,35,15
        ^FO50,775^FB700,4,,,^FDFONT F: ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789^FS
        ^CFG,20,10
        ^FO50,850^FB700,4,,,^FDFONT G: ABCDEF GHIJKLMNOPQRSTUVWXYZ 0123456 789^FS
        ^CFE,40,9
        ^FO50,1100^FB700,4,,,^FDFONT H: ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "9 - Fonts 2",
        """
        ^XA
        ^CFP,20,20
        ^FO50,50^FB700,4,,,^FDFONT P: ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789^FS
        ^CFQ,20,20
        ^FO50,100^FB700,4,,,^FDFONT Q: ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789^FS
        ^CFR,20,20
        ^FO50,150^FB700,4,,,^FDFONT R: ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789^FS
        ^CFS,20,20
        ^FO50,200^FB700,4,,,^FDFONT S: ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789^FS
        ^CFT,20,20
        ^FO50,300^FB700,4,,,^FDFONT T: ABCDEFGHIJKLMNOPQRSTUVWX YZ0123456789^FS
        ^CFU,20,20
        ^FO50,450^FB700,4,,,^FDFONT U: ABCDEFGHIJKLMNOPQR STUVWXYZ0123456789^FS
        ^CFV,20,20
        ^FO50,600^FB700,4,,,^FDFONT V: ABCDEFGHIJKL MNOPQRSTUVWXYZ0123456789^FS
        ^CF0,20,20
        ^FO50,850^FB700,4,,,^FDFONT 0: ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "10 - Position and Shapes",
        """
        ^XA^PMN^FWN^PON^LL900
        ^FX Reversing Colors
        ^LRY^MD30
        ^FO50,50^GB195,203,195^FS
        ^FO130,110^FWN^CFA,70^FDLABEL^FS
        ^FO80,170^FWN^FDREVERSE^FS
        ^FO600,50^FWR,0^FD90 Deg Turn^FS
        ^FO150,300^FWB,1^FD270 Deg Turn^FS
        ^FO200,800^FWI,0^FDUpside Down^FS^FWN
        ^PQ2,0,0,N,N
        ^FO200,300^GB100,175,10,B,1^FS
        ^FO175,300^GD400,400,5,B,R^FS
        ^FO175,300^GD400,400,5,B,L^FS
        ^FO350,500^GC200,10,B^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "11 - Downloads (DB/DE/DF/DG)",
        listOf(
            ZplSection(
                "Section 1: download font, encoding, format, graphic",
                """
                ~DBR:Testing_DB_Command.FNT,N,5,24,3,10,2,ZEBRA 1992,
                #0025.5.16.2.5.18.
                OOFF
                OOFF
                FFOO
                FFOO
                FFFF
                #0037.4.24.3.6.26.
                OOFFOO
                OFOOFO
                OFOOFO
                OOFFOO
                ~DER:Testing_DE.DAT,2,F00F
                ^XA
                ^DFR:Testing_DF.ZPL^FS
                ^FO25,25^AD,36,20^FN1^FS
                ^FO165,25^AD,36,20^FN2^FS
                ^FO25,75^AB,22,14^FDBUILT BY - "DF" Command^FS
                ^FO25,125^AE,28,15^FN1
                ^XZ
                """.trimIndent() + "\n" + DG_TESTING
            ),
            ZplSection(
                "Section 2: use downloads (CT/CV/XF/XG/GS)",
                DG_ID_DELETING + "\n" + """
                ^XA
                ^CFA,30,15
                ^LL1200
                ~CT=^FO50,200^FDChanged the ~ to the equal sign in code^FS=CT~
                ^CVY^FO50,300^BEN,100,Y,N^FD97823456 890^FS^CVN
                ^FO225,300^FD<---Testing Code Validation (CV)^FS
                ^XFR:Testing_DF.ZPL^FS
                ^FN1^FDZEBRA^FS
                ^FO50,450^FDUsing "DG" Command:^FS
                ^FO50,480^XGR:Testing_DG.GRF,8,10^FS
                ^FO50,600^GSR,70,70^FDA^FS
                ^FO150,600^GSN,70,70^FDB^FS
                ^FO250,600^GSI,70,70^FDC^FS
                ^FO350,600^GSB,70,70^FDD^FS
                ^FO450,600^GSN,70,70^FDE^FS
                ^XZ
                """.trimIndent()
            )
        )
    ),

    CodeSet(
        "12 - Image Save/Load/Move/Delete (IS/IL/IM/ID)",
        listOf(
            ZplSection(
                "Section 1: print label and save as image (IS)",
                DG_ID_DELETING + "\n" + """
                ^XA
                ^LH10,15^FWN^BY3,3,85^CFD,36
                ^GB430,750,4^FS
                ^FO10,170^GB200,144,2^FS
                ^FO10,318^GB410,174,2^FS
                ^FO212,170^GB206,144,2^FS
                ^FO10,498^GB200,120,2^FSR
                ^FO212,498^GB209,120,2^FS
                ^FO4,150^GB422,10,10^FS
                ^FO135,20^A0,70,60^FDZEBRA^FS
                ^FO80,100^A0,40,30^FDTECHNOLOGIES CORP^FS
                ^FO15,180^CFD,18,10^FS^FDARTICLE#^FS
                ^FO218,180^FDLOCATION^FS
                ^FO15,328^FDDESCRIPTION^FS
                ^FO15,508^FDREQ.NO.^FS
                ^FO220,508^FDWORK NUMBER^FS
                ^FO15,630^AD,36,20^FDCOMMENTS:^FS
                ^ISR:IS_Example.GRF,Y
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 2: load/move/delete image (IL/IM/ID/HW)",
                """
                ^XA^CFA,30,15^LL1200
                ^ILR:IS_Example.GRF
                ^FO200,200^IMR:IS_Example.GRF.GRF^FS
                ^IDR:ID_Deleting.GRF
                ^HWR:*.*
                ^XZ
                """.trimIndent()
            )
        )
    ),

    CodeSet(
        "13 - Cancel All (~JA) and Pause (^PP)",
        listOf(
            ZplSection(
                "Section 1: ~JA cancel test (neither label should print; printer pauses - use Unpause)",
                """
                ^XA
                ^CFA,30,15^LL200
                ^FO50,50^FDThis should not print at all due to JA.^FS
                ^XZ
                ^XA
                ^CFA,30,15^LL200
                ^FO50,50^FDThis should not have printed^FS
                ~JA
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 2: ^PP pause test (second label prints after tapping Unpause)",
                """
                ^XA
                ^CFA,30,15^LL200
                ^FO50,50^FDThis should be the first print.^FS
                ^PP
                ^XZ
                ^XA
                ^CFA,30,15^LL200
                ^FO50,50^FDThis should print after the pause.^FS
                ^XZ
                """.trimIndent()
            )
        )
    ),

    CodeSet(
        "14 - Diagnostic Mode (run twice)",
        """
        ^FX You will need to hit run on this program twice
        ^XA
        ^CFA,30,15^LL600
        ^FX Checking Diagnostics(Noise)~JD
        ^FX Disabling Diagnostics(Print)~JE
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "15 - Sensor Calibration (~JG)",
        """
        ^XA^CFA,30,15^LL1200
        ~JG
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "16 - Error Handling (^JJ) - interrupt the print",
        """
        ^FX Try to interrupt the printing and check if it will print again
        ^XA^CFA,30,15^LL600
        ^JJ1,,,e,e^JZY
        ^FO50,500^FDTHis is testing the JJ command of the device^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "17 - Number of Dots (^JM)",
        listOf(
            ZplSection(
                "Section 1: ^JMA (size A)",
                """
                ^XA^CFA,50,15^LL200
                ^FX Testing JM Command^JMA
                ^FO50,50^FDSize A^FS
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 2: ^JMB (size B)",
                """
                ^XA^CFA,50,15^LL200
                ^FX Testing JM Command^JMB
                ^FO50,50^FDSize B^FS
                ^XZ
                """.trimIndent()
            )
        )
    ),

    // VERDICT (tested on hardware 2026-07-02): ~JP hard-locks the DPP-450 in every
    // form - embedded in a format, standalone, paced, even followed by ~PS. Firmware
    // bug in the ZPL emulation; only a power cycle recovers (printer is otherwise
    // unaffected). Production must use ~JA for queue clearing, verified working below.
    CodeSet(
        "18 - Print Queue clearing (~JP broken on DPP-450 - use ~JA)",
        listOf(
            ZplSection(
                "RECOMMENDED: ~JA-based queue clear (verified working)",
                listOf(
                    "^XA\n^CFA,30,15^LL200\n^FO50,50^FDThis line should not print.^FS\n^XZ\n~JA",
                    "^XA\n^CFA,30,15^LL200\n^FO50,50^FDThis is the first line print.^FS\n^XZ",
                    "^XA\n^CFA,30,15^LL200\n^FO50,50^FDThis is second line print.^FS\n^XZ"
                ),
                gapMs = 300L
            ),
            ZplSection(
                "Probe: standalone paced ~JP + auto-resume - CONFIRMED: locks the printer",
                listOf(
                    "^XA\n^CFA,30,15^LL200\n^FO50,50^FDThis line should not print.^FS\n^XZ",
                    "~JP",
                    "~PS",
                    "^XA\n^CFA,30,15^LL200\n^FO50,50^FDThis is the first line print.^FS\n^XZ",
                    "~JP",
                    "~PS",
                    "^XA\n^CFA,30,15^LL200\n^FO50,50^FDThis is second line print.^FS\n^XZ"
                ),
                gapMs = 400L
            ),
            ZplSection(
                "Original document version - CONFIRMED: hard-locks, power cycle required",
                """
                ^XA
                ^CFA,30,15^LL200
                ^FO50,50^FDThis line should not print.^FS
                ^XZ
                ^XA
                ^CFA,30,15^LL200
                ~JP
                ^XZ
                ^FO50,50^FDThis is the first line print.^FS
                ^XA
                ^CFA,30,15^LL200
                ~JP
                ^XZ
                ^FO50,50^FDThis is second line print.^FS
                """.trimIndent()
            )
        )
    ),

    CodeSet(
        "19 - Saving Settings (^JUS/^JUF/^JUR)",
        listOf(
            ZplSection(
                "Section 1: semicolon delimiter + save settings (^JUS)",
                """
                ~CD;
                ^XA
                ^CFA;30;15^LL300^MMC,N^MNN
                ^FO50;50^FDThis was set with a semi colon delimiter^FS
                ^JUS
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 2: factory reset (^JUF)",
                """
                ^XA
                ^JUF
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 3: print after factory reset (comma delimiter back)",
                """
                ^XA
                ^CFA,30,15^LL300^MMC,N^MNN
                ^FO50,50^FB900,3^FDThis is the output when factory reset went through.  Delimiter went back to a comma.^FS
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 4: restore saved settings (^JUR)",
                """
                ^XA
                ^JUR^CFA;30;15^LL300^MMC,N^MNN
                ^FO50;50^FDSaved settings resotored.^FS~CD,
                ^XZ
                """.trimIndent()
            )
        )
    ),

    CodeSet(
        "20 - Name Changing (^KN)",
        listOf(
            ZplSection(
                "Section 1: set name + ^JST",
                """
                ^XA
                ^MNN
                ^KNZTC ZD421-203dpi ZPL,D8N230700196
                ^JST
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 2: print configuration (~WC)",
                """
                ^XA
                ~WC
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 3: set name 2 + ^JSR",
                """
                ^XA
                ^KNIPCQC_Zebra_Printer,Zebra_Printer^JSR
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 4: print configuration again (~WC)",
                """
                ^XA
                ~WC
                ^XZ
                """.trimIndent()
            )
        )
    ),

    CodeSet(
        "21 - Label Home (^LH)",
        listOf(
            ZplSection(
                "Section 1: ^LH0,0",
                """
                ^XA
                ^CFA,30,15^LL400
                ^LH0,0
                ^FO50,50^FDThis is LH0,0!^FS
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 2: ^LH100,100",
                """
                ^XA
                ^CFA,30,15^LL400
                ^LH100,100
                ^FO50,50^FDThis is LH100,100!^FS
                ^XZ
                """.trimIndent()
            )
        )
    ),

    CodeSet(
        "22 - Label Shifting (^LS/^LT)",
        listOf(
            ZplSection(
                "Section 1: ^LS0 ^LT0",
                """
                ^XA
                ^LS0^LT0
                ^XZ
                ^XA
                ^CFA,30,15^MMC,N^MNN^LL400
                ^LS0^LT0
                ^FO50,50^FDFor this, 0 was applied to LS and LT^FS
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 2: ^LS200 ^LT200",
                """
                ^XA
                ^LS200^LT200
                ^XZ
                ^XA
                ^CFA,30,15^MMC,N^MNN^LL400
                ^LS200^LT200
                ^FO50,50^FDFor this, 200 was applied to LS and LT^FS
                ^XZ
                """.trimIndent()
            )
        )
    ),

    CodeSet(
        "23 - Media Darkness (^MD)",
        listOf(
            ZplSection(
                "Section 1: ^MD-30",
                """
                ^XA
                ^MD-30
                ^XZ
                ^XA
                ^CFA,30,15^LL400
                ^MD-30
                ^FO50,50^FDThis is darkness -30^FS
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 2: ^MD30",
                """
                ^XA
                ^MD30
                ^XZ
                ^XA
                ^CFA,30,15^LL400
                ^MD30
                ^FO50,50^FDThis is darkness +30^FS
                ^XZ
                """.trimIndent()
            )
        )
    ),

    CodeSet(
        "24 - Next Empty Page (^PH)",
        """
        ^XA
        ^CFA,30,15^LL600
        ^PH
        ^FO50,50^FDTesting PH. Next page is blank!^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "25 - Printing Speeds (^PR; no printed output expected)",
        listOf(
            ZplSection(
                "Section 1: ^PR12,12,12 (fast)",
                """
                ^XA
                ^CFA,30,15^LL600
                ^PQ3,0,0,N
                ^PF400^PH^PR12,12,12
                ^FO50,50^FDTesting PH. Next page is blank!^FS
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 2: ^PR1,1,1 (slow)",
                """
                ^XA
                ^CFA,30,15^LL600
                ^PQ3,0,0,N
                ^PF400^PH^PR1,1,1
                ^FO50,50^FDTesting PH. Next page is blank!^FS
                ^XZ
                """.trimIndent()
            )
        )
    ),

    CodeSet(
        "26 - Print Width (^PW)",
        """
        ^XA
        ^CFA,30,15^LL300
        ^PW300
        ^FO50,50^FDThis is PW300........^FS
        ^XZ
        """.trimIndent()
    ),

    // ~RO1 itself prints nothing; the document's reference photos verify the reset
    // with printer-configuration printouts (~WC) before and after.
    CodeSet(
        "27 - Counter Reset (~RO) - DPP-450 config lists no counters; unverifiable on this printer",
        listOf(
            ZplSection(
                "Section 1: print config BEFORE (~WC) - note the counter values",
                """
                ^XA
                ~WC
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 2: reset counter (~RO1; no output)",
                """
                ^XA
                ~RO1
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 3: print config AFTER (~WC) - counters should be reset",
                """
                ^XA
                ~WC
                ^XZ
                """.trimIndent()
            )
        )
    ),

    CodeSet(
        "28 - Set Darkness (~SD)",
        listOf(
            ZplSection(
                "Section 1: ~SD0",
                """
                ^XA
                ~SD0
                ^XZ
                ^XA
                ^CFA,30,15^LL600
                ~SD0
                ^FO50,50^FDThis is SD0^FS
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 2: ~SD30",
                """
                ^XA
                ~SD30
                ^XZ
                ^XA
                ^CFA,30,15^LL600
                ~SD30
                ^FO50,50^FDThis is SD30^FS
                ^XZ
                """.trimIndent()
            )
        )
    ),

    CodeSet(
        "29 - Serialization (^SF)",
        """
        ^XA
        ^LL300^CF0,100^PQ3
        ^FO100,100^FD12A^SFnnA,F^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "30 - Serialization Number (^SN)",
        """
        ^XA
        ^LL300^CF0,50^PQ3
        ^FO50,50^SN001,1,Y^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "31 - Shipping Label",
        """
        ^XA^PMN^PON^FWN
        ^FX Top section with logo, name and address.
        ^CFA,50^LL1200
        ^FO50,50^GB100,100,100^FS
        ^FO75,75^FR^GB100,100,100^FS
        ^FO93,93^GB40,40,40^FS
        ^FO220,50^FDIPC Mobile, Inc.^FS
        ^CFA,30
        ^FO220,115^FD17681 Mitchell N^FS
        ^FO220,155^FDIrvine CA 92614^FS
        ^FO220,195^FDUnited States (USA)^FS
        ^FO50,250^GB700,3,3^FS
        ^FX Second section with recipient address and permit information.
        ^CFA,30
        ^FO50,300^FDCesar^FS
        ^FO50,340^FD1315 10th St^FS
        ^FO50,380^FDSacramento CA 95184^FS
        ^FO50,420^FDUnited States (USA)^FS
        ^CFA,15
        ^FO600,300^GB150,150,3^FS
        ^FO638,340^FDPermit^FS
        ^FO638,390^FD123456^FS
        ^FO50,500^GB700,3,3^FS
        ^FX Third section with bar code.
        ^BY5,2,270
        ^FO100,550^BC^FD12345678^FS
        ^FX Fourth section (the two boxes on the bottom).
        ^FO50,900^GB700,250,3^FS
        ^FO400,900^GB3,250,3^FS
        ^CFA,40
        ^FO100,960^FDCtr. X34B-1^FS
        ^FO100,1010^FDREF1 F00B47^FS
        ^FO100,1060^FDREF2 BL4H8^FS
        ^CFA,190
        ^FO470,955^FDCA^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "32 - Shipping Label Reversed (^PMY)",
        """
        ^XA^PMY^PON^FWN
        ^FX Top section with logo, name and address.
        ^CFA,50^LL1200
        ^FO50,50^GB100,100,100^FS
        ^FO75,75^FR^GB100,100,100^FS
        ^FO93,93^GB40,40,40^FS
        ^FO220,50^FDIPC Mobile, Inc.^FS
        ^CFA,30
        ^FO220,115^FD17681 Mitchell N^FS
        ^FO220,155^FDIrvine CA 92614^FS
        ^FO220,195^FDUnited States (USA)^FS
        ^FO50,250^GB700,3,3^FS
        ^FX Second section with recipient address and permit information.
        ^CFA,30
        ^FO50,300^FDCesar^FS
        ^FO50,340^FD1315 10th St^FS
        ^FO50,380^FDSacramento CA 95184^FS
        ^FO50,420^FDUnited States (USA)^FS
        ^CFA,15
        ^FO600,300^GB150,150,3^FS
        ^FO638,340^FDPermit^FS
        ^FO638,390^FD123456^FS
        ^FO50,500^GB700,3,3^FS
        ^FX Third section with bar code.
        ^BY5,2,270
        ^FO100,550^BC^FD12345678^FS
        ^FX Fourth section (the two boxes on the bottom).
        ^FO50,900^GB700,250,3^FS
        ^FO400,900^GB3,250,3^FS
        ^CFA,40
        ^FO100,960^FDCtr. X34B-1^FS
        ^FO100,1010^FDREF1 F00B47^FS
        ^FO100,1060^FDREF2 BL4H8^FS
        ^CFA,190
        ^FO470,955^FDCA^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "33 - Field Block / FC / FH / FM / FP / FT / FV / MC",
        listOf(
            ZplSection(
                "Section 1: full field feature test",
                """
                ^XA^CFA,30,15^LL1200
                ^FO50,50^FB500,5,,,^FDThis is testing the field block feature that wraps the text to stay within certain bounds of the label/receipt.^FS
                ^FO50,250^FDThis one does not have the field block, thus it does not wrap^FS
                ^FC%,{,#
                ^FH[
                ^FO50,300^FDDate: %m/%d/%y[09Time: %I:%M:%S %p^FS
                ^FO50,375^FDTesting "FM" Bellow^FS
                ^FM50,400, 50,450
                ^BY2,2
                ^B7N,1,1,9,30,N
                ^FD$MISSION $MISSION^FS
                ^FO700,400^FPV,10
                ^FDVertical Text using "FP"l^FS
                ^FO550,500^FPR,10
                ^FDReversing with "FP"^FS
                ^FO50,525^FPH,10
                ^FDRegular again^FS
                ^PR1
                ^FO100,600^GB70,70,70,,3^FS
                ^FO200,600^GB70,70,70,,3^FS
                ^FO300,600^GB70,70,70,,3^FS
                ^FO400,600^GB70,70,70,,3^FS
                ^FO107,610^A0,70,93^FR^FDREVERSE^FS
                ^FT50,750^A0N,30,20^FDThis ^FS
                ^FT^A0N,40,25^FDis a ^FS
                ^FT^A0N,50,30^FDtest using ^FS
                ^FT^A0N,60,35^FD"FT"^FS
                ^FO50,800^GB300,203,8^FS
                ^FO60,820^A0,25^FVVARIABLE DATA #1^FS
                ^FO100,900^FDFIXED DATA^FS
                ^MCN
                ^FO60,820^A0,25^FVVARIABLE DATA #2^FS
                ^MCY
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 2: same with ^MMC,N ^MNN (no FM/B7)",
                """
                ^XA^CFA,30,15^LL1200^MMC,N^MNN
                ^FO50,50^FB500,5,,,^FDThis is testing the field block feature that wraps the text to stay within certain bounds of the label/receipt.^FS
                ^FO50,250^FDThis one does not have the field block, thus it does not wrap^FS
                ^FC%,{,#
                ^FH[
                ^FO50,300^FDDate: %m/%d/%y[09Time: %I:%M:%S %p^FS
                ^FO500,550^FPV,10
                ^FDVertical "FP"l^FS
                ^FO550,500^FPR,10
                ^FDReversing with "FP"^FS
                ^FO50,525^FPH,10
                ^FDRegular again^FS
                ^PR1
                ^FO100,600^GB70,70,70,,3^FS
                ^FO200,600^GB70,70,70,,3^FS
                ^FO300,600^GB70,70,70,,3^FS
                ^FO400,600^GB70,70,70,,3^FS
                ^FO107,610^A0,70,93^FR^FDREVERSE^FS
                ^FT50,750^A0N,30,20^FDThis ^FS
                ^FT^A0N,40,25^FDis a ^FS
                ^FT^A0N,50,30^FDtest using ^FS
                ^FT^A0N,60,35^FD"FT"^FS
                ^FO50,800^GB300,203,8^FS
                ^FO60,820^A0,25^FVVARIABLE DATA #1^FS
                ^FO100,900^FDFIXED DATA^FS
                ^MCN
                ^FO60,820^A0,25^FVVARIABLE DATA #2^FS
                ^MCY
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 3: FM/B7 only",
                """
                ^XA^CFA,30,15^LL1200^MMC,N^MNN
                ^FO50,375^FDTesting "FM" Bellow^FS
                ^FM50,400, 50,450
                ^BY2,2^B7N,1,1,9,30,N
                ^FD$MISSION $MISSION^FS
                ^XZ
                """.trimIndent()
            )
        )
    ),

    CodeSet(
        "34 - Label Printing (label sensor; no printed output expected)",
        """
        ^XA
        ^CFA,30,15^LL400
        ^JST^MNW^MMC
        ^FO50,50^FB400,3^FDTesting the label sensor functionality!^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "35 - Marked Labels (reflective sensor; no printed output expected)",
        """
        ^XA
        ^CFA,30,15
        ^JSR^MNM^MMC
        ^FO200,50^FB400,3^FDTesting the reflective sensor functionality!^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "36 - Change International Font / FB Justification (^CI)",
        """
        ^XA
        ^CFA,30,15^LL1200
        ^CI2, 21, 36
        ^FO0,50^FDChanged money to euro: $^FS
        ^FO0,100^FH^FDTilde _7e was used for HEX^FS
        ^FO0,200^FB600,3,0,L,0^FDTesting FB command's left justification^FS
        ^FO0,275^FB600,3,0,L,50^FDTesting FB command's left justification with indentation^FS
        ^FO0,450^^FB600,3,0,C,0^FDTesting FB command's center justification^FS
        ^FO0,525^^FB600,3,0,C,50^FDTesting FB command's  center justification with indentation^FS
        ^FO0,700^^FB600,3,0,R,50^FDTesting FB command's Right justification ^FS
        ^FO0,775^^FB600,3,0,R,50^FDTesting FB command's Right justification with indentation^FS
        ^FO0,950^^FB600,3,0,J,50^FDTesting FB command's Justified justification ^FS
        ^FO0,1025^^FB600,3,0,J,50^FDTesting FB command's Justified justification with indentation^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "37 - Mode Units (^MU dots/mm/inches)",
        listOf(
            ZplSection(
                "Section 1: ^MUd (dots)",
                """
                ^XA
                ^MUd^LL500
                ^XZ
                ^XA
                ^FO50,50^GB256,128,128^FS
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 2: ^MUm (millimeters)",
                """
                ^XA
                ^MUm^LL62.2808
                ^XZ
                ^XA
                ^FO6.25,6.25^GB32,16,16^FS
                ^XZ
                """.trimIndent()
            ),
            ZplSection(
                "Section 3: ^MUi (inches)",
                """
                ^XA
                ^MUi^LL2.452
                ^XZ
                ^XA
                ^FO.2465,.2465^GB1.216,.631,.631^FS
                ^XZ
                """.trimIndent()
            )
        )
    ),

    CodeSet(
        "38 - Head Diagnostics (~HD)",
        """
        ^XA
        ^FX Testing HD (Head Diagnostics)~HD
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "39 - Host Graphic (^HG)",
        """
        ^XA
        ^FX Testing HG (Host Graphic)^HGR:Testing_DG.GRF
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "40 - Configuration Information (^HH)",
        """
        ^XA
        ^FX Testing HH (Configuration Information)^HH
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "41 - Host Identification (~HI)",
        """
        ^XA
        ^FX Testing HI (Host Identification)~HI
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "42 - Home RAM Status (~HM)",
        """
        ^XA
        ^FX Testing HM (Home RAM Status)
        ~HM
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "43 - Host Status Return (~HS)",
        """
        ^XA
        ^FX Testing HS (Host Status Return)~HS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "44 - ZebraNet Alert Configuration (~HU)",
        """
        ^XA
        ^FX Testing HU (Return ZebraNet Alert Configuration)~HU
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "45 - Host Directory List (^HW)",
        """
        ^XA
        ^FX Testing HW (Host Directory List)^HWR:*.*
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "46 - Upload Graphics (^HY)",
        """
        ^XA
        ^FX Testing HY (Upload Graphics)^HYR:Testing_DG.GRF
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "47 - ^FD Character Limit",
        listOf(
            ZplSection(
                "Long format (3200 + 1600 chars) - ^CF0",
                "^XA^CF0,50,25^LL4000^FO50,50^FB700,1000^FD$A3200^FS\n" +
                    "^FO50,3600^FB700,1000^FD$A1600^FS^XZ"
            ),
            ZplSection(
                "Long format (3200 + 1600 chars) - ^CFA",
                "^XA^CFA,50,25^LL4000^FO50,50^FB700,1000^FD$A3200^FS\n" +
                    "^FO50,3600^FB700,1000^FD$A1600^FS^XZ"
            ),
            ZplSection(
                "Short format (320 chars) - ^CF0",
                "^XA^CF0,50,25^LL4000^FO50,50^FB700,1000^FD$A320^FS^XZ"
            ),
            ZplSection(
                "Short format (320 chars) - ^CFA",
                "^XA^CFA,50,25^LL4000^FO50,50^FB700,1000^FD$A320^FS^XZ"
            )
        )
    ),

    CodeSet(
        "48 - Graphic Boxes (^GB corner rounding)",
        """
        ^XA^LL1200
        ^FO20,50^GB250,100,5,B,0^FS
        ^FO20,250^GB250,100,5,B,2^FS
        ^FO20,450^GB250,100,5,B,4^FS
        ^FO20,650^GB250,100,5,B,6^FS
        ^FO20,850^GB250,100,5,B,8^FS
        ^FO300,50^GB250,100,50,B,0^FS
        ^FO300,250^GB250,100,50,B,2^FS
        ^FO300,450^GB250,100,50,4^FS
        ^FO300,650^GB250,100,50,B,6^FS
        ^FO300,850^GB250,100,50,B,8^FS
        ^XZ
        """.trimIndent()
    ),

    CodeSet(
        "49 - Display Description (^HZ)",
        """
        ^XA
        ^FX Testing HZ (Display Description)^HZa
        ^XZ
        """.trimIndent()
    )
)
