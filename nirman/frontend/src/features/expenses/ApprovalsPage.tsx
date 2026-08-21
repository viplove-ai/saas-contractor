import PersonOutlineIcon from '@mui/icons-material/PersonOutline';
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
import { useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { useSelectedSite } from '../../shared/siteSelection';
import { StatusChip } from '../../shared/StatusChip';
import { useAuth } from '../auth/AuthContext';
import { EvidencePhotoField } from '../../shared/EvidencePhotoField';
import {
  useDecideExpense,
  useExpenses,
  usePayments,
  usePendingApprovals,
  useRecordPayment,
  useSites,
} from './api';
import { AllocationChip } from './AllocationChip';
import { BillPreview } from './BillPreview';
import type { ApprovalAction, CostAllocation, Expense } from './types';

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * One of the three decisions, sized so that all three fit a phone's width in one row.
 *
 * <p>On a phone they share the width equally — {@code flex: 1} rather than a fixed size, so
 * the row holds together at 320px as well as at 430 — and the padding and the label give way
 * before the row does, because a button whose text has wrapped mid-word is how "Return to
 * fix" stops reading as one answer. The 48px tap target survives all of it.</p>
 *
 * <p>From {@code sm} up they go back to their own widths. A desk has room to spare and
 * stretching three buttons across nine hundred pixels does not use it — it just makes Reject
 * as large a target as Approve on the screen where nobody was ever short of room.</p>
 */
const DECISION_BUTTON = {
  minHeight: 48,
  flex: { xs: 1, sm: '0 0 auto' },
  px: { xs: 0.5, sm: 2 },
  whiteSpace: 'nowrap',
  fontSize: { xs: '0.8125rem', sm: '0.875rem' },
} as const;

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
  const [remarks, setRemarks] = useState<Record<string, string>>({});
  const [payAmount, setPayAmount] = useState<Record<string, string>>({});
  /**
   * The picture of the cash going out, per bill: the UPI screenshot, the signed receipt, the
   * bank slip.
   *
   * <p>Held here rather than inside the row so that clearing it on a successful save is the
   * same act as clearing the amount. A file left behind after the payment it belonged to has
   * been recorded is the next payment quietly attaching the wrong receipt.</p>
   */
  const [proof, setProof] = useState<Record<string, File | null>>({});
  const [proofType, setProofType] = useState<Record<string, string>>({});
  /**
   * Whose cost each waiting bill is, where the approver has changed his mind about it.
   *
   * <p>Empty means "what the head proposed", which is what the row already carries — so an
   * approver who agrees with the proposal simply presses Approve, and the two hundred office
   * bills cost him nothing. Only the diesel bill gets touched.</p>
   */
  const [allocation, setAllocation] = useState<Record<string, CostAllocation>>({});
  const [siteShare, setSiteShare] = useState<Record<string, string>>({});

  const sites = useSites();
  const [siteId, setSiteId] = useSelectedSite(sites.data);
  const pending = usePendingApprovals('EXPENSE');
  const submitted = useExpenses(siteId || undefined, 'SUBMITTED');
  const l1 = useExpenses(siteId || undefined, 'L1_APPROVED');
  const approved = useExpenses(siteId || undefined, 'APPROVED');
  const decide = useDecideExpense();
  const pay = useRecordPayment();

  const canDecide = hasPermission('expense:approve:l1');
  const canPay = hasPermission('payment:record');

  /** Everything with a level still waiting, whichever level that is. */
  const waiting = [...(submitted.data?.content ?? []), ...(l1.data?.content ?? [])];
  const unpaid = (approved.data?.content ?? []).filter((expense) => expense.payableAmount > 0);

  /** What the approver has chosen for this bill, or what its head proposed if he has not. */
  const chosen = (expense: Expense): CostAllocation =>
    allocation[expense.id] ?? expense.costAllocation;

  /** A split needs a number, and the server refuses one that is the whole bill or none of it. */
  const splitIncomplete = (expense: Expense) => {
    if (chosen(expense) !== 'SPLIT') return false;
    const share = Number(siteShare[expense.id]);
    return !(share > 0 && share < expense.totalAmount);
  };

  const act = (expense: Expense, action: ApprovalAction) => {
    decide.mutate({
      id: expense.id,
      action,
      remarks: remarks[expense.id],
      allocation: chosen(expense),
      siteShare: Number(siteShare[expense.id]),
    });
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
              {/*
                Who sent it, on the row rather than a click away. The approver is agreeing to
                a figure he did not watch being incurred, and the author is half of what he is
                weighing — the same bill from the storekeeper and from a supervisor two days
                off the site are not the same bill. Named when it is known and admitted when it
                is not, because a blank line reads as nobody having sent it.
              */}
              <Stack direction="row" spacing={0.5} alignItems="center">
                <PersonOutlineIcon fontSize="small" color="disabled" />
                <Typography variant="body2" color="text.secondary">
                  Submitted by {expense.createdByName ?? 'somebody no longer on the rolls'}
                </Typography>
              </Stack>
              <Typography variant="body2" color="text.secondary">
                {billLine(expense)}
              </Typography>
              <BillPreview attachments={expense.attachments} />

              {canDecide && (
                <>
                  {/*
                    Whose cost it is, asked where the money is being agreed to and not on a
                    screen somebody visits afterwards. It arrives already answered by the head
                    — office and staff heads say company — so the question costs nothing on the
                    rows where it is obvious and is still in front of him on the diesel bill.
                  */}
                  <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
                    <TextField
                      select
                      label="Whose cost is it"
                      size="small"
                      value={chosen(expense)}
                      onChange={(e) =>
                        setAllocation((current) => ({
                          ...current,
                          [expense.id]: e.target.value as CostAllocation,
                        }))
                      }
                      sx={{ minWidth: 200 }}
                    >
                      <MenuItem value="SITE">The site&apos;s</MenuItem>
                      <MenuItem value="COMPANY">The company&apos;s</MenuItem>
                      <MenuItem value="SPLIT">Part each</MenuItem>
                    </TextField>
                    {chosen(expense) === 'SPLIT' && (
                      <TextField
                        label="The site's part"
                        type="number"
                        inputMode="decimal"
                        size="small"
                        value={siteShare[expense.id] ?? ''}
                        onChange={(e) =>
                          setSiteShare((current) => ({ ...current, [expense.id]: e.target.value }))
                        }
                        helperText={`The company carries the rest of ${formatAmount(expense.totalAmount)}`}
                        sx={{ minWidth: 200 }}
                      />
                    )}
                  </Stack>
                  <TextField
                    label="Remarks"
                    size="small"
                    value={remarks[expense.id] ?? ''}
                    onChange={(e) =>
                      setRemarks((current) => ({ ...current, [expense.id]: e.target.value }))
                    }
                  />
                  {/*
                    Three decisions, side by side on a phone as well as on a desk. They used
                    to wrap, which put Reject alone on its own line under the other two and
                    made it look like the row's main action; equal widths on one line is the
                    shape the decision actually has — three answers of the same weight.
                  */}
                  <Stack direction="row" spacing={1}>
                    <Button
                      variant="contained"
                      color="secondary"
                      disabled={decide.isPending || splitIncomplete(expense)}
                      onClick={() => act(expense, 'APPROVE')}
                      sx={DECISION_BUTTON}
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
                      sx={DECISION_BUTTON}
                    >
                      Return to fix
                    </Button>
                    <Button
                      variant="outlined"
                      color="error"
                      disabled={decide.isPending}
                      onClick={() => act(expense, 'REJECT')}
                      sx={DECISION_BUTTON}
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
                  <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
                    <Typography fontWeight={600}>{expense.expenseNumber}</Typography>
                    <StatusChip status="APPROVED" />
                    {/* Whose cost it was, in front of the person about to pay it. */}
                    <AllocationChip expense={expense} />
                  </Stack>
                  {/* The three figures, on the row, never merged into one. */}
                  <Typography variant="body2" color="text.secondary">
                    {expense.vendorName ?? 'Cash purchase'} · approved{' '}
                    {formatAmount(expense.totalAmount)} · paid {formatAmount(expense.paidAmount)} ·{' '}
                    <strong>owed {formatAmount(expense.payableAmount)}</strong>
                  </Typography>
                  {/* What has already gone out against this bill, and what proved it. */}
                  <PaidSoFar expenseId={expense.id} paidAmount={expense.paidAmount} />
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
                    {/*
                      Asked only once there is something to describe. A receipt with a
                      supplier's signature on it and a screenshot out of a UPI app are not the
                      same kind of proof — the first binds him and the second only says the
                      money moved — and whoever reads it later is entitled to be told which he
                      is looking at. Asking before a file is picked would be a picker on every
                      row for a question most of them never reach.
                    */}
                    {proof[expense.id] && (
                      <TextField
                        select
                        label="What is it"
                        size="small"
                        value={proofType[expense.id] ?? 'RECEIPT'}
                        onChange={(e) =>
                          setProofType((current) => ({
                            ...current,
                            [expense.id]: e.target.value,
                          }))
                        }
                        sx={{ minWidth: 170 }}
                      >
                        <MenuItem value="RECEIPT">Receipt</MenuItem>
                        <MenuItem value="SCREENSHOT">Payment screenshot</MenuItem>
                        <MenuItem value="BANK_SLIP">Bank slip</MenuItem>
                        <MenuItem value="OTHER">Something else</MenuItem>
                      </TextField>
                    )}
                  </Stack>
                  {/*
                    The proof, collected where the payment is recorded and not on a screen
                    somebody visits afterwards. A reference number was all this record could
                    carry, so a supplier disputing a payment nine months later was answered
                    with twelve digits and the hope that somebody's phone still had the
                    screenshot on it.

                    Optional, and it stays optional: cash handed across a table has nothing to
                    photograph, and refusing the payment for want of a picture is how a real
                    payment ends up in a second book.
                  */}
                  <EvidencePhotoField
                    file={proof[expense.id] ?? null}
                    onPick={(file) =>
                      setProof((current) => ({ ...current, [expense.id]: file }))
                    }
                    label="Attach proof of payment"
                    changeLabel="Change the proof"
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
                          siteId: expense.siteId,
                          paymentDate: today(),
                          amount: Number(payAmount[expense.id]),
                          paymentMode: 'BANK',
                          proof: proof[expense.id] ?? undefined,
                          proofType: proofType[expense.id] ?? 'RECEIPT',
                        },
                        {
                          onSuccess: () => {
                            setPayAmount((current) => ({ ...current, [expense.id]: '' }));
                            // The file goes with the amount. Left behind, it is the next
                            // payment quietly attaching the previous one's receipt.
                            setProof((current) => ({ ...current, [expense.id]: null }));
                            setProofType((current) => ({ ...current, [expense.id]: 'RECEIPT' }));
                          },
                        },
                      )
                    }
                    sx={{ minHeight: 48, alignSelf: 'flex-start' }}
                  >
                    Record payment
                  </Button>
                </Stack>
              </Paper>
            ))}
          </Stack>
        </>
      )}
    </Stack>
  );
}

