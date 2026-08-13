# 10 — Planning and Execution Strategy

Turning a Notice Inviting Tender into a dated plan: what gets built when, by how many men, off
what material ordered how far ahead, and — the question that actually decides whether the work
is survivable — how much money has to be found before the department pays any of it back.

`docs/00-assumptions.md` and `docs/09-design-decisions.md` explain why the existing rules are
what they are. This document is written in the same spirit: every choice below names the
failure it is preventing.

---

## 1. What this is, and what it must never become

The planner reads a tender and produces a **baseline** — a stored, frozen, versioned statement
of intent. It is the third kind of number the system holds, and it has to be kept apart from
the other two or it will corrupt them:

| Kind | Example | Rule |
|---|---|---|
| **Recorded fact** | a verified attendance row, a stock transaction | append-only, never recomputed |
| **Derived figure** | a dashboard tile, DPR prefill | computed per call, never stored |
| **Baseline** | this plan | computed once, then **frozen**, and superseded rather than edited |

A plan looks like a derived figure and is not one. `09-design-decisions.md` says rolled-up
figures are derived because a cached total is a second version of the truth — but a plan is not
a total of anything that happened. It is a claim about a future that has not happened yet, and
its whole value is that it *stops changing* so that reality can be measured against it. A plan
that silently recomputed itself every time it was opened would show a project on target
forever, because the target would follow the work.

So the plan freezes, exactly as attendance freezes its wage rate at verification and a DPR
freezes its figures when sent. **Variance** — plan against ledger — is the derived figure, and
it is computed per call like every other one.

**Three things the plan is forbidden to do.** Every one of these is a bug even if it passes
review:

- **A planned quantity never reaches the measurement book.** Claiming work is its own act and
  needs an engineer verifying a report. A plan is not a claim.
- **A planned material requirement is never a stock transaction.** The ledger records what
  arrived and what was issued. A plan predicting sixty bags of cement in March must not put
  sixty bags into any balance, or the store will show cement that is not in the shed.
- **A planned cost is never an expense.** No plan row is ever payable, approvable, or bookable.
  Cost incurred still comes from consumption and verified attendance.

The plan writes into the transaction tables in exactly **one** place, and it is a place V1
already built and left empty (§8.4).

---

## 2. What the tender has to be read to say

`nit_documents` already holds twenty fields and the priced schedule. Every one of them is used
by the plan. But the fields that decide the *shape* of a plan are the ones that were not worth
extracting when the tender module only had to answer "what did we bid against?", and they are
mostly in Schedule F and the standard clauses rather than on the summary page.

### 2.1 Already extracted, and what the plan does with it

| Field | Use |
|---|---|
| `estimated_cost`, `civil_/electrical_estimated_cost` | scale of the job; splits the two schedules' curves |
| `emd_amount` | cash blocked from before award until it is released |
| `performance_guarantee_percent` | margin money and BG commission at the start |
| `security_deposit_percent` | retention held back from **every** receipt |
| `completion_period` | the time the whole plan has to fit inside |
| BOQ lines + `category` + `work_part` | the work itself, already classified by `BoqClassifier` |
| `civil_cost_index_percent` | the rates the BOQ is priced at are DSR plus this |

### 2.2 Missing, and the plan is wrong without it

*Every figure in this section was checked against the ten CPWD notices in `docs/NIT documents/`.
Where the corpus contradicted an assumption, the corpus won — the counts below are what those
ten actually say.*

**`completion_days` (integer).** `completion_period` is stored as the printed string —
`"12 (Twelve) Months"`. A planner cannot divide by a string. Parse it to days alongside keeping
the text, because the text is the quotation and the integer is the input. In the corpus the
range is **two to twelve months**, which is the fact that shapes everything downstream: these
are short jobs, so a monsoon is not a seasonal adjustment, it is potentially the whole
programme.

**Milestones (Clause 2 / Schedule F).** *The single most valuable thing on this list, and
present in all ten.* They come in two shapes, and the second is worth far more than the first:

