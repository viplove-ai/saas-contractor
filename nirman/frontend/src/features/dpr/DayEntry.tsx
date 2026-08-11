import AddIcon from '@mui/icons-material/Add';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import {
  Alert,
  Button,
  Chip,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { ReferenceNotice } from '../../shared/ReferenceNotice';
import {
  useAddExpense,
  useExpenseCategories,
  useIssueMaterial,
  useLabourContractors,
  useLabourCounts,
  useMaterials,
  useReceiveMaterial,
  useSaveLabourCounts,
  useSkillCategories,
  useStores,
} from './api';

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
  labourContractorId: string;
  headCount: string;
}

function emptyCount(): CountRow {
  return { key: crypto.randomUUID(), skillCategoryId: '', labourContractorId: '', headCount: '' };
}

/**
 * Head counts per trade, for a site whose work is let to a labour contractor.
 *
 * <p>Only drawn when the site is set up that way — on a site with its own muster roll this
 * box would invite somebody to type the same men twice, once as attendance and once as a
 * count.</p>
 *
 * <p>No hours and no money, and there is nowhere to type either. The contractor bills for
 * the work; a rate typed here would produce a wage figure for men who are not on our
 * payroll.</p>
 */
export function OutsourcedLabourCard({ siteId, date, locked, onSaved }: DaySectionProps) {
  const counts = useLabourCounts(siteId, date);
  const categories = useSkillCategories();
  const contractors = useLabourContractors();
  const save = useSaveLabourCounts();
  const [rows, setRows] = useState<CountRow[]>([]);
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
        labourContractorId: line.labourContractorId ?? '',
        headCount: String(line.headCount),
      })),
    );
  }, [counts.data]);

  if (!counts.data?.enabled) {
    return null;
  }

  const readOnly = locked || counts.data.periodLocked;
  const total = rows.reduce((sum, row) => sum + (Number(row.headCount) || 0), 0);

  async function submit() {
    setError(null);
    setSavedAt(null);
    const lines = rows
      .filter((row) => row.skillCategoryId && row.headCount !== '')
      .map((row) => ({
        skillCategoryId: row.skillCategoryId,
        labourContractorId: row.labourContractorId || undefined,
        headCount: Number(row.headCount),
      }));
    try {
      await save.mutateAsync({ siteId, date, lines });
      setSavedAt(new Date().toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' }));
      onSaved();
    } catch (caught) {
      setError(apiErrorDetail(caught));
    }
  }

  return (
    <Card
      title="Contractor's labour"
      hint="Men on site today under a labour contractor, counted by trade. No muster roll, no wages — the contractor bills for the work."
      right={<Chip size="small" label={`${total} on site`} />}
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
            <TextField
              select
              size="small"
              label="Contractor"
              disabled={readOnly}
              value={row.labourContractorId}
              onChange={(event) =>
                setRows((current) =>
                  current.map((candidate) =>
                    candidate.key === row.key
                      ? { ...candidate, labourContractorId: event.target.value }
                      : candidate,
                  ),
                )
              }
              sx={{ minWidth: 180 }}
            >
              <MenuItem value="">Not named</MenuItem>
              {(contractors.data ?? []).map((contractor) => (
                <MenuItem key={contractor.id} value={contractor.id}>
                  {contractor.name}
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
  const stores = useStores(siteId);
  const materials = useMaterials();
  const receive = useReceiveMaterial();
  const issue = useIssueMaterial();
  const [storeId, setStoreId] = useState('');
  const [rows, setRows] = useState<MaterialRow[]>([]);
  const [purpose, setPurpose] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  // One store is the common case; nobody should pick from a list of one.
  useEffect(() => {
    if (!storeId && stores.data?.length) {
      setStoreId(stores.data[0]!.id);
    }
  }, [stores.data, storeId]);

  const receiving = mode === 'RECEIVED';
  const pending = receive.isPending || issue.isPending;
  const usable = rows.filter((row) => row.materialId && Number(row.quantity) > 0);

  async function submit() {
    setError(null);
    setDone(null);
    const materialById = new Map((materials.data ?? []).map((m) => [m.id, m]));
    const lines = usable.map((row) => ({
      materialId: row.materialId,
      unitId: materialById.get(row.materialId)?.baseUnitId ?? '',
      quantity: Number(row.quantity),
      ...(receiving ? { rate: Number(row.rate) || 0 } : {}),
    }));
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
            <TextField
              select
              size="small"
              label="Material"
              value={row.materialId}
              onChange={(event) =>
                setRows((current) =>
                  current.map((candidate) =>
                    candidate.key === row.key
                      ? { ...candidate, materialId: event.target.value }
                      : candidate,
                  ),
                )
              }
              sx={{ minWidth: 220 }}
            >
              {(materials.data ?? []).map((material) => (
                <MenuItem key={material.id} value={material.id}>
                  {material.name}
                </MenuItem>
              ))}
            </TextField>
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
}: {
  files: File[];
  onChange: (files: File[]) => void;
  uploaded: number;
}) {
  return (
    <Card
      title="Photographs"
      hint="What the work looked like today. They go up when the report is saved."
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
              onChange([...files, ...picked]);
            }
            event.target.value = '';
          }}
        />
      </Button>

      {files.length > 0 && (
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
          {files.map((file, index) => (
            <Chip
              key={`${file.name}-${index}`}
              label={file.name}
              onDelete={() => onChange(files.filter((_, at) => at !== index))}
              variant="outlined"
            />
          ))}
        </Stack>
      )}
    </Card>
  );
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
