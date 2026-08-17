import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useMemo, useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { useSelectedSite } from '../../shared/siteSelection';
import { StatusChip, type RecordStatus } from '../../shared/StatusChip';
import { tokens } from '../../app/theme';
import { useAuth } from '../auth/AuthContext';
import { AllocationChip, allocationLabel } from './AllocationChip';
import {
  useAllocateExpense,
  useExpenseCategories,
  usePaymentRegister,
  useRegisterSummary,
  useSites,
  useVendors,
  type RegisterFilters,
} from './api';
import type { CostAllocation, Expense, ExpenseWorkflow, PaymentStatus } from './types';

function startOfMonth(): string {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), 1).toISOString().slice(0, 10);
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Every payment record the organisation holds, in one place, with whose cost each one is.
 *
 * <p>The register exists because the allocation decision is only worth taking if somebody can
 * afterwards read what was decided. Approvals are a queue — a row leaves it the moment it is
 * signed — and a queue cannot answer "what did this month cost the company", which is the
 * question the decision was for.</p>
 *
 * <p>So: every row, filtered the way an accountant narrows a ledger, with the three figures
 * the filter adds up to at the top. The totals come from the server over every matching row,
 * not from the page on screen — a total over the twenty-five rows somebody happens to be
 * looking at is not a total, and labelling it one is worse than showing none.</p>
 *
 * <p><b>What can be changed here, and what cannot.</b> Whose cost it is, until the site
 * closes — a classification, re-decided by the person reading the month. Not the amount: an
 * approved figure is what somebody signed, and moving it is either the author re-opening the
 * expense on his own screen or a void, both of which leave a record. That line is the whole
 * of why this screen is safe to give an administrator.</p>
 */
