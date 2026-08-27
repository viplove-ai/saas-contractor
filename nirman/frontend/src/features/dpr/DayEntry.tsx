import AddIcon from '@mui/icons-material/Add';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { ReferenceNotice } from '../../shared/ReferenceNotice';
import { useAuth } from '../auth/AuthContext';
import {
  useAddExpense,
  useAddFieldMaterial,
  useExpenseCategories,
  useIssueMaterial,
  useLabourCounts,
  useLabourSuppliers,
  useMaterials,
  useReceiveMaterial,
  useSaveLabourCounts,
  useSkillCategories,
  useStores,
  useUnits,
} from './api';
import { CorrectMaterialNameDialog } from '../inventory/CorrectMaterialNameDialog';
import { RaiseCorrectionDialog } from '../inventory/RaiseCorrectionDialog';
import { MATERIAL_NOT_LISTED } from './types';

/**
 * The parts of the day a supervisor can enter <b>from the report itself</b>.
 *
 * <p>The premise of the whole screen: he should not have to remember which of four screens a
 * fact belongs on. If he entered the material at the gate this morning, the report already
 * shows it and these cards are empty boxes he walks past. If he entered nothing all day —
 * which is the normal day — he types it here at six o'clock and the report is the only screen
 * he opened.</p>
 *
 * <p><b>Each card posts to the real endpoint.</b> Material typed here becomes a goods receipt
 * in the stock ledger, spending becomes an expense the office approves, head counts become
 * the site's own record for the day. None of it is stored on the report as a private figure,
 * because then the ledger and the report would be two different answers to the same question
 * and the office would have to guess which one to believe.</p>
 *
 * <p>Every card saves on its own and says so. Four separate saves rather than one is
 * deliberate: a receipt that went through is not undone because the expense after it was
 * flagged as a duplicate — they are unrelated facts about the same day.</p>
 */

interface DaySectionProps {
  siteId: string;
  date: string;
  /** The month is closed; the cards render read-only rather than collecting a refusal. */
  locked?: boolean;
  /** Re-read the day after a save, so the figures above the cards move as he types. */
  onSaved: () => void;
}

// ------------------------------------------------------------------ outsourced labour

interface CountRow {
  key: string;
  skillCategoryId: string;
  /**
   * Who sent the men. Asked again now that there is somewhere to answer from: labour
   * suppliers live on the one supplier register (V23), so the name picked here is a firm
   * with an account, an address and a history rather than a free-text note.
   *
   * <p>Still optional. A site that uses one supplier every day and never says so is
   * recording a true count with a name missing, and refusing the save over it would cost
   * the count.</p>
   */
  labourSupplierId: string;
  headCount: string;
  hours: string;
}

/** What the supervisor says about a supplier for the day, keyed by the supplier's id. */
interface SupplierAnswer {
  supplierPresent: boolean;
  representativeName: string;
}

function emptyCount(): CountRow {
  return {
    key: crypto.randomUUID(),
    skillCategoryId: '',
    labourSupplierId: '',
    headCount: '',
    hours: '',
  };
}

/**
 * Head counts per trade, for a site whose work is let to an outside labour supplier.
 *
 * <p>Only drawn when the site is set up that way — on a site with its own muster roll this
 * box would invite somebody to type the same men twice, once as attendance and once as a
 * count.</p>
 *
 * <p>Hours are per man and optional: eleven masons for eight hours is what the gate knows,
 * and a day where nobody noted hours has to be able to say so rather than record a zero.
 * <b>Still no money.</b> There is nowhere to type a rate and nothing here multiplies by one —
 * these men are not on our payroll, and a wage figure derived from a head count would be a
 * guess wearing the clothes of a figure.</p>
 */
