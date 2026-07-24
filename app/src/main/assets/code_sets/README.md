# Code sets

Drop ZPL test files here and the app picks them up automatically - no code
changes needed.

**Single-section code set** (most common): one file named `NN_description.zpl`,
e.g. `02_font_positioning.zpl`. Shows up as one row with a single Print button.

**Multi-section code set** (sections must run independently, e.g. because a
setting from one section would corrupt the next if sent together): a folder
named `NN_description/` containing `1.zpl`, `2.zpl`, `3.zpl`, etc. Shows up
as one expandable row with a Print button per section.

`NN` is a two-digit sort prefix (`01`, `02`, ...); the rest of the name is
just a human-readable label and can be anything.

## Source

Sets 01-49 here are transcribed from `03-2024_Testing_CodeSets.pdf`
("Code Sets for Testing DPP-450 ZPL Emulation"), the same QA document the
sibling `printer_app` Android project uses. The PDF's text extraction had a
recurring font ligature bug (e.g. "ti" rendering as a stray "A" or ","); the
English prose in comments/titles was cleaned up by hand, but no ZPL command
syntax was altered.

Two things worth knowing before running these against unfamiliar hardware:

- **Set 18 sends `~JP` twice.** On the DPP-450 (a different printer than
  what this tool currently targets), `~JP` was found to hard-lock the
  printer in every form tested - only a power cycle recovered it. It has
  not caused problems on the Zebra ZD421 this tool was built against, but
  if you're testing an unfamiliar printer/emulation, be ready to power
  cycle it after running set 18.
- **Set 20 ("Name Changing") differs from the PDF.** The source document's
  ZPL for this one has unbalanced `^XA`/`^XZ` tags (a typo in the original
  document). It's been reconstructed here as 4 well-formed sections that
  preserve the original intent (rename, dump config, rename back, dump
  config again) rather than sent as-authored.