/**
 * What has already gone out against this bill, and what proved it.
 *
 * <p>Only on a part-paid row, which is why the query is not fired at all until there is
 * something to fetch: a queue of forty untouched bills would otherwise be forty round trips
 * for forty empty lists. A bill nobody has paid says nothing here, because "no payments yet"
 * is exactly what the owed figure above already says.</p>
 *
 * <p>The receipt is shown rather than only recorded. Evidence that can be attached and never
 * looked at is a filing cabinet nobody has the key to — and the moment it is wanted is
 * precisely the moment somebody is disputing the payment, which is months after whoever
 * uploaded it has forgotten which bill it belonged to.</p>
 */
function PaidSoFar({ expenseId, paidAmount }: { expenseId: string; paidAmount: number }) {
  const payments = usePayments(paidAmount > 0 ? expenseId : undefined);
  const rows = payments.data?.content ?? [];
  if (rows.length === 0) {
    return null;
  }
  return (
    <Stack spacing={1}>
      {rows.map((payment) => (
        <Stack key={payment.id} spacing={0.5}>
          <Typography variant="body2" color="text.secondary">
            {payment.paymentDate} · {formatAmount(payment.amount)} · {payment.paymentMode}
            {payment.referenceNumber && <> · {payment.referenceNumber}</>}
          </Typography>
          <BillPreview
            attachments={payment.attachments}
            emptyLabel="Nothing was attached to prove this payment"
          />
        </Stack>
      ))}
    </Stack>
  );
}

/**
 * What the row says about the bill — read together with the photograph beside it, not apart
 * from it.
 *
 * <p>It used to say "No bill" whenever there was no bill <b>number</b>, which on a challan
 * with no serial on it was flatly untrue: the bill was on the screen directly underneath the
 * sentence denying it. {@code ExpenseEvidencePolicy} has always counted a photograph as
 * evidence — V40 moved the rule out of a check constraint precisely so it could see the
 * attachments — and the screen was the last place still pretending otherwise.</p>
 *
 * <p>So the three cases are said as three different things. A number is a number. A
 * photograph with no number is a bill that carries none, which the approver can look at and
 * judge. Neither of those, and the reason the author gave for there being no bill at all is
 * the whole of what he has to go on — and if he gave none, "No bill" is finally true.</p>
 */
function billLine(expense: Expense): string {
  if (expense.billNumber) {
    return `Bill ${expense.billNumber}`;
  }
  if (expense.attachments.length > 0) {
    return 'Bill photographed, no bill number on it';
  }
  return expense.noBillReason ?? 'No bill';
}
