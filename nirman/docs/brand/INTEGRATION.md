# Wiring the brand assets into the app

Assets are already staged at `design_handoff_sketch_shell/public/brand/` — copy that folder into
your repo's `public/` so files resolve at `/brand/…` in dev and in the build.

## 1. index.html

Paste inside `<head>` (contents of `brand/HEAD-SNIPPET.html`):

```html
<link rel="icon" href="/brand/favicon.svg" type="image/svg+xml" />
<link rel="icon" href="/brand/favicon-32.png" sizes="32x32" type="image/png" />
<link rel="icon" href="/brand/favicon-16.png" sizes="16x16" type="image/png" />
<link rel="apple-touch-icon" href="/brand/apple-touch-icon.png" />
<link rel="manifest" href="/brand/manifest.webmanifest" />
<meta name="theme-color" content="#C2410C" />
<meta name="apple-mobile-web-app-capable" content="yes" />
<meta name="apple-mobile-web-app-status-bar-style" content="default" />
<meta name="apple-mobile-web-app-title" content="Nirman" />
```

Set the title too: `<title>Nirman — Today</title>`.

Delete Vite's default `<link rel="icon" href="/vite.svg" />` and `public/vite.svg`.

## 2. The lockup in the UI

`LoginPage.tsx`, above the form:

```tsx
<Box
  component="img"
  src="/brand/logo-lockup.png"
  alt="Nirman Constructions"
  sx={{ width: 240, height: 'auto', display: 'block', mx: 'auto', mb: 3 }}
/>
```

The lockup is 753×288 with its own paper-white bed, so give it a `#FFFDF7` parent or leave it on
the login card — don't put it on the graph-paper ground, the bed edge will show.

Anywhere the mark alone is wanted (nav rail head, report header), use `icon-512.png` at a small
size rather than cropping the lockup:

```tsx
<Box component="img" src="/brand/icon-512.png" alt="" sx={{ width: 34, height: 34, borderRadius: '9px 6px 10px 7px/7px 10px 6px 9px' }} />
```

## 3. Service worker (makes it installable)

The manifest alone gets you the icons and theme colour; Chrome only offers "Install" once a
service worker with a fetch handler is registered. Easiest path with Vite:

```bash
npm i -D vite-plugin-pwa
```

```ts
// vite.config.ts
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      // the file in public/brand is the source of truth — don't let the plugin write its own
      manifest: false,
      manifestFilename: 'brand/manifest.webmanifest',
      workbox: {
        globPatterns: ['**/*.{js,css,html,png,svg,woff2}'],
        navigateFallback: '/index.html',
      },
    }),
  ],
});
```

Keep the `<link rel="manifest">` from step 1; with `manifest: false` the plugin will not inject
a competing one.

## 4. Check it

- `npm run build && npm run preview` — the manifest must be served as
  `application/manifest+json`; Vite's preview does this for `.webmanifest` automatically.
- DevTools → Application → Manifest: no icon warnings, and the maskable preview should show the
  glyph clear of the circle edge.
- Lighthouse → Installable.
- iOS: Share → Add to Home Screen; the icon must be the opaque paper tile, not a black square
  (that failure means `apple-touch-icon.png` 404'd).

## Regenerating

The mark is Kalam's न, so there is no editable vector — masters come from `brand/_render.html`.
Edit that file, open it, and re-capture the `#icon`, `#maskable`, `#favicon` and `#lockup` tiles.
