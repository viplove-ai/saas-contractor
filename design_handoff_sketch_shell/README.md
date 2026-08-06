# Handoff: sketch-elegant shell + Today screen

## Overview
Restyles the Nirman Constructions PWA as a **site notebook** — warm paper ground, hand-inked
card edges, Kalam for headings and margin notes — and replaces the tile-grid landing screen with
a ranked **Today** screen behind a five-item bottom bar (phone) / left rail (desk).

The existing palette is unchanged: ink `#14181D`, signal `#C2410C`, the five status colours, mono
digits, 48px targets. The handwriting font never carries a figure, field label or button.

## About these files
Unlike a usual design handoff, `src/` here is **real drop-in TypeScript/React** written against
your actual stack (MUI v5 `sx`, react-router-dom v6 lazy routes, @tanstack/react-query, dexie +
dexie-react-hooks) and your actual hooks (`useSites`, `useRoster`, `useVerificationQueue`,
`useSiteDashboard`, `offlineDb`). It is meant to be copied into `frontend/src/` and compiled, not
transcribed. The HTML file `Nirman Design.dc.html` in the project root is the visual reference the
code was written from, including the recreation of the current UI as a before-picture.

Fidelity: **high**. Colors, type, spacing and copy in the code match the mock.

## Files in this bundle

| File | Action | Notes |
| --- | --- | --- |
| `src/app/theme.ts` | **replace** | Adds `paper`, `paperDeep`, `annotation` tokens, `HAND`, hand-set `h1`/`h2`, mono `overline`, and inked-edge overrides for Button / Paper(outlined) / OutlinedInput / Alert. `tokens.ink`, `.signal`, `.ok`, `.warn`, `.stop`, `.muted`, `.line` and `TOUCH_TARGET` keep their exact values, so nothing that imports them changes. |
| `src/app/sketch.ts` | **new** | `inkEdge(seed, {emphasis})`, `edgeRadius(seed)`, `graphPaper`, `marginNote`, `figure`. The whole hand-drawn language, as `sx` values — no images, no SVG filters. |
| `src/app/AppNav.tsx` | **new** | `BottomNav`, `SideRail`, `Wordmark`, `Initials`, `useUnsentCount`. |
| `src/app/RootLayout.tsx` | **replace** | AppBar removed; rail + bottom bar + paper ground. Takes `signoffCount?: number`. |
| `src/features/today/api.ts` | **new** | `useToday(siteId)` — composes existing queries, **no new endpoint**. |
| `src/features/today/TodayPage.tsx` | **new** | The Today screen. Also exports `HandRule`. |
| `src/features/auth/LoginPage.tsx` | **replace** | Same form, same schema, same submit logic. Labels above fields, Show/Hide password, offline note. |
| `src/features/home/HomePage.tsx` | **replace** | Same `GROUPS` table verbatim; retitled "All screens", restyled tiles. |
| `patches/index.html.patch` | apply | Adds Kalam to the existing font request. |
| `patches/router.tsx.patch` | apply | `/today` route + index/catch-all redirects. |

## Order to apply
1. `index.html` font patch (nothing looks right until Kalam loads).
2. `theme.ts`, then `sketch.ts` — every other file imports from these.
3. `AppNav.tsx`, `RootLayout.tsx`.
4. `today/api.ts`, `today/TodayPage.tsx`, `router.tsx` patch.
5. `LoginPage.tsx`, `HomePage.tsx`.

## Design decisions worth keeping (or arguing with)

**The AppBar is gone.** It spent 56px of a phone screen on a wordmark, a role chip and Sign out —
nothing anybody opened the app for. The home icon existed only because the tiles were the only
way back; a bottom bar makes it redundant. Identity moved to an avatar in the masthead (phone)
and the rail footer (desk), both one tap from `/profile`.

**Today ranks what the tile grid listed.** The three bands — muster / waiting on you / enter
something — are the same split `GROUPS` already makes, ordered by when a day needs them. The
muster card is the only emphasised card on the screen, and only while the muster is unmarked.

**Five bottom items, all ≥56px, labels not icons.** A sixth item puts every label under 60px on
a 375px screen. There is no icon for "sign-off" or "registers" that a supervisor reads faster
than the word. `env(safe-area-inset-bottom)` keeps the bar off the swipe area.

**The sketch never touches data.** Kalam is confined to `h1`, `h2` and `marginNote`. Every figure
is `figure` (IBM Plex Mono, tabular-nums). Field labels sit above the input at full contrast
instead of floating as 12px grey. Paper `#F7F3E9` is *lighter* than the `#F7F8F9` it replaces at
equal ink, so sunlight contrast went up, not down.

**Wobble is deterministic.** `edgeRadius(seed)` picks from six fixed corner-radius strings by
index, so a grid of sixteen tiles reads as sixteen drawn boxes without random values changing on
every render.

## Design tokens
Ink `#14181D` · surface `#FFFDF7` · paper `#F7F3E9` · paperDeep `#EFE9DA` · muted `#5A646E` ·
line `#DCE1E6` · annotation `#8F6A3F` · signal `#C2410C` · ok `#15803D` · warn `#B45309` ·
stop `#B91C1C`.

Inked edge: `1.5–1.8px solid ink`, radius from `edgeRadius()`, shadow `3px 4px 0 rgba(20,24,29,.10)`
(or `4px 5px 0 ink` when emphasised). Graph paper: two 1px gradients at 5% ink on a 26px grid.
Fonts: Kalam 400/700, IBM Plex Sans 400/500/600/700, IBM Plex Mono 500/600.

## Not in this bundle
The landing page (`2d` in the mock) is marketing-site content, not part of the PWA — say the word
and it becomes its own route or a separate static page. The muster, material, expense, DPR and
dashboard screens inherit the new theme automatically (outlined `Paper`, `Button` and
`TextField` are themed) but have not been individually re-laid out; the four `.test.tsx` files
for the screens above may need their queries updated where copy changed
("Nirman Constructions" → "Good morning." on login, "All screens" on home).
