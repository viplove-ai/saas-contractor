# 06 — Frontend routes and screens

Two shells. The role decides which one loads at `/`.

## SupervisorShell — phone first
Home is seven tiles, nothing else. Each tile is a full-width 88px row with an icon, a label and a pending-count badge.

| Route | Screen | Notes |
|---|---|---|
| `/` | Home tiles | Mark attendance · Receive material · Issue material · Add expense · Submit DPR · Pending approvals · Reports |
| `/attendance/mark` | Roster | Site and date defaulted. Present/absent toggle, mark-all-present, per-worker hours drawer |
| `/attendance/mark/:workerId` | Worker hours | Hours worked (prefilled with the site shift), OT reason, BOQ item |
| `/inventory/receive` | Goods receipt | Supplier, challan, camera capture, line items |
| `/inventory/issue` | Material issue | Material search, quantity with unit switch, BOQ item, issued-to |
| `/expenses/new` | Add expense | Category, amount before tax with GST rate beside it, bill number. Saves as a draft; sending is a separate act. A duplicate warning shows the rows it collided with and takes a reason to override |
| `/approvals` | Approvals and payments | What is waiting on you, with the level and role on each row; and, for whoever holds `payment:record`, the approved bills still owed with approved/paid/owed shown separately |
| `/dpr/new` | DPR wizard | 4 steps: auto-summary → work done → observations → photos |
| `/approvals` | My submissions | Status chips, rejection reasons |
| `/sync` | Sync centre | Queue with draft / pending / synced / failed, retry, conflict resolver |

## DeskShell — Admin, Engineer, Accountant
Left sidebar, dense tables, saved filters.

| Route | Screen | Roles |
|---|---|---|
| `/dashboard` | Company dashboard | Admin, Accountant |
| `/dashboard/site/:siteId` | Site dashboard | Admin, Engineer |
| `/dashboard/quality` | Data quality | Admin, Engineer |
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
| `/dprs`, `/dprs/:id` | DPR list, verify, PDF | Admin, Engineer |
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
- Offline banner appears at the top of the shell when the queue is non-empty, with the count and a link to `/sync`.
- i18n: all strings through `t()` from day one, English catalogue only.
