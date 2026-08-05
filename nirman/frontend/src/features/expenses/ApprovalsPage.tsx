import {
  Alert,
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
import { useEffect, useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { StatusChip } from '../../shared/StatusChip';
import { useAuth } from '../auth/AuthContext';
import {
  useDecideExpense,
  useExpenses,
  usePendingApprovals,
  useRecordPayment,
  useSites,
} from './api';
import type { ApprovalAction, Expense } from './types';

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * The queue: what is waiting on you, and what is waiting to be paid.
 *
 * <p>Two lists on one screen because they are two ends of the same story and the same
 * people work both. Approving decides that money may go out; recording a payment says it
 * has. The permissions differ — an engineer approves and never pays, an accountant pays and
 * never approves an expense — so each half appears only for whoever holds it.</p>
 *
 * <p>Approving from here routes to exactly the same engine as approving while looking at
 * the expense. There is one code path and two doors.</p>
 */
export function ApprovalsPage() {
  const { hasPermission } = useAuth();
  const [siteId, setSiteId] = useState('');
  const [remarks, setRemarks] = useState<Record<string, string>>({});
  const [payAmount, setPayAmount] = useState<Record<string, string>>({});

  const sites = useSites();
  const pending = usePendingApprovals('EXPENSE');
  const submitted = useExpenses(siteId || undefined, 'SUBMITTED');
  const l1 = useExpenses(siteId || undefined, 'L1_APPROVED');
  const approved = useExpenses(siteId || undefined, 'APPROVED');
  const decide = useDecideExpense();
  const pay = useRecordPayment();

  const canDecide = hasPermission('expense:approve:l1');
  const canPay = hasPermission('payment:record');

  useEffect(() => {
    if (!siteId && sites.data?.length) {
      setSiteId(sites.data[0]!.id);
    }
  }, [sites.data, siteId]);

  /** Everything with a level still waiting, whichever level that is. */
  const waiting = [...(submitted.data?.content ?? []), ...(l1.data?.content ?? [])];
  const unpaid = (approved.data?.content ?? []).filter((expense) => expense.payableAmount > 0);

  const act = (expense: Expense, action: ApprovalAction) => {
    decide.mutate({ id: expense.id, action, remarks: remarks[expense.id] });
  };

  return (
    <Stack spacing={2}>
      <Typography variant="h1">Approvals and payments</Typography>

      <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap">
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
        {pending.data && (
          <Chip label={`${pending.data.length} waiting on you`} color="warning" variant="outlined" />
        )}
      </Stack>

      {(submitted.isLoading || l1.isLoading) && <CircularProgress />}
      {decide.isError && <Alert severity="error">{apiErrorDetail(decide.error)}</Alert>}

      <Typography variant="h2" sx={{ fontSize: '1.25rem' }}>
        Waiting for a decision
      </Typography>
      {waiting.length === 0 && (
        <Typography color="text.secondary">Nothing is waiting at this site.</Typography>
      )}

      <Stack spacing={1}>
        {waiting.map((expense) => (
          <Paper key={expense.id} elevation={0} sx={{ p: 1.5, border: 1, borderColor: 'divider' }}>
            <Stack spacing={1.5}>
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
                <Typography fontWeight={600}>{expense.expenseNumber}</Typography>
                <StatusChip status="SUBMITTED" />
                {/*
                  Which level, and on whom. A bill past the engineer and waiting on the
                  office looks identical to one nobody has touched without this.
                */}
                {expense.pendingWithRole && (
                  <Chip
                    size="small"
                    variant="outlined"
                    label={`Level ${expense.pendingLevel} · ${expense.pendingWithRole.toLowerCase()}`}
                  />
                )}
              </Stack>
              <Typography variant="body2" color="text.secondary">
                {expense.expenseDate} · {expense.categoryName}
                {expense.vendorName && <> · {expense.vendorName}</>} ·{' '}
                {formatAmount(expense.totalAmount)}
              </Typography>
              <Typography variant="body2">{expense.description}</Typography>
              <Typography variant="body2" color="text.secondary">
                {expense.billNumber
                  ? `Bill ${expense.billNumber}`
                  : (expense.noBillReason ?? 'No bill')}
              </Typography>

              {canDecide && (
                <>
                  <TextField
                    label="Remarks"
                    size="small"
                    value={remarks[expense.id] ?? ''}
                    onChange={(e) =>
                      setRemarks((current) => ({ ...current, [expense.id]: e.target.value }))
                    }
                  />
                  <Stack direction="row" spacing={1} flexWrap="wrap">
                    <Button
                      variant="contained"
                      color="secondary"
                      disabled={decide.isPending}
                      onClick={() => act(expense, 'APPROVE')}
                      sx={{ minHeight: 48 }}
                    >
                      Approve
                    </Button>
                    {/*
                      Return is not a softer rejection. It goes back editable, and keeping
                      the two apart is what stops every correction reading as a refusal.
                    */}
                    <Button
                      variant="outlined"
                      disabled={decide.isPending}
                      onClick={() => act(expense, 'RETURN')}
                      sx={{ minHeight: 48 }}
                    >
                      Send back to fix
                    </Button>
                    <Button
                      variant="outlined"
                      color="error"
                      disabled={decide.isPending}
                      onClick={() => act(expense, 'REJECT')}
                      sx={{ minHeight: 48 }}
                    >
                      Reject
                    </Button>
                  </Stack>
                </>
              )}
            </Stack>
          </Paper>
        ))}
      </Stack>

      {canPay && (
        <>
          <Divider sx={{ pt: 2 }} />
          <Typography variant="h2" sx={{ fontSize: '1.25rem' }}>
            Approved and still owed
          </Typography>
          {pay.isError && <Alert severity="error">{apiErrorDetail(pay.error)}</Alert>}
          {unpaid.length === 0 && (
            <Typography color="text.secondary">Nothing is outstanding at this site.</Typography>
          )}

          <Stack spacing={1}>
            {unpaid.map((expense) => (
              <Paper
                key={expense.id}
                elevation={0}
                sx={{ p: 1.5, border: 1, borderColor: 'divider' }}
              >
                <Stack spacing={1.5}>
                  <Stack direction="row" spacing={1} alignItems="center">
                    <Typography fontWeight={600}>{expense.expenseNumber}</Typography>
                    <StatusChip status="APPROVED" />
                  </Stack>
                  {/* The three figures, on the row, never merged into one. */}
                  <Typography variant="body2" color="text.secondary">
                    {expense.vendorName ?? 'Cash purchase'} · approved{' '}
                    {formatAmount(expense.totalAmount)} · paid {formatAmount(expense.paidAmount)} ·{' '}
                    <strong>owed {formatAmount(expense.payableAmount)}</strong>
                  </Typography>
                  <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
                    <TextField
                      label="Pay now"
                      type="number"
                      inputMode="decimal"
                      size="small"
                      value={payAmount[expense.id] ?? ''}
                      onChange={(e) =>
                        setPayAmount((current) => ({ ...current, [expense.id]: e.target.value }))
                      }
                      helperText={`Up to ${formatAmount(expense.payableAmount)}`}
                      sx={{ minWidth: 160 }}
                    />
                    <Button
                      variant="contained"
                      color="secondary"
                      disabled={
                        pay.isPending ||
                        !(Number(payAmount[expense.id]) > 0) ||
                        Number(payAmount[expense.id]) > expense.payableAmount
                      }
                      onClick={() =>
                        pay.mutate(
                          {
                            expenseId: expense.id,
                            paymentDate: today(),
                            amount: Number(payAmount[expense.id]),
                            paymentMode: 'BANK',
                          },
                          {
                            onSuccess: () =>
                              setPayAmount((current) => ({ ...current, [expense.id]: '' })),
                          },
                        )
                      }
                      sx={{ minHeight: 48, alignSelf: 'flex-start' }}
                    >
                      Record payment
                    </Button>
                  </Stack>
                </Stack>
              </Paper>
            ))}
          </Stack>
        </>
      )}
    </Stack>
  );
}
