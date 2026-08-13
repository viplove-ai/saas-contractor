# Nirman brand assets — mark 5c (Devanagari न)

> **Where the files are.** The shipped assets live in `frontend/public/brand/` and are served
> at `/brand/…`. This folder holds only the working material behind them — this note, the
> integration write-up, and `_render.html` with its source capture. They were moved out of
> `public/` because everything under it is copied into the build, served publicly and
> precached into the service worker, and a site phone has no use for the design notes.

The mark is the first letter of निर्माण set in Kalam 700 (the app's heading face), inside the
inked notebook tile, with the terracotta rule the app uses under headings. Because it is
typeset rather than drawn, the master is rendered from the live font — `_render.html` is that
source. To change size, weight, framing or colour, edit `_render.html`, open it, and re-capture.

## Ship these
| File | Where |
|---|---|
| favicon-16.png, favicon-32.png | `<link rel="icon">` |
| favicon.svg | scalable favicon (wraps the 512 master) |
| apple-touch-icon.png (180, opaque) | iOS home screen — iOS does not honour transparency |
| icon-192.png, icon-512.png | manifest, purpose `any` |
| maskable-512.png | manifest, purpose `maskable` — terracotta field, glyph inside the 80% safe circle |
| logo-lockup.png (753×288) | login header, reports, letterhead |
| manifest.webmanifest | `<link rel="manifest">` |
| HEAD-SNIPPET.html | paste into index.html `<head>` |

Colours: paper `#F7F3E9`, ink `#14181D`, terracotta `#C2410C`, theme-color `#C2410C`,
background_color `#F7F3E9`.

Put the folder at `public/brand/` in the Vite app so the paths in the snippet resolve as written.
