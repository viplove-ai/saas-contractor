# Screen-by-screen spec (turn 3 of the mock)

Every screen below keeps its **current behaviour, state, queries and copy** — the change is
visual, plus the shell change described in the README. Nothing here asks for a new endpoint.

Apply the visual language from `src/app/theme.ts` + `src/app/sketch.ts` (already in this
bundle) and these rules everywhere:

- Page title: `variant="h1"` (Kalam, 32px desk / 27px phone). Follow it with the `HandRule`
  from `TodayPage.tsx`, width 120–340px depending on title length.
- Card: `<Paper variant="outlined" sx={inkEdge(i)}>`. Emphasised card (one per screen, at most):
  `inkEdge(i, {emphasis: true})`.
- Section label above a group: `variant="overline"` (mono, tracked, `color: tokens.annotation`
  when it labels data, `text.secondary` when it labels a list).
- Every amount / quantity / hour / date / code: `sx={figure}`. Never Kalam.
- One margin note per screen at most, `sx={marginNote}`, carrying a fact the UI cannot show.
- Primary action `variant="contained" color="secondary"`; secondary `variant="outlined"`;
  destructive `variant="outlined" color="error"`. No third weight.
- Tables become `display:grid` rows with a `tokens.paperDeep` header row, a
  `1.6px solid ink` rule under it and `1.2px dashed rgba(20,24,29,.25)` between rows —
  not MUI `<Table>`, whose 1px greys disappear on paper.

---

## MarkAttendancePage — mock `3a` (phone-led)
Source: `src/features/attendance/MarkAttendancePage.tsx`

Site + date pickers side by side, then the count chips (`N of M marked`, `N present`) and
**Mark all present** as a text action, not a button — it sits beside counts, not in the action bar.

Each worker is one `inkEdge(i)` row: name (600/14.5px), then `code · H h worked · H h OT` in
mono at `text.secondary`. Right side, in order: StatusChip (only when `entry.attendance`
exists), the P/A `ToggleButtonGroup` (44×46 cells, selected cell filled `tokens.signal` with
`surface` text), and the hours button showing `${hours} h` — outlined `tokens.signal` on
`#FDEDE6` when hours exceed the standard shift, plain otherwise, and disabled/greyed with the
label "Hours" when the row is absent. A locked or verified row greys the toggle at 0.5 opacity.

Save / Send for verification pin to the bottom above a dashed rule.
Margin note: "Tapping P fills the standard 8 h shift — open the hours only for the men who
stayed longer."

## VerifyAttendancePage — mock `3b` (desk-led)
Source: `src/features/attendance/VerifyAttendancePage.tsx`

Site / From / To, then the `SUBMITTED | VERIFIED` toggle as one inked segmented control
(selected segment = solid ink, `surface` text). Count chips, then **Select all** as a text
action.

Rows: a 24px inked checkbox (checked = `tokens.signal` fill, Kalam ✓), name, then
`date · status · H h worked · H h OT` and the OT reason on its own line. StatusChip right.
Selected rows carry the drawn shadow `2px 3px 0 rgba(20,24,29,.08)`; unselected do not — the
selection is legible without reading the boxes.

Remarks textarea max 620px wide, then `Verify N records` (contained secondary) and
`Send back N records` (outlined error).
Margin note: "Hours, not money — the rate is frozen onto the record at the moment you sign."

## ReceiveMaterialPage — mock `3c` (phone-led)
Source: `src/features/inventory/ReceiveMaterialPage.tsx`

StorePicker as a 2-up grid (Store, Received on), then Challan / Vehicle 2-up, Invoice below
when present. Each line is an `inkEdge(i)` card headed `LINE N` in overline with a
`Remove` text action in `error.main` — the icon button becomes a word, because a 48px bin
icon beside four fields on a 390px screen is the easiest mis-tap on the screen.
Inside: material select full width, then a `.8fr 1fr 1.1fr` grid of unit / quantity / rate.
Inner fields sit on `tokens.paper` with a `1.4px rgba(ink,.55)` border, so a line's fields
read as inside its card.