- **Financial.** `50% of Tendered Amount | 15 Days | 2.5% of accepted tendered value withheld`.
  Simple, tabular, parseable.
- **Physical, with a financial fallback joined by "or".** A prose description naming the actual
  activities — *"100% RRM/Retaining Wall/Plum Concrete work and 25% other development works.
  Excavation of foundation, lean concrete, casting of RCC foundation and RCC works upto plinth
  level beams, back filling and compaction, casting of grade slab"* — followed by
  *"or — Financially Gross value of work done: 10% of tendered value"*.

The second shape **is the department's own phasing of the work**, written in the vocabulary
`BoqClassifier` already sorts BOQ lines into. That changes the engine's job from inventing a
phase structure to adopting one (§6.3), and it is the strongest argument that this feature is
extraction-led rather than model-led.

Three details the table format forces:

- **Time units are mixed inside one table** — `15 Days`, `02 Month`, `3 (Three) days`.
- **A milestone can cover civil and electrical in one row**, with a note that the completion
  date for both is the same.
- **The withheld amount is a percentage of the accepted tendered value, per milestone, and it
  is recoverable** — released when a later milestone is met. So it is a cash-flow *timing*
  event, not a cost, and modelling it as a penalty would overstate the loss while understating
  the squeeze. Typically 2.5% per milestone, which on a two-milestone job is 5% of the contract
  hanging on dates.

**Minimum value of work for an interim certificate (Clause 7).** Present in all ten, and
**stated separately for civil and electrical** — CHAURA is ₹21 lakh civil / ₹5 lakh E&M,
QUITER ₹47 lakh / ₹9 lakh, NIT-29 a single ₹3.5 lakh. So the field is **per work part, not one
number.** It sets the billing rhythm and therefore the depth of the cash trough: the contractor
funds roughly a bill and a half at all times. The clause also says the Engineer-in-Charge *may*
release less at his discretion, subject to funds — which the plan must treat as what it is, a
possibility the contractor cannot rely on and must not plan against.

**Clause 7A — the gate on the first rupee.** *"No Running Account Bill shall be paid for the
work till the applicable labour licenses, registration with EPFO, ESIC and BOCW Welfare Board
... submitted."* This is not a deduction, it is a **precondition on being paid at all**, and a
contractor who has not completed those registrations has a cash flow of pure outflow for as
long as it takes. It belongs in the plan as a dated prerequisite before the first receipt, not
as a note.

**Defect liability / maintenance period.** Decides when the retention comes back — six to
twelve months *after* completion. Money earned and unusable is still money the contractor does
not have, and a plan showing the balance going positive at handover is lying about a year of it.

**Date of commencement.** `Number of days from the date of issue of letter of acceptance for
reckoning date of start` — **10 days in all ten notices.** Consistent enough to be a confident
default and far too load-bearing to hardcode: the entire calendar hangs off it, and it is not
the bid opening date.

### 2.3 Present in the standard form, but not what this corpus needed

Extract these — a tender that offers them changes the answer enormously — but do not build the
engine as though they were normal, because in ten notices they were not:

- **Mobilisation advance (Clause 10B): offered in none of the ten.** A plan that assumed one
  would understate the funding requirement by the whole advance. That absence is precisely why
  the working-capital peak (§6.8) is the headline number rather than a footnote.
- **Secured advance on material at site: not offered here**, and note that **clause numbering
  is not stable across divisions** — Clause 10A is the site-laboratory testing equipment list
  in NIT-29, not a secured advance. **Match on the label text, never on the clause number.**
- **Price escalation (Clause 10CC): "Not Applicable" in all ten**, which follows from durations
  of twelve months and under. Extract the flag; expect it to be off.
- **Water and electricity.** These notices impose *metering and non-wastage obligations*, not a
  recovery percentage. The 1%–1.5% recovery that appears in some CPWD contracts is not in this
  corpus, so it is an org setting with a default of zero, not an assumed deduction.
- **Labour cess (BOCW, 1%).** Never stated as a percentage in any of the ten, because it is
  statutory rather than tendered. It is an org setting with a statutory default — not an
  extraction target that will be forever reported as missing.

