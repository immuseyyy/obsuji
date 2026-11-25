# ObSuji

中文： [README.md](README.md)

A quick-capture companion app for Obsidian (Android). It only captures — organizing and reviewing stay in Obsidian itself.

- Full-screen editor, plus a floating quick-capture panel summoned from a Quick Settings tile
- On send, entries are written into a target note in the vault using a template; attachments are copied into the attachment folder only at send time
- Recording capsule: Stop stages the take in the draft, Send writes it straight into the note
- System share target: share text or a single image from other apps into the floating panel
- Drafts, history (200 entries), and the pending-attachment registry live in app-specific storage

## Layout

- `app/src/main/java/` — the shell (Kotlin): WebView host, full-screen and floating activities, native bridge, Quick Settings tile
- `app/src/main/assets/editor.html` — the editor itself (single file)
- `manual-build/` — manual build chain (aapt2 + kotlinc + d8 + apksigner), no Gradle
- `icon-master.svg` — icon master artwork

## Building

Run `manual-build/build-apk.sh`. The signing key is not in this repository and is kept separately; a package signed with a different key cannot upgrade an existing install.