`+ Add material` as a text action, with `Before tax ₹N` right-aligned on the same row.
Then the "waiting to be checked" queue, one compact row per GRN with its StatusChip.
Margin note: "Nothing moves the stock until an engineer checks it against the challan."

## IssueMaterialPage — mock `3e` left (phone-led)
Source: `src/features/inventory/IssueMaterialPage.tsx`

Same line-card grammar as receive, minus rate (an issue has no price — the moving average
supplies it). Above the lines: For which work (BOQ item), Issued to, What is it for.
Footer row shows `Charged to work ₹N`.

**New element, not in the current screen:** under the quantity, an amber hint reading
`Store holds N unit. This leaves M.` — it needs `useStock(storeId)` for the material, which
the screen does not currently call. Implement it or drop it; do not ship it showing a
guessed number.

## StockPage — mock `3d` (desk-led)
Source: `src/features/inventory/StockPage.tsx`

Grid table: `2fr 1fr 1fr 1fr 1fr` — Material / In stock / Coming / Avg rate / Value, all but
the first right-aligned and in mono. A low row takes a `#FEF3E2` background and its quantity
turns `tokens.warn`; its subtitle reads `code · below N unit`. Above: store + as-at pickers,
the "Only what is running low" switch, and the stock-value chip.

The ledger `Drawer` becomes a paper panel with a `1.6px solid ink` left edge (not a shadow —
a shadow on paper reads as a smudge). Header: material name in `h2`, then
`store · qty at rate`. Overline `EVERY MOVEMENT BEHIND THAT FIGURE`, then one row per
movement: label + `date · reason` left, `±qty` and `balance N` right, inward in
`success.main` with a `+`.
Margin notes: "Coming is its own column on purpose — material on a lorry cannot be issued."
and, in the drawer, "The argument ends at the movement where the two counts diverged."

## AddExpensePage — mock `3e` right (phone-led)
Source: `src/features/expenses/AddExpensePage.tsx`

Spent on + What kind 2-up; What was it for full width; then a `1.2fr .7fr 1fr` grid of
Before tax / GST % / Bill number. `Total with tax` is a label-left, figure-right row at
19px mono — the one big number on the screen.