### 2.4 Not in the tender at all, and must be asked for

**The quoted percentage.** A percentage-rate bid is quoted as *X% above or below* the estimated
cost. The BOQ carries DSR rates; the contractor is paid those rates adjusted by his own quote.
**If the plan does not apply the quoted percentage, every rupee in it is wrong** — and by the
exact margin that decides whether the job makes money. At the bid-stage screen this is the
central input, because varying it is the whole point of the exercise. It is not extractable,
because at the time the NIT is read it has not been decided yet.

Also asked for, not read: the intended commencement date, whether a mobilisation advance will
actually be taken, and the contractor's own overhead percentage.

### 2.5 How the new fields get extracted

The existing parser is deterministic regex tuned over a corpus, and it stays that way for
anything that appears as a labelled figure — `completion_days`, the recovery percentages, the
minimum interim value. Those are §2.2's easy half.

The milestone table and the advance clauses are the hard half: they are tables whose column
headings differ per division, and prose whose sentence shape differs per drafter. Regex will
get some and silently miss others.

**The split to hold to: a model may be used to read prose into fields; nothing but Java
arithmetic ever produces a number the plan asserts.** A language model reading "3/8th of the
work in half the time" out of a paragraph is doing the job it is good at, and its output lands
in the preview as fields a person confirms — the same two-step the NIT import already uses,
for the same reason. A model computing a cash flow is producing a confident number nobody can
trace, which is worse than no number. `NitExtraction` already makes every field nullable and
carries a `warnings` list precisely so an uncertain reading can be honest; extend that, do not
work around it.

### 2.6 There is a corpus, and it should be a fixture

`docs/NIT documents/` holds ten real notices, 126 to 257 pages each, and **every one extracts
cleanly as text** — no OCR needed. They span two-month repair jobs to twelve-month building
works, single and composite schedules, and both milestone formats.

Seven of the ten are checked in as fixtures under `backend/src/test/resources/nit/pdf/` and are
what the tests actually run against; the folder itself is deliberately not committed, because
thirty megabytes of source PDFs in git is a cost that never comes back. The three left out —
DHYAN, QUITER and DOCUMENT-3 — are format duplicates of `almora-35-chaura-bop` and
`almora-25-asi-mess`, so nothing is uncovered by their absence. Adding one is a matter of
copying the PDF in, generating its `nit/text/<name>.pypdf.txt` page dump, and transcribing its
Schedule F into `ScheduleFCorpusTest` — which that test will demand, since it fails on any
fixture it has no expectations for.

That is a test corpus, and the parser work in §11.1 should be driven by it the way
`NitPatterns` was driven by the corpus before it: a pattern that looks needlessly specific is
usually specific because a general version matched the wrong thing on a real notice. The
extraction target to hold to is **ten of ten on milestones, completion days, date of start and
the Clause 7 minimums** — all four are present and unambiguous in all ten — with everything in
§2.3 allowed to come back null.

---

## 3. Different kinds of work

The user's point is the load-bearing one: a school building, a road, an internal water supply
scheme and an E&M package are four different plans, and a single sequencing rule flatters all
four and fits none.

**Work-type profile** — seeded, org-editable master data, one row per kind of work:

| Profile | Phases run by | Notes |
|---|---|---|
| Building — new construction | substructure → superstructure by floor → finishes | the default; floors are the natural phase |
| Building — repair & maintenance | scattered small packages, largely parallel | CPWD's commonest tender and the worst fit for floor-based phasing |
| Road / pavement | chainage | monsoon-critical; earthwork and bituminous work stop in rain |
| Water supply & sanitary | by line / by block | trenching is weather- and permission-bound |
| Electrical / E&M | follows civil, cannot lead it | phases are *dependent* on the civil plan, not parallel to it |
| Horticulture & landscape | season-bound | planting windows are not negotiable |
| Composite (civil + E&M) | two interlocked curves | the case `work_part` already exists for |

