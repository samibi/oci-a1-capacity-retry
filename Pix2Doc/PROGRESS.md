# Pix2Doc — progress

## 2026-10-16
- Mockup (4 screens) published as a private Artifact; updated: name Pix2Doc, A4 default, language setting, Save/Save as.
- Decisions: A4 default (fit, aspect kept, auto orientation, 5 mm margin); JPEG embedded untouched; PNG lossless; HEIC/WebP → JPEG q95; optional 300 dpi cap (off).
- Overwrite by default when the file is writable; read-only (shared) PDFs fall back to Save as.
- Default save: Download/Pix2Doc (MediaStore) or a user-chosen folder; optional "ask every time".
- Language: system / Hebrew / English (per-app locale).
- New repo creation blocked from this session (GitHub 403) → code staged in `Pix2Doc/` on branch `ccr-8e951833-b3kiye` of oci-a1-capacity-retry.
- No Android SDK in the session container → build verified only through GitHub Actions (`.github/workflows/pix2doc.yml`).

## Next
- Move `Pix2Doc/` to its own repo once created.
- Test on device: share flow, EXIF rotation, overwrite vs. save as, Hebrew UI.
