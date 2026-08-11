import {
  Alert,
  Button,
  Divider,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useMemo, useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { ReferenceNotice } from '../../shared/ReferenceNotice';
import { StatusChip, type RecordStatus } from '../../shared/StatusChip';
import { useAuth } from '../auth/AuthContext';
import { BillPhotoField } from './BillPhotoField';
import {
  duplicateCandidates,
  useCreateExpense,
  useExpenseCategories,
  useExpenses,
  useSites,
  useSubmitExpense,
} from './api';
import type { DuplicateCandidate, ExpenseWorkflow } from './types';

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Booking a site expense.
 *
 * <p>Three things shape it. The amount is entered before tax with the rate beside it, never
 * as a total, because that is how it appears on the bill and deriving one from the others is
 * what stops them disagreeing. The duplicate warning shows what the entry collided with
 * rather than just refusing — only the person entering it can tell a second delivery from a
 * double entry, and they can only do that if they see the candidate. And booking is separate
 * from sending: an expense sits as a draft until somebody submits it, so a wrong figure
 * costs a correction on screen rather than an approval somebody has to chase back.</p>
 */
export function AddExpensePage() {
  const { hasPermission } = useAuth();
  const [siteId, setSiteId] = useState('');
  const [expenseDate, setExpenseDate] = useState(today());
  const [categoryId, setCategoryId] = useState('');
  const [description, setDescription] = useState('');
  const [billNumber, setBillNumber] = useState('');
  const [amountBeforeTax, setAmountBeforeTax] = useState('');
  const [gstPercent, setGstPercent] = useState('0');
  const [noBillReason, setNoBillReason] = useState('');
  const [overrideReason, setOverrideReason] = useState('');
  const [candidates, setCandidates] = useState<DuplicateCandidate[] | null>(null);
  const [photo, setPhoto] = useState<File | null>(null);
  // The id is fixed when the form is opened, not when Save is pressed. The photograph is
  // attached to it before the expense exists anywhere, and a fresh uuid at save time would
  // leave the photograph pointing at a record that was never created.
  const [expenseId, setExpenseId] = useState(() => crypto.randomUUID());

  const sites = useSites();
  const categories = useExpenseCategories();
  const create = useCreateExpense();
  const submit = useSubmitExpense();
  const mine = useExpenses(siteId || undefined, '' as ExpenseWorkflow | '');

  const canSubmit = hasPermission('expense:create');

  // Default to the first site the user can reach; most supervisors have exactly one.
  useEffect(() => {
    if (!siteId && sites.data?.length) {
      setSiteId(sites.data[0]!.id);
    }
  }, [sites.data, siteId]);

  const total = useMemo(() => {
    const base = Number(amountBeforeTax);
    const rate = Number(gstPercent);
    if (!Number.isFinite(base) || !Number.isFinite(rate)) return 0;
    return base + (base * rate) / 100;
  }, [amountBeforeTax, gstPercent]);

  const complete =
    Boolean(siteId) && Boolean(categoryId) && description.trim().length > 0 && total > 0;

  const site = sites.data?.find((candidate) => candidate.id === siteId);
  const booked = create.data;

  const book = (force: boolean) => {
    create.mutate(
      {
        force,
        siteLabel: site ? `${site.code} ${site.name}` : 'site',
        photo: photo ?? undefined,
        input: {
          id: expenseId,
          siteId,
          expenseDate,
          categoryId,
          description: description.trim(),
          billNumber: billNumber || undefined,
          amountBeforeTax: Number(amountBeforeTax),
          gstPercent: Number(gstPercent),
          noBillReason: noBillReason || undefined,
          duplicateOverrideReason: force ? overrideReason : undefined,
        },
      },
      {
        onSuccess: () => {
          setDescription('');
          setBillNumber('');
          setAmountBeforeTax('');
          setNoBillReason('');
          setOverrideReason('');
          setCandidates(null);
          setPhoto(null);
          // A new id for the next bill. Reusing this one would make the second expense a
          // replay of the first and the server would answer with the first one back.
          setExpenseId(crypto.randomUUID());
        },
        onError: (error) => setCandidates(duplicateCandidates(error)),
      },
    );
  };

  return (
    <Stack spacing={2}>
      <Typography variant="h1">Add expense</Typography>

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField
          select
          label="Site"
          value={siteId}
          onChange={(e) => setSiteId(e.target.value)}
          sx={{ minWidth: 200 }}
        >
          {(sites.data ?? []).map((site) => (
            <MenuItem key={site.id} value={site.id}>
              {site.code} — {site.name}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          label="Spent on"
          type="date"
          value={expenseDate}
          onChange={(e) => setExpenseDate(e.target.value)}
          InputLabelProps={{ shrink: true }}
        />
        <TextField
          select
          label="What kind"
          value={categoryId}
          onChange={(e) => setCategoryId(e.target.value)}
          sx={{ flexGrow: 1, minWidth: 220 }}
        >
          {(categories.data ?? []).map((category) => (
            <MenuItem key={category.id} value={category.id}>
              {category.name}
            </MenuItem>
          ))}
        </TextField>
      </Stack>

      <ReferenceNotice query={categories} what="expense heads" />

      <TextField
        label="What was it for"
        value={description}
        onChange={(e) => setDescription(e.target.value)}
      />

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField
          label="Amount before tax"
          type="number"
          inputMode="decimal"
          value={amountBeforeTax}
          onChange={(e) => setAmountBeforeTax(e.target.value)}
          sx={{ minWidth: 180 }}
        />
        <TextField
          label="GST %"
          type="number"
          inputMode="decimal"
          value={gstPercent}
          onChange={(e) => setGstPercent(e.target.value)}
          sx={{ minWidth: 120 }}
        />
        <TextField
          label="Bill number"
          value={billNumber}
          onChange={(e) => setBillNumber(e.target.value)}
          helperText="Leave blank for a cash purchase with no bill"
          sx={{ minWidth: 180 }}
        />
      </Stack>

      {!billNumber && (
        <TextField
          label="Why is there no bill"
          value={noBillReason}
          onChange={(e) => setNoBillReason(e.target.value)}
          helperText="Needed above the org's threshold when there is no bill number or photograph"
        />
      )}

      <Typography color="text.secondary">Total with tax: {formatAmount(total)}</Typography>

      <BillPhotoField file={photo} onPick={setPhoto} />

      {/*
        The duplicate warning, with what it collided against. Refusing without saying which
        row sends somebody hunting through a month of paper for something already in hand.
      */}
      {candidates && candidates.length > 0 && (
        <Alert severity="warning">
          <Typography fontWeight={600}>This looks like an expense already on file.</Typography>
          {candidates.map((candidate) => (
            <Typography key={candidate.id} variant="body2">
              {candidate.expenseNumber} · {candidate.expenseDate} ·{' '}
              {formatAmount(candidate.totalAmount)}
              {candidate.billNumber && <> · bill {candidate.billNumber}</>} —{' '}
              {candidate.matchedOn}
            </Typography>
          ))}
          <TextField
            label="If it really is separate, say why"
            value={overrideReason}
            onChange={(e) => setOverrideReason(e.target.value)}
            fullWidth
            sx={{ mt: 1.5 }}
          />
          <Button
            variant="outlined"
            disabled={overrideReason.trim().length === 0 || create.isPending}
            onClick={() => book(true)}
            sx={{ minHeight: 48, mt: 1 }}
          >
            Book it anyway
          </Button>
        </Alert>
      )}

      {create.isError && !candidates && (
        <Alert severity="error">{apiErrorDetail(create.error)}</Alert>
      )}
      {booked?.outcome === 'SENT' && (
        <Alert severity="success">
          Saved {booked.expense.expenseNumber} as a draft. Send it when you are ready.
        </Alert>
      )}
      {booked?.outcome === 'QUEUED' && (
        <Alert severity="info">
          No connection. This expense{booked.photoQueued ? ' and its photograph are' : ' is'} saved
          on this phone and goes out by itself when there is a signal.
        </Alert>
      )}

      <Stack direction="row" spacing={2}>
        <Button
          variant="contained"
          color="secondary"
          disabled={!complete || create.isPending}
          onClick={() => book(false)}
          sx={{ minHeight: 48 }}
        >
          {create.isPending ? 'Saving…' : 'Save as draft'}
        </Button>
      </Stack>

      <Divider sx={{ pt: 2 }} />

      <Typography variant="h2" sx={{ fontSize: '1.25rem' }}>
        Your recent expenses
      </Typography>
      {submit.isError && <Alert severity="error">{apiErrorDetail(submit.error)}</Alert>}
      {mine.data?.content.length === 0 && (
        <Typography color="text.secondary">Nothing booked at this site yet.</Typography>
      )}

      <Stack spacing={1}>
        {(mine.data?.content ?? []).map((expense) => (
          <Paper key={expense.id} elevation={0} sx={{ p: 1.5, border: 1, borderColor: 'divider' }}>
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1.5}
              justifyContent="space-between"
              alignItems={{ sm: 'center' }}
            >
              <div>
                <Stack direction="row" spacing={1} alignItems="center">
                  <Typography fontWeight={600}>{expense.expenseNumber}</Typography>
                  <StatusChip status={chipStatus(expense.workflowStatus)} />
                </Stack>
                <Typography variant="body2" color="text.secondary">
                  {expense.expenseDate} · {expense.categoryName} ·{' '}
                  {formatAmount(expense.totalAmount)}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {expense.description}
                </Typography>
                {expense.pendingWithRole && (
                  <Typography variant="body2" color="text.secondary">
                    Waiting with the {expense.pendingWithRole.toLowerCase()}
                  </Typography>
                )}
                {expense.rejectionReason && (
                  <Typography variant="body2" color="error.main">
                    {expense.rejectionReason}
                  </Typography>
                )}
              </div>
              {canSubmit && isSendable(expense.workflowStatus) && (
                <Button
                  variant="contained"
                  color="secondary"
                  disabled={submit.isPending}
                  onClick={() => submit.mutate(expense.id)}
                  sx={{ minHeight: 48 }}
                >
                  Send for approval
                </Button>
              )}
            </Stack>
          </Paper>
        ))}
      </Stack>
    </Stack>
  );
}

/** Draft, returned and rejected rows are still the author's to send. */
function isSendable(status: ExpenseWorkflow): boolean {
  return status === 'DRAFT' || status === 'RETURNED' || status === 'REJECTED';
}

function chipStatus(status: ExpenseWorkflow): RecordStatus {
  switch (status) {
    case 'SUBMITTED':
    case 'L1_APPROVED':
      return 'SUBMITTED';
    case 'APPROVED':
      return 'APPROVED';
    case 'REJECTED':
    case 'VOIDED':
      return 'REJECTED';
    case 'RETURNED':
      return 'CONFLICT';
    default:
      return 'DRAFT';
  }
}