The profile carries the defaults the engine needs: category precedence and permitted overlap,
crew-front density limits, monsoon sensitivity per category, default staff establishment by
contract value, and default overhead percentage.

**Detection, then confirmation.** The profile is guessed from the BOQ category mix — which
`BoqClassifier` already produces — plus keywords in the work name, and then shown to the user
to accept or change. Never silently applied. The classifier is good and the tender is a scanned
government PDF; a plan built on a wrong guess about what kind of job this is would be wrong in
a way no individual number reveals.

---

## 4. The norms that do not exist yet

The chain the whole engine runs on is: **BOQ line → category → norms → time, men, material.**

Two of the three links exist. `boq_items.category` is populated by the classifier and shares a
string space with `material_consumption_norms.work_category`, which was designed for exactly
this. What is missing is everything about labour and time.

**`labour_productivity_norms`** — man-days of a given trade per unit of work in a category.
Two masons and three helpers per 10 cum of brickwork per day is the shape of it. Keyed the same
way the material norms are (`work_category` + optional `work_sub_type`) so one classification
serves both, and referencing `skill_categories` so the skilled/unskilled split falls out of
data already held. Sourced from CPWD's Analysis of Rates, marked as such — `material_consumption_norms.source`
already has a `CPWD_AOR` value waiting for its twin.

**`work_sequence_norms`** — precedence rank per category per profile, and how much a successor
may overlap its predecessor. Plaster does not precede masonry. Painting does not precede
plaster. Some overlap is normal and some is impossible, and the difference is per trade.

**`material_lead_times`** — ordering lead days and a buffer per material, plus shelf life and
whether the material is storable at all. Cement has three months of shelf life and ready-mix
concrete has ninety minutes; a procurement plan that treats them alike will either spoil stock
or strand a pour.

All three are org-scoped master data with seeded starting values, editable, and the seeded
values are *starting* values — a contractor's own productivity is his competitive advantage and
the numbers he cares most about correcting. This is `V14`'s argument again: ship a catalogue so
the feature works on day one for an organisation that has entered nothing, and guard it so it
never overwrites what an organisation has learned.

---

## 5. One engine, two front doors

The user asked for two entry points, and they are genuinely different jobs:

- **Post-award**, from the project: "we won it, plan the execution." Reads the saved project,
  its BOQ, its sites, the org's real wage rates and staff salaries.
- **Pre-award**, standalone: "should we bid this, and at what percentage?" Reads an uploaded
  NIT that belongs to no project. Varying the quoted percentage and watching the funding peak
  move *is the entire exercise*.

These must not be two engines. The rule is the one the parser already follows — **the engine
is pure and takes a `PlanInput` record; two adapters build that record.** One adapter reads a
project and its BOQ; the other reads a transient `NitExtraction` and the user's answers. The
engine has no repository, no `SiteAccessGuard`, no clock of its own. It is testable against a
hand-written input, which is the only way the arithmetic below will ever be trusted.

A pre-award plan is stored with a null `project_id` and can be **attached** to a project when
the tender is won, which is the moment the bid case becomes the execution baseline. Nothing is
recomputed on attachment; what was decided is what is kept.

---

## 6. The engine

### 6.1 Work packages

BOQ lines are grouped into work packages by `category` × `work_part` (× floor or chainage where
the profile says so). A package is the planning unit because 2,000 BOQ lines are too fine to
schedule and a trade is too coarse to sequence. Each package carries its value, its quantity by
unit, and the lines it covers so every figure traces back to rows a person can read.

Synthetic `UNALLOCATED` reconciliation lines are **excluded from the work**, because nothing
can be charged against them — but their value is **kept in the cash flow**, because the
department will still pay against that portion of the contract. Dropping them entirely would
understate the receipts; scheduling them would invent work that has no description.

### 6.2 Duration, and the constraint that is usually forgotten

Duration per package is not value divided by time. It is:

```
gang-days      = quantity ÷ daily output of one gang        (from the productivity norm)
duration       = gang-days ÷ number of gangs deployed
```

