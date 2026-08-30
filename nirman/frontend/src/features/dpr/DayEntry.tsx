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
import {
  useAddExpense,
  useExpenseCategories,
  useLabourCounts,
  useLabourSuppliers,
  useSaveLabourCounts,
  useSkillCategories,
} from './api';
import type { PhotoResponse } from './types';
import { DprPhotos } from './DprPhotos';

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
//
// There is no material card here any more, and that is the point of this note.
//
// Receiving a delivery and issuing to the work face were offered on this step as two short
// lists, on the argument that the man writing the day up should not have to go to four other
// screens. It was the wrong argument for these two. A delivery is a document with a challan
// behind it, a supplier, an invoice number, a rate the office puts on afterwards, and now two
// photographs the man has to take while the lorry is still at the gate — and none of that fits
// in a card that was built for a quick line. What the shortcut actually produced was deliveries
// typed from memory in the evening with no paper behind them.
//
// So the two screens that were always there own it: /inventory/receive and /inventory/issue.
// The wizard links to them instead. Nothing is lost from the report either — the material half
// of the day is read from the store's ledger when the report is downloaded, so a lorry booked
// in at ten at night against a report verified at six still prints on it.

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
  /**
   * The ones already on the report, shown rather than counted.
   *
   * <p>A count is the one thing a photograph cannot say. "3 on the report" is equally true of
   * three pictures of the same wall and of three thumbs over a lens, and a supervisor
   * reopening a report he sent yesterday cannot tell from it whether to take another. So the
   * pictures come back.</p>
   */
  uploaded: PhotoResponse[];
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
        uploaded.length + files.length === 0
          ? 'At least one, before the day can be handed over. They go up when the report is saved.'
          : 'What the work looked like today. They go up when the report is saved.'
      }
      right={
        uploaded.length > 0
          ? <Chip size="small" label={`${uploaded.length} on the report`} />
          : undefined
      }
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
                  renamed(file, siteCode, reportDate, uploaded.length + files.length + at + 1),
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

      {/*
        The ones already up, under the ones still on the phone and separated by a heading. The
        card used to say "3 on the report" and show nothing, so a supervisor reopening
        yesterday's report could not tell whether he had photographed the right wall without
        going to look — which is the whole thing the picture was demanded for.
      */}
      {uploaded.length > 0 && (
        <>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            Already on the report
          </Typography>
          <DprPhotos photos={uploaded} />
        </>
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
