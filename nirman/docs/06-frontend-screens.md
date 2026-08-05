# 06 — Frontend routes and screens

Two shells. The role decides which one loads at `/`.

## SupervisorShell — phone first
Home is tiles and nothing else, grouped under six headings.

The grouping is by **act, not by module**, and that is the decision to hold to when a screen is added. Attendance, material and expenses are three modules and one job — the things entered as the day happens — while marking attendance and verifying it are one module and two entirely different jobs, done by different people at different times of day. Grouping by module would put the second pair together and split the first three, which is backwards from how the work is done. A flat list said only what order the phases were built in.

A group whose tiles the role cannot open disappears with its heading: the word "Set up" over an empty space reads as something broken rather than as something that was never yours. The group order is fixed rather than sorted per role — the permission filter already leaves a supervisor with entry first and little else — because a menu whose contents move is a menu nobody learns.

| Route | Screen | Notes |
|---|---|---|
| `/` | Home tiles | **Today at site** — mark attendance · receive material · issue material · add expense · daily report. **Check and sign off** — verify attendance · verify reports · approvals and payments. **Look something up** — stock · workers. **How it is going** — company, site and data-quality dashboards. **Set up** — members · projects · sites. **This device** — not sent yet · your account |
| `/attendance/mark` | Roster | Site and date defaulted. Present/absent toggle, mark-all-present, per-worker hours drawer |
| `/attendance/mark/:workerId` | Worker hours | Hours worked (prefilled with the site shift), OT reason, BOQ item |
| `/inventory/receive` | Goods receipt | Supplier, challan, camera capture, line items |
| `/inventory/issue` | Material issue | Material search, quantity with unit switch, BOQ item, issued-to |
| `/expenses/new` | Add expense | Category, amount before tax with GST rate beside it, bill number, and the bill photographed and compressed on the device before it is queued. Saves as a draft; sending is a separate act. A duplicate warning shows the rows it collided with and takes a reason to override |
| `/approvals` | Approvals and payments | What is waiting on you, with the level and role on each row; and, for whoever holds `payment:record`, the approved bills still owed with approved/paid/owed shown separately |
| `/dpr/new` | DPR wizard | 3 steps: what the records already say → work done → observations. Step one is read-only: the figures are derived from the muster, the ledger and the bill book, and a field that let somebody type over them would create a second version of a number the rest of the system computes. A labour cost standing on unverified attendance is marked provisional, and material received is shown apart from material consumed. Photographs arrive with Phase 7's compression |
| `/approvals` | My submissions | Status chips, rejection reasons |
| `/sync` | Not sent yet | What the device still owes the server, named rather than counted — "12 attendance mark(s) — KSN-A Kausani", not "1 record". Anything waiting on its own says so and asks nothing; anything stuck on a decision is listed first, because those are the only rows still here tomorrow if nobody touches them. Retry and discard for a refusal, keep-mine-with-a-reason or discard for a conflict |

## DeskShell — Admin, Engineer, Accountant
Left sidebar, dense tables, saved filters.

| Route | Screen | Roles |
|---|---|---|
| `/dashboard` | Company dashboard. Material as three figures set out as an equation that lands on what the stores hold, with purchased below the rule and visibly not one of the terms; cost incurred and total booked under separate headings | Admin, Accountant |
| `/dashboard/site/:siteId` | Site dashboard: labour with the unsigned part of the wage bill flagged, material, cash, contract progress by value, and the dates with no report rather than a count of them | Admin, Engineer |
| `/dashboard/quality` | Data quality. A work queue: act before watch, every finding with what to do about it and the evidence behind its count | Admin, Engineer |
| `/projects`, `/projects/:id` | Project list, add and edit contract details, detail with budget vs actual | Admin, Accountant |
| `/sites`, `/sites/:id` | Site list, detail, stores, engineer and supervisor posting | Admin |
| `/boq`, `/boq/:id` | BOQ items, progress | Admin, Engineer |
| `/workers`, `/workers/:id` | Worker master, wage history timeline | Admin, Engineer |
| `/attendance/verify` | Verification queue, bulk verify | Engineer, Admin |
| `/attendance/register` | Register with month grid, export | all desk roles |
| `/inventory/stock` | Stock by site, consolidated toggle | all desk roles |
| `/inventory/ledger/:materialId` | Movement ledger | all desk roles |
| `/inventory/transfers` | Transfer tracker with status pipeline | Admin, Engineer |
| `/inventory/counts` | Physical count and variance | Admin, Engineer |
| `/expenses` | Expense register, approval queue | Admin, Engineer, Accountant |
| `/expenses/:id` | Detail, approval trail, attachments, payments | same |
| `/payments` | Payment recording, vendor balances | Admin, Accountant |
| `/advances` | Advances, settlements, outstanding | Admin, Accountant |
| `/dprs` | DPR list and the verification queue. The panel shows what would be claimed against the contract before it offers the verify button, and keeps work recorded apart from work claimed. Sending a report back requires a reason. PDF downloads through the api client, since the route needs the Authorization header | Admin, Engineer |
| `/reports/:name` | Report runner with filters and xlsx export | per permission |
| `/masters/*` | Materials, vendors, contractors, categories, units | Admin |
| `/users`, `/users/:id` | Users, roles, site assignment, first-time password and reset | Admin |
| `/profile` | Own account and password change | all roles |
| `/imports` | Template download, upload, batch history, error file | Admin |
| `/audit` | Audit log search | Admin |

## Shared behaviour
- Route guard reads permissions from `/auth/me`. Home shows only the tiles the role can actually open — a greyed-out row of other people's screens tells nobody anything they can act on, and on a phone it pushes the handful they do use below the fold. Tiles a later phase brings still show, because "not built yet" is a different thing from "not yours". Backend re-checks regardless.
- A member still carrying the password an admin issued is held on `/profile` until they replace it: a first-time password is known to two people, so nothing may be entered under it.
- Every list screen: empty state that names the next action, error state that names what failed and offers retry.
- Status chip component is the one visual constant across all modules.
- Offline banner appears at the top of the shell when the queue is non-empty, with a link to `/sync`. Records stuck on a decision are called out separately and in a louder colour: the two states need opposite things from the reader, and a conflict will still be there tomorrow if the banner lets it hide inside a count of things that are merely waiting.
- Saving with no signal is not an error and does not read like one. The record is kept on the device under the id it would have been sent with, and the screen says so in those words — the only thing that has not happened is the sending, and that happens by itself.
- i18n: all strings through `t()` from day one, English catalogue only.