…and the number of gangs is capped. **A plan must be constrained by the working front, not
just by arithmetic.** Dividing the work by the time available always yields a head count, and
on a compressed programme it yields one no site can physically hold — four hundred masons on a
two-hundred-square-metre slab. The profile carries a crew-density limit per category and the
engine respects it.

When the cap binds and the work still does not fit the time, that is not an error to swallow.
It is the finding (§6.3).

### 6.3 Phases — adopt the tender's phasing, do not invent one

This is the section the corpus rewrote.

The obvious design is to sequence the work by precedence and then check the result against the
milestone percentages. That is backwards. **The tender already contains a phasing, written by
the department, and the plan's job is to adopt it and resource it** — because that phasing is
the one the contract is enforced against, and any better schedule that misses it is worse.

So milestones are read first, and they drive:

- **Physical milestones** name their activities in the vocabulary `BoqClassifier` already sorts
  BOQ lines into — *retaining wall, plum concrete, excavation, lean concrete, RCC foundation,
  plinth beams, backfilling, grade slab*. Each phrase maps to a category, and its stated
  percentage (*"100% of RRM, 25% of other development works"*) maps to a target quantity within
  it. **The phase boundaries and their contents both fall out of the tender**, and the engine
  supplies only what the tender does not: durations, gangs, material and money.
- **Financial milestones** give a cumulative value and a date and nothing else, so the engine
  sequences by precedence to hit the value by the date — the design above, correctly demoted to
  the fallback it is.
- **Where a milestone offers both, joined by "or",** both are carried: the physical description
  drives the schedule, and the financial equivalent is the test the plan is measured against,
  because that is the one the department will apply.
- **Where a tender states no milestones**, the work-type profile's default split is used and
  labelled as an assumption, in `plan_assumptions` like every other substitution.

Time units are normalised on the way in — one table mixes days and months freely.

Inside the phases, everything is bucketed by **calendar month**, because every other monthly
thing in this system already is: `period_locks.year_month`, payroll, the billing rhythm. A plan
on its own week grid would have to be re-bucketed by hand every time it was compared to
anything. On a two-month tender the buckets are weeks, and the profile says which.

**When the plan does not fit, say so.** If a milestone cannot be met at the crew-density cap,
the plan reports it — naming the milestone, the shortfall, and the withheld percentage of the
tendered value that rides on it, which the milestone table states directly (§2.2). Pre-award,
*"this tender cannot be executed in the time allowed at normal crew densities, and 2.5% of the
contract is withheld at the milestone you will miss"* is the single most valuable sentence the
platform can produce. Burying it under a schedule that pretends otherwise is the worst thing
this feature could do.

### 6.3.1 The plan is also a bid document

NIT-29 says it outright:

> Intending bidder may submit phasing of activities/milestones based on their resources and
> methodology at the time of bidding … These shall be formed part of the agreement after
> approval of the accepting authority, otherwise it would be assumed that agency agrees with
> the above table.

**The department is asking for exactly the artefact this feature produces**, it becomes part of
the agreement, and the default if nothing is submitted is that the contractor is bound to the
department's table unamended. That raises the stakes on the output in two ways.

First, it must be **exportable as a submission-grade document** — the contractor's phasing, laid
out against the department's own milestone table, in the department's order. This is a printed
deliverable, not a dashboard, and it should be designed as one.

Second, it means a plan that finds the stipulated milestones infeasible (§6.3) has somewhere to
go other than a warning: the contractor can propose the phasing his resources actually support,
at bid time, when it is still negotiable. That is the difference between the platform telling
somebody they are going to lose money and helping them not to.

### 6.4 Labour

Per month, per trade: man-days from the quantity scheduled in that month times the productivity
norm. Head count is man-days ÷ working days in the month, which is what a supervisor can act
on. Skilled and unskilled split via `skill_categories.is_skilled`.

Costed at the org's own current wage rate for the trade where one exists. Where none exists,
the state minimum wage, **named in the assumptions list** rather than quietly substituted.

