# Paste this into Claude Code

Two ways to use this bundle. Pick one.

---

## A. You have the bundle unzipped (fastest, no rewriting)

Unzip `design_handoff_sketch_shell/` anywhere inside or beside the repo, then:

> Read `design_handoff_sketch_shell/README.md`. It contains finished TypeScript/React files
> written against this repo's actual stack and hooks. Apply them to `frontend/` in the order the
> README's "Order to apply" section gives: copy each file in `design_handoff_sketch_shell/src/`
> to the matching path under `frontend/src/`, and apply the two patches in
> `design_handoff_sketch_shell/patches/` by hand (they are described, not in diff format).
>
> Then: run `npm run lint` and `tsc --noEmit` and fix anything that does not compile — the files
> were written from reading the repo, not from building it, so import paths and a couple of type
> names may need adjusting. Run `npm test` and update the assertions in
> `LoginPage.test.tsx`, `RootLayout.test.tsx` and `HomePage.test.tsx` where copy changed
> (login heading is now "Good morning.", home heading is now "All screens", and there is no
> AppBar or home icon any more). Do not change behaviour to make a test pass — change the test.
>
> Add a `TodayPage.test.tsx` covering: the muster card says NOT MARKED YET when the roster has
> entries and none are marked; it says N OF M MARKED otherwise; the "Mark all present" button
> only appears while unmarked; and a group with a zero count does not render a row.
>
> Do not restyle any screen not listed in the README. The theme changes reach them automatically
> through the MUI overrides, and I want to review each one before it is touched.

---

## B. You do not have the bundle (Claude Code writes it from the spec)

Then paste the README's **"Design decisions worth keeping"** and **"Design tokens"** sections
followed by:

> Implement this in `frontend/`. Add a `/today` route as the new index, replace the AppBar in
> `RootLayout.tsx` with a five-item bottom bar (phone) and left rail (desk), extend
> `src/app/theme.ts` with the paper tokens and inked-edge component overrides, and add
> `src/app/sketch.ts` holding `inkEdge(seed)`, `edgeRadius(seed)`, `graphPaper`, `marginNote`
> and `figure` as `sx` values.
>
> The Today screen must compose existing queries — `useSites`, `useRoster`,
> `useVerificationQueue`, `useSiteDashboard`, and the dexie unsent count — into one
> `useToday(siteId)` hook. Do not add a server endpoint.
>
> Hard rules: keep `tokens.ink`, `.signal`, `.ok`, `.warn`, `.stop`, `.muted`, `.line` and
> `TOUCH_TARGET` at their current values. Kalam is allowed on `h1`, `h2` and margin notes only —
> never on a figure, field label or button. Every amount, quantity and hour stays IBM Plex Mono
> with `tabular-nums`. Corner-radius wobble must be deterministic (index into a fixed table),
> never random. Nothing tappable goes below 48px, bottom-bar cells below 56px, and the bar
> must respect `env(safe-area-inset-bottom)`.

---

## Either way, tell it what NOT to do

> Do not add a component library, an icon pack, an animation library, or an SVG filter. The
> hand-drawn look is border-radius, border-width and box-shadow only — it has to repaint cheaply
> on a five-year-old Android in a basement.