export function OutsourcedLabourCard({ siteId, date, locked, onSaved }: DaySectionProps) {
  const counts = useLabourCounts(siteId, date);
  const categories = useSkillCategories();
  const suppliers = useLabourSuppliers();
  const save = useSaveLabourCounts();
  const [rows, setRows] = useState<CountRow[]>([]);
  const [answers, setAnswers] = useState<Record<string, SupplierAnswer>>({});
  const [error, setError] = useState<string | null>(null);
  const [savedAt, setSavedAt] = useState<string | null>(null);

  // Reloads whenever the server's answer changes — including after a save, which is what
  // makes a row deleted on another device disappear here rather than being written back.
  useEffect(() => {
    if (!counts.data) {
      return;
    }
    setRows(
      counts.data.lines.map((line) => ({
        key: crypto.randomUUID(),
        skillCategoryId: line.skillCategoryId,
        labourSupplierId: line.labourSupplierId ?? '',
        headCount: String(line.headCount),
        hours: line.hours == null ? '' : String(line.hours),
      })),
    );
    setAnswers(
      Object.fromEntries(
        (counts.data.suppliers ?? []).map((supplier) => [
          supplier.labourSupplierId,
          {
            supplierPresent: supplier.supplierPresent,
            representativeName: supplier.representativeName ?? '',
          },
        ]),
      ),
    );
  }, [counts.data]);

  if (!counts.data?.enabled) {
    return null;
  }

  const readOnly = locked || counts.data.periodLocked;
  const total = rows.reduce((sum, row) => sum + (Number(row.headCount) || 0), 0);
  /*
    The suppliers the rows currently name, with their men added across trades. Derived from
    what is on screen rather than from what was saved, so ticking a supplier onto a line
    brings his presence question up immediately instead of after a save.
  */
  const namedSuppliers = Object.entries(
    rows.reduce<Record<string, number>>((byId, row) => {
      if (!row.labourSupplierId) return byId;
      byId[row.labourSupplierId] =
        (byId[row.labourSupplierId] ?? 0) + (Number(row.headCount) || 0);
      return byId;
    }, {}),
  ).map(([id, men]) => ({
    id,
    men,
    name: suppliers.data?.find((candidate) => candidate.id === id)?.name ?? 'Supplier',
  }));
  // Only the rows that carry hours. A trade counted without them contributes nothing here
  // rather than dragging the total down as a zero.
  const manHours = rows.reduce(
    (sum, row) => (row.hours === '' ? sum : sum + (Number(row.headCount) || 0) * Number(row.hours)),
    0,
  );

  async function submit() {
    setError(null);
    setSavedAt(null);
    const lines = rows
      .filter((row) => row.skillCategoryId && row.headCount !== '')
      .map((row) => ({
        skillCategoryId: row.skillCategoryId,
        labourSupplierId: row.labourSupplierId || undefined,
        headCount: Number(row.headCount),
        // Blank is "nobody said", which the server stores as no hours at all. Sending a zero
        // would put "they worked no hours" on the report in its place.
        hours: row.hours === '' ? undefined : Number(row.hours),
      }));
    // Only the suppliers the day actually names. Sending an answer for somebody whose last
    // line was just deleted would leave a presence row for a gang that is not there.
    const named = [...new Set(lines.map((line) => line.labourSupplierId).filter(Boolean))];
    const supplierAnswers = named.map((id) => ({
      labourSupplierId: id as string,
      supplierPresent: answers[id as string]?.supplierPresent ?? false,
      representativeName: answers[id as string]?.representativeName || undefined,
    }));
    try {
      await save.mutateAsync({ siteId, date, lines, suppliers: supplierAnswers });
      setSavedAt(new Date().toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' }));
      onSaved();
    } catch (caught) {
      setError(apiErrorDetail(caught));
    }
  }

  return (
    <Card
      title="External labour"
      hint="Men on site today from outside the muster roll, counted by trade. Hours are per man and optional. No muster roll, no wages — their supplier bills for the work."
      right={
        <Stack direction="row" spacing={1}>
          <Chip size="small" label={`${total} on site`} />
          {manHours > 0 && <Chip size="small" variant="outlined" label={`${manHours} man-hours`} />}
        </Stack>
      }
    >
      {error && <Alert severity="error">{error}</Alert>}
      {readOnly && <Alert severity="info">This month is closed. The counts cannot be changed.</Alert>}

      <Stack spacing={1.5}>
        {rows.map((row) => (
          <Stack key={row.key} direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
            <TextField
              select
              size="small"
              label="Trade"
              disabled={readOnly}
              value={row.skillCategoryId}
              onChange={(event) =>
                setRows((current) =>
                  current.map((candidate) =>
                    candidate.key === row.key
                      ? { ...candidate, skillCategoryId: event.target.value }
                      : candidate,
                  ),
                )
              }
              sx={{ minWidth: 180 }}
            >
              {(categories.data ?? []).map((category) => (
                <MenuItem key={category.id} value={category.id}>
                  {category.name}
                </MenuItem>
              ))}
            </TextField>
            {/*
              Who sent them. Optional, and deliberately so: a site that uses one supplier
              every day and never names him is still recording a true count, and refusing
              the save over a missing name would cost the count.
            */}
            <TextField
              select
              size="small"
              label="Supplier"
              disabled={readOnly}
              value={row.labourSupplierId}
              onChange={(event) =>
                setRows((current) =>
                  current.map((candidate) =>
                    candidate.key === row.key
                      ? { ...candidate, labourSupplierId: event.target.value }
                      : candidate,
                  ),
                )
              }
              sx={{ minWidth: 170 }}
            >
              <MenuItem value="">Not named</MenuItem>
              {(suppliers.data ?? []).map((supplier) => (
                <MenuItem key={supplier.id} value={supplier.id}>
                  {supplier.name}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              size="small"
              type="number"
              label="Men"
              disabled={readOnly}
              value={row.headCount}
              onChange={(event) =>
                setRows((current) =>
                  current.map((candidate) =>
                    candidate.key === row.key
                      ? { ...candidate, headCount: event.target.value }
                      : candidate,
                  ),
                )
              }
              sx={{ maxWidth: 110 }}
              inputProps={{ min: 0 }}
            />
            {/*
              Per man, and it may be left empty. "Eight" here means each of the eleven masons
              worked eight hours; the man-hours on the card are the product. Left blank, the
              day records no hours at all rather than a zero — nobody said is not none.
            */}
            <TextField
              size="small"
              type="number"
              label="Hours each"
              disabled={readOnly}
              value={row.hours}
              onChange={(event) =>
                setRows((current) =>
                  current.map((candidate) =>
                    candidate.key === row.key
                      ? { ...candidate, hours: event.target.value }
                      : candidate,
                  ),
                )
              }
              sx={{ maxWidth: 130 }}
              inputProps={{ min: 0, max: 24, step: 0.5 }}
            />
            <IconButton
              aria-label="Remove trade"
              disabled={readOnly}
              onClick={() => setRows((current) => current.filter((c) => c.key !== row.key))}
            >
              <DeleteOutlineIcon />
            </IconButton>
          </Stack>
        ))}
      </Stack>

      {/*
        Who was standing over the gang. Asked once per supplier rather than once per trade —
        the same supplier on three lines is one man, and answering for him three times is a
        question that can disagree with itself. It is the thing a site argues about a
        fortnight later: a gang left to itself for a week is how work goes wrong.
      */}
      {namedSuppliers.length > 0 && (
        <Stack spacing={1} sx={{ pt: 1 }}>
          <Typography variant="body2" color="text.secondary">
            Was the supplier on site today?
          </Typography>
          {namedSuppliers.map((supplier) => (
            <Stack
              key={supplier.id}
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1.5}
              alignItems={{ sm: 'center' }}
            >
              <FormControlLabel
                sx={{ minWidth: 240 }}
                control={
                  <Switch
                    disabled={readOnly}
                    checked={answers[supplier.id]?.supplierPresent ?? false}
                    onChange={(_, next) =>
                      setAnswers((current) => ({
                        ...current,
                        [supplier.id]: {
                          supplierPresent: next,
                          representativeName: current[supplier.id]?.representativeName ?? '',
                        },
                      }))
                    }
                  />
                }
                label={`${supplier.name} · ${supplier.men} men`}
              />
              {/* Only once he says somebody was there — a name box beside an off switch is
                  a question about a person who did not come. */}
              {(answers[supplier.id]?.supplierPresent ?? false) && (
                <TextField
                  size="small"
                  label="Who came"
                  disabled={readOnly}
                  value={answers[supplier.id]?.representativeName ?? ''}
                  onChange={(event) =>
                    setAnswers((current) => ({
                      ...current,
                      [supplier.id]: {
                        supplierPresent: true,
                        representativeName: event.target.value,
                      },
                    }))
                  }
                  helperText="Leave blank if the supplier himself came"
                  sx={{ minWidth: 220 }}
                />
              )}
            </Stack>
          ))}
        </Stack>
      )}

      {!readOnly && (
        <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap>
          <Button startIcon={<AddIcon />} onClick={() => setRows((c) => [...c, emptyCount()])}>
            Add a trade
          </Button>
          <Button variant="outlined" onClick={submit} disabled={save.isPending}>
            Save counts
          </Button>
          {savedAt && (
            <Typography variant="body2" color="text.secondary" alignSelf="center">
              Saved at {savedAt}.
            </Typography>
          )}
        </Stack>
      )}
    </Card>
  );
}

// ------------------------------------------------------------------ material

interface MaterialRow {
  key: string;
  materialId: string;
  quantity: string;
  rate: string;
  /** What he called it, when the catalogue has no name for what turned up. */
  newName?: string | undefined;
  /** Only for a material he is naming: the picked one brings its own base unit. */
  unitId?: string | undefined;
}

function emptyMaterial(): MaterialRow {
  return { key: crypto.randomUUID(), materialId: '', quantity: '', rate: '' };
}

/**
 * What came in and what went out, as two short lists.
 *
 * <p>They are separate cards and separate documents because they are separate facts.
 * Material arriving is inventory; material issued to the work face is cost. Anyone who adds
 * the two has counted the same cement twice.</p>
 *
 * <p>A receipt needs a rate and an issue does not — the issue is priced by the store's own
 * moving average on the day it leaves, which is the ledger's job and not the supervisor's.</p>
 */
export function MaterialCard({
  siteId,
  date,
  locked,
  onSaved,
  mode,
}: DaySectionProps & { mode: 'RECEIVED' | 'USED' }) {
  const { hasPermission } = useAuth();
  const stores = useStores(siteId);
  const materials = useMaterials();
  const units = useUnits();
  const receive = useReceiveMaterial();
  const issue = useIssueMaterial();
  const nameMaterial = useAddFieldMaterial();
  const [storeId, setStoreId] = useState('');
  const [rows, setRows] = useState<MaterialRow[]>([]);
  const [purpose, setPurpose] = useState('');
  /* What is already in the ledger and wrong. Two different acts, so two different dialogs:
     a quantity is put right by asking the office to post an adjustment — the ledger is
     append-only and nobody rewrites a movement — and a name is put right on the material,
     once, rather than on every row that ever carried it. */
  const [reporting, setReporting] = useState(false);
  const [renaming, setRenaming] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  // One store is the common case; nobody should pick from a list of one.
  useEffect(() => {
    if (!storeId && stores.data?.length) {
      setStoreId(stores.data[0]!.id);
    }
  }, [stores.data, storeId]);

  const receiving = mode === 'RECEIVED';
  const pending = receive.isPending || issue.isPending || nameMaterial.isPending;
  /*
    Naming one is offered on the delivery only. What leaves the store for the work face has
    to be in the store first, and a material nobody has ever received has no stock to issue —
    so the answer there would create a row and then be refused by the ledger.
  */
  const canName = receiving && hasPermission('masterdata:provisional');
  const canRename = hasPermission('masterdata:provisional');
  const canCorrect = hasPermission('inventory:correct');
  const usable = rows.filter(
    (row) =>
      Number(row.quantity) > 0 &&
      (row.materialId === MATERIAL_NOT_LISTED
        ? (row.newName ?? '').trim().length > 0 && Boolean(row.unitId)
        : Boolean(row.materialId)),
  );

  async function submit() {
    setError(null);
    setDone(null);
    const materialById = new Map((materials.data ?? []).map((m) => [m.id, m]));
    let lines: { materialId: string; unitId: string; quantity: number; rate?: number }[];
    try {
      // Anything he had to name becomes a material first: a receipt line carries an id, and
      // there is no way to send a name in its place.
      lines = await Promise.all(
        usable.map(async (row) => {
          const named =
            row.materialId === MATERIAL_NOT_LISTED
              ? await nameMaterial.mutateAsync({
                  name: (row.newName ?? '').trim(),
                  baseUnitId: row.unitId!,
                })
              : undefined;
          return {
            materialId: named?.id ?? row.materialId,
            unitId: named?.baseUnitId ?? materialById.get(row.materialId)?.baseUnitId ?? '',
            quantity: Number(row.quantity),
            ...(receiving ? { rate: Number(row.rate) || 0 } : {}),
          };
        }),
      );
    } catch (caught) {
      setError(apiErrorDetail(caught));
      return;
    }
    try {
      if (receiving) {
        await receive.mutateAsync({
          id: crypto.randomUUID(),
          storeId,
          receiptDate: date,
          lines: lines as { materialId: string; unitId: string; quantity: number; rate: number }[],
        });
      } else {
        await issue.mutateAsync({
          id: crypto.randomUUID(),
          storeId,
          issueDate: date,
          purpose: purpose || undefined,
          lines,
        });
      }
      setRows([]);
      setPurpose('');
      setDone(receiving ? 'Delivery booked into the store.' : 'Issue recorded.');
      onSaved();
    } catch (caught) {
      setError(apiErrorDetail(caught));
    }
  }

  if (locked) {
    return null;
  }

  return (
    <Card
      title={receiving ? 'Material that came in' : 'Material used today'}
      hint={
        receiving
          ? 'Goes into the store as a delivery. It becomes a cost later, when it is issued to the work.'
          : 'What left the store for the work face. This is the part that costs money today.'
      }
    >
      {error && <Alert severity="error">{error}</Alert>}
      {done && <Alert severity="success">{done}</Alert>}
      {stores.data?.length === 0 && (
        <Alert severity="warning">This site has no store, so material cannot be recorded.</Alert>
      )}
      <ReferenceNotice query={materials} what="materials" />

      {(stores.data?.length ?? 0) > 1 && (
        <TextField
          select
          size="small"
          label="Store"
          value={storeId}
          onChange={(event) => setStoreId(event.target.value)}
          sx={{ maxWidth: 260 }}
        >
          {(stores.data ?? []).map((store) => (
            <MenuItem key={store.id} value={store.id}>
              {store.code} — {store.name}
            </MenuItem>
          ))}
        </TextField>
      )}

      <Stack spacing={1.5}>
        {rows.map((row) => (
          <Stack key={row.key} direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
            <Stack spacing={1} sx={{ minWidth: 220 }}>
              <TextField
                select
                size="small"
                label="Material"
                value={row.materialId}
                onChange={(event) =>
                  setRows((current) =>
                    current.map((candidate) =>
                      candidate.key === row.key
                        ? {
                            ...candidate,
                            materialId: event.target.value,
                            // Switching back to a catalogue material drops what he was typing,
                            // so a half-typed name cannot ride along with a picked one.
                            ...(event.target.value === MATERIAL_NOT_LISTED
                              ? {}
                              : { newName: undefined, unitId: undefined }),
                          }
                        : candidate,
                    ),
                  )
                }
              >
                {(materials.data ?? []).map((material) => (
                  <MenuItem key={material.id} value={material.id}>
                    {material.name}
                  </MenuItem>
                ))}
                {canName && <MenuItem value={MATERIAL_NOT_LISTED}>Not in the list…</MenuItem>}
              </TextField>
              {/*
                Where the wrong name is actually noticed: he has just picked "Celment White"
                off the picker and is looking at it. Correcting it here fixes the catalogue
                row rather than adding a second one, which is the whole reason the duplicate
                check exists.
              */}
              {canRename && row.materialId && row.materialId !== MATERIAL_NOT_LISTED && (
                <Button
                  size="small"
                  sx={{ alignSelf: 'flex-start' }}
                  onClick={() => setRenaming(row.materialId)}
                >
                  Wrong name?
                </Button>
              )}
              {row.materialId === MATERIAL_NOT_LISTED && (
                <>
                  <TextField
                    size="small"
                    label="What is it called"
                    value={row.newName ?? ''}
                    onChange={(event) =>
                      setRows((current) =>
                        current.map((candidate) =>
                          candidate.key === row.key
                            ? { ...candidate, newName: event.target.value }
                            : candidate,
                        ),
                      )
                    }
                    helperText="As it reads on the challan. The office prices it later."
                  />
                  {/* No catalogue row means no base unit, so this one is his to say. */}
                  <TextField
                    select
                    size="small"
                    label="Counted in"
                    value={row.unitId ?? ''}
                    onChange={(event) =>
                      setRows((current) =>
                        current.map((candidate) =>
                          candidate.key === row.key
                            ? { ...candidate, unitId: event.target.value }
                            : candidate,
                        ),
                      )
                    }
                  >
                    {(units.data ?? []).map((unit) => (
                      <MenuItem key={unit.id} value={unit.id}>
                        {unit.code} — {unit.name}
                      </MenuItem>
                    ))}
                  </TextField>
                </>
              )}
            </Stack>
            <TextField
              size="small"
              type="number"
              label="Quantity"
              value={row.quantity}
              onChange={(event) =>
                setRows((current) =>
                  current.map((candidate) =>
                    candidate.key === row.key
                      ? { ...candidate, quantity: event.target.value }
                      : candidate,
                  ),
                )
              }
              sx={{ maxWidth: 140 }}
            />
            {receiving && (
              <TextField
                size="small"
                type="number"
                label="Rate"
                value={row.rate}
                onChange={(event) =>
                  setRows((current) =>
                    current.map((candidate) =>
                      candidate.key === row.key
                        ? { ...candidate, rate: event.target.value }
                        : candidate,
                    ),
                  )
                }
                sx={{ maxWidth: 140 }}
              />
            )}
            <IconButton
              aria-label="Remove line"
              onClick={() => setRows((current) => current.filter((c) => c.key !== row.key))}
            >
              <DeleteOutlineIcon />
            </IconButton>
          </Stack>
        ))}
      </Stack>

      {!receiving && rows.length > 0 && (
        <TextField
          size="small"
          label="What it was used for"
          value={purpose}
          onChange={(event) => setPurpose(event.target.value)}
        />
      )}

      <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap>
        <Button startIcon={<AddIcon />} onClick={() => setRows((c) => [...c, emptyMaterial()])}>
          {rows.length === 0 ? 'Add material' : 'Add another'}
        </Button>
        {/*
          Only on the delivery card, for the same reason naming one is: the store's figure is
          what a receipt made wrong, and a man reporting Tuesday's short delivery is standing
          on the card he booked it from. Offering it twice would ask him which of two
          identical questions he meant.
        */}
        {receiving && canCorrect && (
          <Button onClick={() => setReporting(true)}>Something already entered is wrong</Button>
        )}
        {rows.length > 0 && (
          <Button
            variant="outlined"
            onClick={submit}
            disabled={pending || usable.length === 0 || !storeId}
          >
            {receiving ? 'Book the delivery' : 'Record the issue'}
          </Button>
        )}
      </Stack>

      <RaiseCorrectionDialog
        open={reporting}
        storeId={storeId}
        siteId={siteId}
        date={date}
        onClose={() => setReporting(false)}
      />
      <CorrectMaterialNameDialog
        open={Boolean(renaming)}
        materialId={renaming ?? ''}
        onClose={() => setRenaming(null)}
      />
    </Card>
  );
}

// ------------------------------------------------------------------ spending

/**
 * A bill paid at site. It goes into the approvals queue like any other expense — the
 * supervisor records what he spent, and somebody else says whether the company accepts it.
 */
export function ExpenseCard({ siteId, date, locked, onSaved }: DaySectionProps) {
  const categories = useExpenseCategories();
  const add = useAddExpense();
  const [categoryId, setCategoryId] = useState('');
  const [description, setDescription] = useState('');
  const [amount, setAmount] = useState('');
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  if (locked) {
    return null;
  }

  async function submit() {
    setError(null);
    setDone(null);
    try {
      await add.mutateAsync({
        id: crypto.randomUUID(),
        siteId,
        expenseDate: date,
        categoryId,
        description,
        amountBeforeTax: Number(amount),
        gstPercent: 0,
      });
      setCategoryId('');
      setDescription('');
      setAmount('');
      setOpen(false);
      setDone('Booked. It goes to the office for approval.');
      onSaved();
    } catch (caught) {
      setError(apiErrorDetail(caught));
    }
  }

  return (
    <Card
      title="Money spent today"
      hint="Small purchases and payments made at site. Anything with a bill is better entered on the expense screen, where the photograph goes with it."
    >
      {error && <Alert severity="error">{error}</Alert>}
      {done && <Alert severity="success">{done}</Alert>}
      <ReferenceNotice query={categories} what="expense heads" />

      {!open && (
        <Button startIcon={<AddIcon />} onClick={() => setOpen(true)} sx={{ alignSelf: 'flex-start' }}>
          Add spending
        </Button>
      )}

      {open && (
        <Stack spacing={1.5}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
            <TextField
              select
              size="small"
              label="What for"
              value={categoryId}
              onChange={(event) => setCategoryId(event.target.value)}
              sx={{ minWidth: 200 }}
            >
              {(categories.data ?? []).map((category) => (
                <MenuItem key={category.id} value={category.id}>
                  {category.name}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              size="small"
              type="number"
              label="Amount"
              value={amount}
              onChange={(event) => setAmount(event.target.value)}
              sx={{ maxWidth: 160 }}
            />
          </Stack>
          <TextField
            size="small"
            label="Description"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
          />
          <Stack direction="row" spacing={1.5}>
            <Button
              variant="outlined"
              onClick={submit}
              disabled={add.isPending || !categoryId || !description || !(Number(amount) > 0)}
            >
              Book it
            </Button>
            <Button onClick={() => setOpen(false)}>Cancel</Button>
          </Stack>
        </Stack>
      )}
    </Card>
  );
}

// ------------------------------------------------------------------ photographs

/**
 * Photographs of the day's work.
 *
 * <p>Held on the device until the report itself is saved, because a photograph has to hang
 * off a report and there is no report until then. The supervisor picks them while he is
 * looking at the work; they go up with the save.</p>
 */
export function PhotoCard({
  files,
  onChange,
  uploaded,
  siteCode,
  reportDate,
}: {
  files: File[];
  onChange: (files: File[]) => void;
  uploaded: number;
  /** The site the day belongs to, which is half of what a photograph has to be named after. */
  siteCode: string | undefined;
  reportDate: string;
}) {
  const [preview, setPreview] = useState<{ url: string; name: string } | null>(null);

  /**
   * Object URLs for the thumbnails, made once per set of files and revoked when it changes.
   *
   * <p>Kept in state rather than derived on every render: a URL minted inside the render
   * body is a new URL each time, which restarts the decode of every thumbnail on each
   * keystroke elsewhere on the page and leaks each of the ones it replaced.</p>
   */
  const [thumbnails, setThumbnails] = useState<string[]>([]);
  useEffect(() => {
    const urls = files.map((file) => URL.createObjectURL(file));
    setThumbnails(urls);
    return () => urls.forEach((url) => URL.revokeObjectURL(url));
  }, [files]);

  return (
    <Card
      title="Photographs"
      hint={
        uploaded + files.length === 0
          ? 'At least one, before the day can be handed over. They go up when the report is saved.'
          : 'What the work looked like today. They go up when the report is saved.'
      }
      right={uploaded > 0 ? <Chip size="small" label={`${uploaded} on the report`} /> : undefined}
    >
      <Button component="label" startIcon={<AddIcon />} sx={{ alignSelf: 'flex-start' }}>
        Add photographs
        <input
          hidden
          type="file"
          accept="image/*"
          multiple
          onChange={(event) => {
            const picked = Array.from(event.target.files ?? []);
            if (picked.length > 0) {
              onChange([
                ...files,
                ...picked.map((file, at) =>
                  renamed(file, siteCode, reportDate, uploaded + files.length + at + 1),
                ),
              ]);
            }
            event.target.value = '';
          }}
        />
      </Button>

      {/*
        Thumbnails rather than a row of file names. IMG_20260812_104533.jpg is what the camera
        called it and says nothing about which wall it is; the picture says it at a glance, and
        the wrong one picked off a phone gallery is caught here rather than on a report the
        office is reading a week later.
      */}
      {files.length > 0 && (
        <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap>
          {files.map((file, index) => (
            <Stack key={`${file.name}-${index}`} spacing={0.5} sx={{ width: 108 }}>
              <Box sx={{ position: 'relative' }}>
                <Box
                  component="img"
                  src={thumbnails[index]}
                  alt={file.name}
                  onClick={() =>
                    thumbnails[index] &&
                    setPreview({ url: thumbnails[index]!, name: file.name })
                  }
                  sx={{
                    width: 108,
                    height: 108,
                    objectFit: 'cover',
                    borderRadius: 1,
                    border: 1,
                    borderColor: 'divider',
                    cursor: 'pointer',
                    display: 'block',
                  }}
                />
                <IconButton
                  aria-label={`Remove ${file.name}`}
                  onClick={() => onChange(files.filter((_, at) => at !== index))}
                  size="small"
                  sx={{
                    position: 'absolute',
                    top: 2,
                    right: 2,
                    bgcolor: 'background.paper',
                    border: 1,
                    borderColor: 'divider',
                    '&:hover': { bgcolor: 'background.paper' },
                  }}
                >
                  <DeleteOutlineIcon fontSize="small" />
                </IconButton>
              </Box>
              <Typography variant="caption" color="text.secondary" noWrap title={file.name}>
                {file.name}
              </Typography>
            </Stack>
          ))}
        </Stack>
      )}

      <Dialog open={Boolean(preview)} onClose={() => setPreview(null)} fullWidth maxWidth="md">
        <DialogTitle sx={{ fontSize: '1rem' }}>{preview?.name}</DialogTitle>
        <DialogContent>
          {preview && (
            <Box
              component="img"
              src={preview.url}
              alt={preview.name}
              sx={{ width: '100%', height: 'auto', display: 'block' }}
            />
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPreview(null)}>Close</Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
}

/**
 * The photograph, renamed after the site and the day it belongs to.
 *
 * <p>A phone calls it {@code IMG_20260812_104533.jpg} — or {@code image.jpg}, four times over
 * — and that name is what the office sees against the report and what lands in the bucket.
 * {@code KSN-A-2026-08-12-3.jpg} says which site, which day and which of the day's pictures
 * it is, without anybody having to open it.</p>
 *
 * <p>Renamed at the moment it is picked rather than at upload, so the name under the
 * thumbnail is the name that goes up. The extension is kept from the original; compression
 * changes it to jpg later if it re-encodes.</p>
 */
function renamed(file: File, siteCode: string | undefined, reportDate: string, index: number): File {
  const extension = file.name.includes('.') ? file.name.slice(file.name.lastIndexOf('.') + 1) : 'jpg';
  const site = (siteCode ?? 'SITE').replace(/[^A-Za-z0-9-]+/g, '');
  const name = `${site}-${reportDate}-${index}.${extension.toLowerCase()}`;
  // A new File rather than a mutation: File.name is read-only, and everything downstream —
  // the compressor, the upload queue, the attachment row — reads it from here.
  return new File([file], name, { type: file.type, lastModified: file.lastModified });
}

// ------------------------------------------------------------------ shared frame

function Card({
  title,
  hint,
  right,
  children,
}: {
  title: string;
  hint: string;
  right?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
      <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={2}>
        <Typography fontWeight={600}>{title}</Typography>
        {right}
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 0.25, mb: 1.5 }}>
        {hint}
      </Typography>
      <Stack spacing={1.5}>{children}</Stack>
    </Paper>
  );
}