Where the site is flagged `uses_outsourced_labour`, the plan reports the trades as head counts
and does not price them, because the supplier bills for the work. This is the existing
invariant and the plan does not get an exception: nothing multiplies those hours by a rate.

The labour curve is also the staffing curve for the site establishment — engineer, supervisor,
storekeeper — whose salaries come from `staff_profiles` where the org has entered them.

### 6.5 Material: two curves, not one

Per month the engine produces a **requirement** — quantity scheduled × consumption norm ×
(1 + wastage). Then it produces a **procurement** curve by shifting each requirement earlier by
that material's lead time plus buffer.

**These are different curves and conflating them is how a site runs out of cement while the
plan says it has plenty.** The requirement curve answers "what will be consumed in March"; the
procurement curve answers "what has to be ordered in January so March can happen". The user
asked for material required *in advance for coming phases*, and the procurement curve is that
answer.

The procurement curve is then capped by shelf life and by store capacity — you cannot buy the
whole job's cement in month one — which means on a long job the same material is ordered
repeatedly, and the plan says when.

This is also where the existing invariant does real work: **purchase is not consumption.**
Cash leaves on the procurement curve. Cost of work accrues on the requirement curve. A plan
that put both on the same date would show a project that never has money and never has stock.

### 6.6 Money out

Per month:

- wages, from §6.4
- material purchases, on the **procurement** curve, at rate
- staff salaries, from the establishment the profile sets for the contract value
- plant: hire or own, by category — and the existing rule holds, plant is held, not consumed,
  so no equipment figure enters a material curve
- commute and transport, fuel, at the org's own rates
- **site setup, in month zero**: site office, store shed, labour huts, water and power
  connection, boundary and hoarding, first tools. The line item everybody forgets and
  everybody pays.
- overheads: the org's percentage, insurance, bank guarantee commission
- statutory: labour cess, and the GST timing gap on inputs

### 6.7 Money in

A running account bill is raised when executed value since the last bill reaches the **minimum
interim value for that work part** — civil and E&M have their own thresholds and therefore
their own billing rhythms — or at a milestone, whichever comes first.

```
gross bill      = executed value at contract rates × (1 ± quoted percentage)
net receipt     = gross − security deposit − income tax TDS − GST TDS
                        − labour cess − any milestone withholding in force
received on     = bill date + payment lag        … but not before Clause 7A is satisfied
```

Three things that formula is carrying:

- **Milestone withholding is recoverable**, so it leaves on the bill that follows a missed
  milestone and comes back on the bill that follows the milestone being met. Modelled as a
  timing event, never as a cost.
- **Clause 7A gates the first receipt entirely.** Until the EPFO, ESIC and BOCW registrations
  are in, no bill is paid regardless of work done. The plan dates that prerequisite and shows
  the outflow-only stretch it creates if it slips.
- **The Engineer-in-Charge's discretion to release less is not planned against.** The clause
  offers it and denies the contractor any right to it, so it can only ever make the plan
  pessimistic, which is the safe direction.

The payment lag — measurement, MB recording, checking, passing, payment — is an org setting
with an honest default, not a constant. It is the number the plan is most sensitive to and the
one a contractor knows best from his own division.

### 6.8 Working capital — the headline

Cumulative net cash, month by month. What comes out of it:

- **Peak funding requirement** — the deepest point of the cumulative trough, and the month it
  happens. *This is the answer to "how much money should be required to start the work", and it
  is not the first month's cost.* Money keeps going out through the whole payment lag, so the
  trough is typically two to three months deep, not one.
- **Money before day one** — EMD, performance guarantee margin and commission, site setup.
- **Break-even month** — when cumulative receipts overtake cumulative spend.
- **Locked at the end** — retention released only after the defect liability period. Earned,
  and not available.
- **The recycling schedule the user asked for**: each net receipt matched against the phase it
  funds, so it is visible when a receipt arrives too late for the phase depending on it — which
  is the actual mechanism by which contractors run out of money on profitable jobs.

### 6.9 Sensitivity, because every number above rests on a norm

A single-point plan reads as a promise. Three scenarios read as what it is. At minimum:

