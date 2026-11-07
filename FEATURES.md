# PDF Speed — CamScanner feature checklist (owner-supplied 2026-11-08)
Status key: DONE = in the native app · PARTIAL = basic form exists · TODO = not yet
Checked against app code 2026-11-08 (native Kotlin app, com.asadwebs.pdfspeed).

## 1. Scan & capture
- Live edge detection + auto-capture — DONE (R1, 2026-11-08)
- Perspective fix + manual corners — DONE (dual-strategy detection + edge-snap refinement, warp, loupe handles)
- Batch scan (multi-page session) — DONE (Next page adds pages to one document)
- Modes: Document / ID Card (two-sided, one page) / Book (spread split) / Receipt / Whiteboard / Passport / Card — DONE (R1)
- Flash, Grid — DONE · Gallery import — DONE · Import PDF — DONE (Library → Import PDF)
- Reorder pages — DONE (Doc screen: Up/Down/Remove per page)

## 2. Enhancement
- Filters: Original DONE · Magic Color DONE · B&W DONE (adaptive) · Grayscale DONE · Auto ~ Magic Color · Lighten TODO
- Brightness / Contrast / Sharpness sliders — TODO
- Shadow & stain removal, background cleanup — TODO
- Crop — PARTIAL (corner adjust) · Rotate — DONE · Deskew — PARTIAL (warp) · Erase brush — TODO

## 3. OCR — TODO (planned: on-device, free, no limits)
## 4. Library
- Star DONE · Rename DONE · Delete DONE · Search TODO · Folders TODO · Tags TODO · Sort TODO
- Multi-select / Move / Duplicate / Recycle bin — TODO · Cloud sync — TODO
## 5. PDF tools
- Export PDF + share — DONE · In-app PDF viewer — TODO (pages shown as images)
- Merge / Split / Reorder / Extract — TODO · Annotate (highlight/draw/text/shapes) — TODO
- Sign / Watermark / Page numbers — TODO · PDF→Word/Excel/PPT/JPG — TODO · Compress — TODO
## 6. Security — TODO (PDF password, app PIN/fingerprint lock)
## 7. Sharing — PDF share DONE · JPG/link/email share TODO · Collaboration/fax TODO (skip: business-plan extras)
## 8. AI features — TODO (later)

## Our edge vs CamScanner (already true today)
- No watermark on free scans, no ads, no feature paywalls, everything on-device.