export function PaymentsRegisterPage() {
  const { hasPermission } = useAuth();
  const sites = useSites();
  const [siteId, setSiteId] = useSelectedSite(sites.data, { allowAll: true });
  const [status, setStatus] = useState<ExpenseWorkflow | ''>('');
  const [allocation, setAllocation] = useState<CostAllocation | ''>('');
  const [paymentStatus, setPaymentStatus] = useState<PaymentStatus | ''>('');
  const [categoryId, setCategoryId] = useState('');
  const [vendorId, setVendorId] = useState('');
  const [from, setFrom] = useState(startOfMonth());
  const [to, setTo] = useState(today());
  const [editing, setEditing] = useState<Expense | null>(null);
  const [kind, setKind] = useState<CostAllocation>('SITE');
  const [share, setShare] = useState('');
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);

  const categories = useExpenseCategories();
  const vendors = useVendors();
  const reallocate = useAllocateExpense();

  const filters: RegisterFilters = useMemo(
    () => ({
      siteId: siteId || undefined,
      status: status || undefined,
      allocation: allocation || undefined,
      paymentStatus: paymentStatus || undefined,
      categoryId: categoryId || undefined,
      vendorId: vendorId || undefined,
      from,
      to,
    }),
    [siteId, status, allocation, paymentStatus, categoryId, vendorId, from, to],
  );

  const register = usePaymentRegister(filters);
  const summary = useRegisterSummary(filters);
  const canAllocate = hasPermission('expense:allocate');

  const openForAllocation = (expense: Expense) => {
    setEditing(expense);
    setError(null);
    setKind(expense.costAllocation);
    setShare(expense.costAllocation === 'SPLIT' ? String(expense.siteCost) : '');
    setNote(expense.allocationNote ?? '');
  };

  const save = () => {
    if (!editing) return;
    setError(null);
    reallocate.mutate(
      {
        id: editing.id,
        allocation: kind,
        siteShare: Number(share),
        note: note || undefined,
      },
      {
        onSuccess: () => setEditing(null),
        onError: (failure) => setError(apiErrorDetail(failure)),
      },
    );
  };

  const splitIncomplete =
    kind === 'SPLIT' &&
    !(Number(share) > 0 && editing !== null && Number(share) < editing.totalAmount);

  const rows = register.data?.content ?? [];

  return (
    <Stack spacing={2}>
      <Typography variant="h1">Payments register</Typography>

      {/*
        A grid rather than a wrapping row: the theme makes every TextField full width — right
        on a phone, where one filter to a line is the only readable answer — and eight of them
        in a flex row is eight full-width lines before the first record appears. Four columns
        on a desk, two on a tablet, one on a phone.
      */}
      <Box
        sx={{
          display: 'grid',
          gap: 2,
          gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', lg: 'repeat(4, 1fr)' },
        }}
      >
        <TextField
          select
          label="Site"
          value={siteId}
          onChange={(e) => setSiteId(e.target.value)}
        >
          <MenuItem value="">Every site</MenuItem>
          {(sites.data ?? []).map((site) => (
            <MenuItem key={site.id} value={site.id}>
              {site.code} — {site.name}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          label="From"
          type="date"
          value={from}
          onChange={(e) => setFrom(e.target.value)}
          InputLabelProps={{ shrink: true }}
        />
        <TextField
          label="To"
          type="date"
          value={to}
          onChange={(e) => setTo(e.target.value)}
          InputLabelProps={{ shrink: true }}
        />
        <TextField
          select
          label="Whose cost"
          value={allocation}
          onChange={(e) => setAllocation(e.target.value as CostAllocation | '')}
        >
          <MenuItem value="">Any</MenuItem>
          <MenuItem value="SITE">The site&apos;s</MenuItem>
          <MenuItem value="COMPANY">The company&apos;s</MenuItem>
          <MenuItem value="SPLIT">Part each</MenuItem>
        </TextField>
        <TextField
          select
          label="Approval"
          value={status}
          onChange={(e) => setStatus(e.target.value as ExpenseWorkflow | '')}
        >
          <MenuItem value="">Any</MenuItem>
          <MenuItem value="SUBMITTED">Waiting</MenuItem>
          <MenuItem value="L1_APPROVED">Past the first level</MenuItem>
          <MenuItem value="APPROVED">Approved</MenuItem>
          <MenuItem value="RETURNED">Sent back</MenuItem>
          <MenuItem value="REJECTED">Rejected</MenuItem>
          <MenuItem value="VOIDED">Void</MenuItem>
        </TextField>
        <TextField
          select
          label="Paid"
          value={paymentStatus}
          onChange={(e) => setPaymentStatus(e.target.value as PaymentStatus | '')}
        >
          <MenuItem value="">Any</MenuItem>
          <MenuItem value="UNPAID">Nothing paid</MenuItem>
          <MenuItem value="PARTIAL">Part paid</MenuItem>
          <MenuItem value="PAID">Paid</MenuItem>
        </TextField>
        <TextField
          select
          label="Head"
          value={categoryId}
          onChange={(e) => setCategoryId(e.target.value)}
        >
          <MenuItem value="">Any</MenuItem>
          {(categories.data ?? []).map((category) => (
            <MenuItem key={category.id} value={category.id}>
              {category.name}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          select
          label="Supplier"
          value={vendorId}
          onChange={(e) => setVendorId(e.target.value)}
        >
          <MenuItem value="">Any</MenuItem>
          {(vendors.data ?? []).map((vendor) => (
            <MenuItem key={vendor.id} value={vendor.id}>
              {vendor.name}
            </MenuItem>
          ))}
        </TextField>
      </Box>

      {/*
        The three figures the filter adds up to, and they add up: booked is the site's part
        plus the company's. Kept beside each other rather than one at a time, because the
        question the office comes here with is the ratio.
      */}
      {summary.data && (
        <Paper elevation={0} sx={{ p: 1.5, border: `1.6px solid ${tokens.ink}` }}>
          <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
            <Figure label="Booked" value={formatAmount(summary.data.totalBooked)} />
            <Figure label="The site's" value={formatAmount(summary.data.siteCost)} />
            <Figure label="The company's" value={formatAmount(summary.data.companyCost)} />
            <Figure label="Paid" value={formatAmount(summary.data.paid)} />
            <Figure label="Still owed" value={formatAmount(summary.data.payable)} />
            <Figure label="Records" value={String(summary.data.expenseCount)} />
          </Stack>
          {/*
            The rows nobody actually answered the question about: still carrying whatever their
            head proposed. Worth saying out loud, because they are indistinguishable from a
            decision on the row itself.
          */}
          {summary.data.unallocatedCount > 0 && (
            <Typography variant="body2" color="text.secondary" sx={{ pt: 1 }}>
              {summary.data.unallocatedCount} of these still carry their head&apos;s proposal —
              nobody has decided whose cost they are.
            </Typography>
          )}
        </Paper>
      )}

      {register.isLoading && <CircularProgress />}
      {register.isError && <Alert severity="error">{apiErrorDetail(register.error)}</Alert>}
      {!register.isLoading && rows.length === 0 && (
        <Typography color="text.secondary">Nothing matches this filter.</Typography>
      )}

      <Stack spacing={1}>
        {rows.map((expense) => (
          <Paper key={expense.id} elevation={0} sx={{ p: 1.5, border: 1, borderColor: 'divider' }}>
            <Stack
              direction={{ xs: 'column', md: 'row' }}
              spacing={1.5}
              justifyContent="space-between"
              alignItems={{ md: 'center' }}
            >
              <div>
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                  <Typography fontWeight={600}>{expense.expenseNumber}</Typography>
                  <StatusChip status={chipStatus(expense.workflowStatus)} />
                  <AllocationChip expense={expense} />
                  {expense.revision > 0 && (
                    <Chip
                      size="small"
                      variant="outlined"
                      label={`Re-opened ${expense.revision}×`}
                      title={expense.revisionReason}
                    />
                  )}
                </Stack>
                <Typography variant="body2" color="text.secondary">
                  {expense.expenseDate} · {expense.categoryName}
                  {expense.vendorName && <> · {expense.vendorName}</>} ·{' '}
                  {formatAmount(expense.totalAmount)}
                  {expense.costAllocation === 'SPLIT' && (
                    <>
                      {' '}
                      (site {formatAmount(expense.siteCost)} · company{' '}
                      {formatAmount(expense.companyCost)})
                    </>
                  )}
                </Typography>
                <Typography variant="body2">{expense.description}</Typography>
                <Typography variant="body2" color="text.secondary">
                  paid {formatAmount(expense.paidAmount)} · owed{' '}
                  {formatAmount(expense.payableAmount)}
                </Typography>
              </div>
              {canAllocate && expense.workflowStatus !== 'VOIDED' && (
                <Button
                  variant="outlined"
                  disabled={reallocate.isPending || editing?.id === expense.id}
                  onClick={() => openForAllocation(expense)}
                  sx={{ minHeight: 48, flexShrink: 0 }}
                >
                  Change whose cost
                </Button>
              )}
            </Stack>

            {editing?.id === expense.id && (
              <>
                <Divider sx={{ my: 1.5 }} />
                <Stack spacing={1.5}>
                  <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
                    <TextField
                      select
                      label="Whose cost is it"
                      size="small"
                      value={kind}
                      onChange={(e) => setKind(e.target.value as CostAllocation)}
                      sx={{ minWidth: 200 }}
                    >
                      <MenuItem value="SITE">The site&apos;s</MenuItem>
                      <MenuItem value="COMPANY">The company&apos;s</MenuItem>
                      <MenuItem value="SPLIT">Part each</MenuItem>
                    </TextField>
                    {kind === 'SPLIT' && (
                      <TextField
                        label="The site's part"
                        type="number"
                        inputMode="decimal"
                        size="small"
                        value={share}
                        onChange={(e) => setShare(e.target.value)}
                        helperText={`The company carries the rest of ${formatAmount(expense.totalAmount)}`}
                        sx={{ minWidth: 200 }}
                      />
                    )}
                    <TextField
                      label="Why"
                      size="small"
                      value={note}
                      onChange={(e) => setNote(e.target.value)}
                      sx={{ flexGrow: 1, minWidth: 200 }}
                    />
                  </Stack>
                  {error && <Alert severity="error">{error}</Alert>}
                  <Stack direction="row" spacing={1}>
                    <Button
                      variant="contained"
                      color="secondary"
                      disabled={reallocate.isPending || splitIncomplete}
                      onClick={save}
                      sx={{ minHeight: 48 }}
                    >
                      {reallocate.isPending ? 'Saving…' : `Book it as ${label(kind)}`}
                    </Button>
                    <Button
                      onClick={() => setEditing(null)}
                      disabled={reallocate.isPending}
                      sx={{ minHeight: 48 }}
                    >
                      Cancel
                    </Button>
                  </Stack>
                </Stack>
              </>
            )}
          </Paper>
        ))}
      </Stack>
    </Stack>
  );
}

function Figure({ label: name, value }: { label: string; value: string }) {
  return (
    <div>
      <Typography variant="overline" sx={{ color: tokens.annotation, display: 'block' }}>
        {name}
      </Typography>
      <Typography sx={{ fontFamily: '"IBM Plex Mono", monospace', fontWeight: 700 }}>
        {value}
      </Typography>
    </div>
  );
}

function label(allocation: CostAllocation): string {
  return allocationLabel(allocation).toLowerCase();
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