- payment lag at the default, and at ninety days
- productivity at norm, and twenty percent below
- the quoted percentage as bid, and two points tighter

Cheap to compute — the engine is pure and re-runs on a varied input — and it is the difference
between a plan somebody trusts and a plan somebody believes.

---

## 7. Honest limits

Stated in the output, not buried here:

- Productivity and consumption norms are catalogue values until the org's own history replaces
  them. After two or three completed projects the platform can derive real ones from verified
  attendance and the stock ledger — **that is the version of this feature worth building
  second**, and it is why every plan row stores which norm produced it.
- The engine plans work, not weather, permissions, or a department that hands the site over
  late. Monsoon is modelled from the profile; encumbrance is not modelled at all.
- Rates are the BOQ's rates. Market movement between bid and execution is out of scope unless
  the escalation clause applies.
- Extraction from a scanned PDF is uncertain by nature. Every plan names the fields that were
  read rather than confirmed.

---

## 8. Data model

Additive only, and Flyway owns the schema. Latest applied is `V28`.

### 8.1 `V29__nit_commercial_terms.sql`

Additive columns on `nit_documents` for §2.2 — `completion_days`, `start_reckoning_days`
(10 in all ten notices), `defect_liability_months`, `clause_7a_applicable`,
`mobilisation_advance_percent` / `_interest_percent` / `_recovery_basis`,
`secured_advance_allowed`, `escalation_clause_applicable`, `contract_type`. All nullable,
because a tender that does not say must not be made to say — and per §2.3, several of these
will be null on most notices, which is a correct reading rather than a failed one.

`nit_interim_minimums` — `nit_document_id`, `work_part`, `minimum_value`. A child table because
civil and E&M carry different thresholds and a single column cannot hold both.

`nit_milestones` — `nit_document_id`, `sequence`, `description`, `time_allowed_days`
(normalised from mixed days/months), `work_percent` **nullable**, `financial_percent`
**nullable**, `withheld_percent`, `work_part`. Both percentage columns are nullable because a
milestone may be physical, financial, or physical *or* financial, and flattening the three into
one number would throw away the description that makes §6.3 possible.

Deductions that are statutory rather than tendered — labour cess, TDS rates, and a water and
electricity recovery defaulting to zero — belong on an **org settings** row beside
`expense_settings`, not on the tender. They do not vary per notice and a column that is null in
every row is a column that will be reported as a missing extraction forever.

### 8.2 `V30__planning_norms.sql`

`work_type_profiles`, `labour_productivity_norms`, `work_sequence_norms`,
`material_lead_times` — §3 and §4. Org-scoped, seeded by the same `WHERE NOT EXISTS` guard
`V14` uses so an organisation's own numbers are never overwritten.

### 8.3 `V31__execution_plans.sql`

```
execution_plans          the baseline header: project_id NULLABLE (§5), work_type_profile_id,
                         quoted_percent, commencement_date, revision, superseded_at,
                         baselined_at, source (PROJECT | NIT_UPLOAD), scenario
plan_phases              phase windows, milestone linkage, target value and percent
plan_work_packages       category × work_part, value, dates, gangs, the BOQ lines covered
plan_labour_demand       month × trade × man-days × head count × cost
plan_material_demand     month × material × required qty × order-by date × procurement qty
plan_cash_flow           month × inflow × outflow by head × cumulative
plan_assumptions         every substituted default and every norm used, with its source
```

`plan_assumptions` is not documentation. It is what makes a plan auditable six months later
when the question is *why did it say we needed forty lakh*, and it is what lets a re-plan on
better norms be compared to the old one rather than merely replacing it.

Revisions **supersede**, never overwrite — the pattern `material_estimates` already
establishes, for the same reason: last month's variance must not silently rewrite itself.

### 8.4 The one place the plan writes forward

`boq_items` has carried `planned_start_date`, `planned_completion_date` and four
`budget_*_cost` columns since `V1`. They are mapped on the entity and **nothing has ever
written them.** V1 provisioned a planner and the planner was never built.