`BillPhotoField` becomes a dashed `inkEdge` block with a 64px hatched square placeholder and
two lines of copy ("Photograph of the bill" / "Take it at the shop. It is compressed on the
phone before it is sent."). Keep the real file input behind it.

The duplicate warning keeps its full anatomy: title, the candidate line
(`number · date · amount · bill · matchedOn`), the override reason field, and
`Book it anyway` — all inside one `warning` panel.
Margin note: "Saving is not sending. Send it when the figure is right."

## ApprovalsPage — mock `3f` (desk-led)
Source: `src/features/expenses/ApprovalsPage.tsx`

Two columns side by side on desk, stacked on phone: WAITING FOR A DECISION and
APPROVED AND STILL OWED. Only the first card in each column carries the drawn shadow, so the
eye lands on the next thing to act on.

Decision card: number + StatusChip + `Level N · role` chip on one line;
`date · category · vendor · amount` under it; description; then `Bill N` or the no-bill
reason. Remarks field, then Approve / Send back to fix / Reject.
Payment card: the three figures as a 3-up grid — Approved, Paid, Owed (owed in
`tokens.warn`) — then a 130px Pay now field and Record payment.
Margin notes: "Send back is not a softer rejection — it goes back editable." and
"Three figures, never merged into one."

## DprWizardPage — mock `3g` (phone-led)
Source: `src/features/dpr/DprWizardPage.tsx`

Replace the MUI `Stepper` with three 26px inked circles joined by dashed rules — the active
one filled `tokens.signal`, and only the active step carries its label, because three labels
do not fit a 390px screen without truncation.

Step 1 is three `inkEdge` cards — LABOUR, MATERIAL, CASH — each an overline plus a figure
grid. Labour ends in the provisional warning when `labourCostProvisional`. Material shows
Received (inventory) and Consumed (cost) as two columns with the margin note
"Never added together — one is stock, the other is spend." Suggested work items become a
dashed panel of inked `+ itemNumber description` chips.
Steps 2 and 3 keep their current field sets; apply the line-card grammar from receive.

## DprListPage — mock `3i`
Source: `src/features/dpr/DprListPage.tsx`
Grid table Report / Site / Date / Status; the detail drawer follows the StockPage drawer
pattern with the figure set already in that file.

## SiteDashboardPage — mock `3h` (desk-led)
Source: `src/features/dashboard/SiteDashboardPage.tsx`

Title + period pickers on one baseline. Then Labour and Cash as a 2-up of `inkEdge` cards,
each an overline + figure grid, keeping both existing caveats (unverified wage, days with no
muster). Below, `1.3fr 1fr`: contract progress (percentage right of the overline, the 14px
inked progress bar, the by-value margin note, then the top-items grid with a 6px inline bar
per row — `tokens.warn` when over-claimed) beside a column of cost-by-day, daily reports and
the caveat panel.

`CostTrendChart` becomes inked bars: `1.5px solid ink`, `tokens.signal` fill, slightly
different top radii per bar, a zero-cost day left `paperDeep` with its label in
`tokens.warn`, and today hatched
(`repeating-linear-gradient(135deg, #C2410C 0 5px, #E8946A 5px 10px)`) with the margin note
"Hatched bar is today — still being entered." Do not reach for a chart library.

The missing-record panel is dashed, never solid: it is an absence, not a figure.

## CompanyDashboardPage — mock `3i` (desk-led)
Source: `src/features/dashboard/CompanyDashboardPage.tsx`
Four tiles across (Active projects, Sites, Contract value, Budget), then WHAT IT COST and
WHAT IS OWED as a 2-up, then the projects grid table.

## WorkersPage / UsersPage — mock `3i` bottom
Sources: `src/features/labour/WorkersPage.tsx`, `src/features/admin/UsersPage.tsx`
Grid tables. Worker: name + `code · trade`, site in mono, status chip.
Member: name + username, role label from `shared/roles.ts`, sites (`All` or a count), and the
"Password not set" outline chip.

## SyncPage — mock `3j` left (phone-led)
Source: `src/features/sync/SyncPage.tsx`

Connection / waiting / photo chips, then Send now full width (disabled state = `paperDeep`
fill, muted text, dashed-free — it is unavailable, not broken).

Row emphasis is the whole point of this screen: CONFLICT rows get
`1.8px solid signal` on `#FDEDE6` with the drawn shadow and the `Decide` button; FAILED rows
get `1.5px solid error` on surface with Try again / Discard; PENDING rows get the plain
`rgba(ink,.55)` border and no action at all. Same order as the current file.
Margin note: "Rows needing a decision come first — they are the only ones still here tomorrow."

## ProfilePage — mock `3j` right (phone-led)
Source: `src/features/profile/ProfilePage.tsx`, `src/features/profile/schema.ts`

One identity card: avatar + name + the "Your name, role and site postings are set by an
administrator" line, then a dashed rule and the label/value rows — Username, Role (from
`ROLE_LABEL`), Email, Mobile, Sites (`All sites` chip or `N assigned`). Then the password
card: all three fields (current — labelled "The password you were given" when
`mustChangePassword`, and then the amber banner appears — new, new again), helper
"At least 8 characters.", and Change password.

**New in this design:** Sign out lives here, as a dashed-border outlined error button in its
own `flex:none` row below the scroll area. `ProfilePage.tsx` does not currently render it —
it was on the AppBar that `RootLayout.tsx` removes, so add it here as part of the same change.

---

## Two lessons from building the mock, worth knowing before you code

**A phone screen is 844px and that is a budget.** The profile screen went over it three times:
identity card + three password fields + sign-out + bottom nav does not fit. In a real browser
the page scrolls, so this is not a bug you will hit — but it does mean the identity card should
be the compact label/value list above, not a stack of `Row`s with 120px label gutters.

**Design annotations never go inside the frame.** Anything explaining a decision belongs in
your commit message, not in a `<Typography>` on the screen. The margin notes named above are
different — each one carries a fact about the data that the numbers cannot state themselves.