On baselining, the plan fills them. That is the whole of its write-forward, and it is legitimate
because those columns are the project's own statement of intent, not a ledger. It also means
every existing screen that reads a BOQ item starts showing plan against actual with no new
join.

Optionally, and behind a user's explicit confirmation, the plan also writes
`material_estimates` at the `TENDER_BOQ` level with `derived_from_norm = true` and
`source_ref` naming the plan — which is exactly what that column was for, and what makes the
material variance report work from day one instead of after somebody types in an estimate.

### 8.5 Permissions

New rows, not constants: `planning:read`, `planning:generate`, `planning:baseline`,
`planning:norms:write`. Admin and project manager generate; engineer and accountant read;
only an administrator edits norms, because a productivity norm quietly changed is every future
plan quietly changed.

---

## 9. Module and API

`modules/planning/` with `api/ domain/ repository/ service/ engine/`. The `engine/` package is
pure — no Spring, no repositories, static entry points, mirroring `tender/parser/`.

Cross-module reads go through lookups and nothing else:

- `BoqLookup`, `SiteLookup`, `UnitLookup`, `MaterialLookup`, `LabourLookup` — exist
- `InventoryLookup` — extend for consumption norms, or add `NormLookup` beside it
- **`NitLookup`** — new, in `tender`. Planning must not touch `NitDocumentRepository`.

Endpoints under `/api/v1/planning`:

```
POST   /plans/preview            generate without persisting — the scenario slider
POST   /plans                    persist a generated plan (draft)
POST   /plans/{id}/baseline      freeze it, and write §8.4
POST   /plans/{id}/attach        bind a pre-award plan to a won project
GET    /projects/{id}/plan       the live baseline
GET    /plans/{id}/variance      derived, per call, never stored
POST   /plans/from-nit           upload → extract → generate, no project (§5)
```

`preview` persists nothing, which is what makes varying the quoted percentage cheap. `baseline`
is the irreversible act and the one that is audited.

---

## 10. Frontend

`features/planning/` — `api.ts`, Zod schemas, routes, components.

**The button.** On `ProjectDetailPage`, beside the BOQ. It opens a wizard: confirm the work
type → confirm the terms read from the tender → enter the quoted percentage and start date →
review the generated plan → baseline it. The same preview-then-confirm shape as
`NitImportPanel`, because it is the same situation: a good reading of an uncertain document
that a person has to own before it is written.

**The standalone screen.** `/planning/new` — upload an NIT, get a strategy, no project created.
Its centre of gravity is the scenario controls, not the schedule: quoted percentage, payment
lag, productivity. The output a bidder wants is one number that moves as he drags one slider.

Everything here is a desktop screen for the office and needs no offline path. It reads figures
the service worker is explicitly told not to cache, and a stale plan is worse than a missing
one.

---

## 11. Delivery order

Each step ends with working code, migrations, tests and seed data, per `docs/08-roadmap.md`.

1. **`V29` + parser extensions, driven by the corpus in `docs/NIT documents/` (§2.6).**
   Milestones, `completion_days`, `start_reckoning_days`, the per-work-part Clause 7 minimums.
   Target ten of ten on those four. Testable against real notices on its own, and useful on its
   own — the tender record gets better whether or not the planner ships.
2. **`V30` + norms, seeded and editable.** Also standalone value: `material_estimates` can be
   derived from norms the moment the norms exist.
3. **The engine, pure, against hand-written inputs.** No persistence, no endpoints. This is
   where the arithmetic earns trust and it should be the most heavily tested code in the repo.
4. **`V31`, persistence, baselining, and the write-forward to `boq_items`.**
5. **The project button.**
6. **The standalone bid screen and the scenario controls.**
7. **The submission document (§6.3.1)** — the contractor's phasing printed against the
   department's milestone table, for filing with the bid.
8. **Variance**, once a baselined plan and real ledger data coexist.
9. *Later, and the reason the norms carry a source:* **derive the org's own norms** from its
   verified attendance and stock ledger, and re-plan against what this contractor actually
   achieves rather than what the catalogue says.
