import {
  Alert,
  Button,
  Divider,
  FormControlLabel,
  MenuItem,
  Paper,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { useMemo, useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { useSelectedSite } from '../../shared/siteSelection';
import { ReferenceNotice } from '../../shared/ReferenceNotice';
import { StatusChip, type RecordStatus } from '../../shared/StatusChip';
import { useAuth } from '../auth/AuthContext';
import { AllocationChip } from './AllocationChip';
import { BillPhotoField } from './BillPhotoField';
import {
  duplicateCandidates,
  useAttachBill,
  useCreateExpense,
  useExpenseCategories,
  useExpenses,
  useMyFloat,
  useNameExpenseCategory,
  useReviseExpense,
  useSites,
  useSubmitExpense,
  useUpdateExpense,
} from './api';
import {
  CATEGORY_OTHER,
  type DuplicateCandidate,
  type Expense,
  type ExpenseWorkflow,
} from './types';

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
  const [expenseDate, setExpenseDate] = useState(today());
  const [categoryId, setCategoryId] = useState('');
  /** What he called it, when the taxonomy has no head for what the money went on. */
  const [otherName, setOtherName] = useState('');
  const [namingError, setNamingError] = useState<string | null>(null);
  const [description, setDescription] = useState('');
  const [billNumber, setBillNumber] = useState('');
  const [amountBeforeTax, setAmountBeforeTax] = useState('');
  const [gstPercent, setGstPercent] = useState('0');
  const [noBillReason, setNoBillReason] = useState('');
  /**
   * The part of this bill that is a deposit and comes back.
   *
   * <p>Behind a switch rather than always on screen. Almost no bill has one, and a box asking
   * "how much of this is refundable" on every ₹200 of cartage is a box that gets a zero typed
   * into it out of habit — and then, on the one bill that does have a deposit, gets a zero
   * out of the same habit.</p>
   */
  const [hasDeposit, setHasDeposit] = useState(false);
  const [refundableAmount, setRefundableAmount] = useState('');
  const [refundExpectedOn, setRefundExpectedOn] = useState('');
  const [overrideReason, setOverrideReason] = useState('');
  const [candidates, setCandidates] = useState<DuplicateCandidate[] | null>(null);
  const [photo, setPhoto] = useState<File | null>(null);
  // The id is fixed when the form is opened, not when Save is pressed. The photograph is
  // attached to it before the expense exists anywhere, and a fresh uuid at save time would
  // leave the photograph pointing at a record that was never created.
  const [expenseId, setExpenseId] = useState(() => crypto.randomUUID());
  /**
   * The expense being corrected, or null when the form is booking a new one.
   *
   * <p>One form for both. An expense the office sent back is wrong in the same fields it was
   * typed in, and a second screen to fix them would be this one with the values filled in —
   * which is what this is.</p>
   */
  const [editing, setEditing] = useState<Expense | null>(null);
  const [editError, setEditError] = useState<string | null>(null);
  /**
   * Why an already-approved expense is being re-opened.
   *
   * <p>Asked only in that case, and required there. A draft is corrected before anybody has
   * looked at it and owes nobody an explanation; a figure somebody signed does — the approver
   * is about to be asked to sign it a second time, and "it changed" is not a reason he can
   * act on.</p>
   */
  const [revisionReason, setRevisionReason] = useState('');

  const sites = useSites();
  const [siteId, setSiteId] = useSelectedSite(sites.data);
  const categories = useExpenseCategories();
  const nameCategory = useNameExpenseCategory();
  const create = useCreateExpense();
  const change = useUpdateExpense();
  const reopen = useReviseExpense();
  const attachBill = useAttachBill();
  const submit = useSubmitExpense();
  const mine = useExpenses(siteId || undefined, '' as ExpenseWorkflow | '');

  const canSubmit = hasPermission('expense:create');
  const canNameHead = hasPermission('masterdata:provisional:head');

  const total = useMemo(() => {
    const base = Number(amountBeforeTax);
    const rate = Number(gstPercent);
    if (!Number.isFinite(base) || !Number.isFinite(rate)) return 0;
    return base + (base * rate) / 100;
  }, [amountBeforeTax, gstPercent]);

  /** A kind is named either by picking one or by typing one. Both count. */
  const kindNamed =
    categoryId === CATEGORY_OTHER ? otherName.trim().length > 0 : Boolean(categoryId);

  /**
   * The deposit, checked here so the server's refusal is the backstop rather than the first
   * anybody hears of it. More than the bill is a figure typed the wrong way round, and a
   * switch turned on with nothing in the box is a question half-answered.
   */
  const deposit = Number(refundableAmount);
  const depositValid =
    !hasDeposit
    || (Number.isFinite(deposit) && deposit > 0 && deposit <= Math.round(total * 100) / 100);

  const complete =
    Boolean(siteId)
    && kindNamed
    && description.trim().length > 0
    && total > 0
    && depositValid;

  /** One button does three calls, so every one of them has to hold it disabled. */
  const correcting =
    change.isPending ||
    reopen.isPending ||
    attachBill.isPending ||
    submit.isPending ||
    nameCategory.isPending;

  /** Correcting something already signed, rather than something nobody has looked at yet. */
  const reopening = editing?.workflowStatus === 'APPROVED';
  const reasonGiven = !reopening || revisionReason.trim().length > 0;

  const site = sites.data?.find((candidate) => candidate.id === siteId);
  const booked = create.data;
  // Sending a row from the list below must not change what the banner says about the draft
  // just booked, so the ids have to match and not merely the mutation having succeeded once.
  const justSent =
    submit.isSuccess && booked?.outcome === 'SENT' && submit.data?.id === booked.expense.id;

  /**
   * Books the expense, opening a head first if he had to name the kind himself.
   *
   * <p>The naming has to happen first and against the server: an expense carries a head id
   * and there is nowhere to put a phrase instead. That is also why it is the one thing on
   * this screen that a phone with no signal cannot do — the expense itself queues, a new
   * head does not, and booking it under the wrong head to save the trip is the mess this
   * whole answer exists to avoid.</p>
   */
  const clearDeposit = () => {
    setHasDeposit(false);
    setRefundableAmount('');
    setRefundExpectedOn('');
  };

  /**
   * What goes on the request. Zero when the switch is off, so that turning it off on a
   * correction actually removes the deposit rather than leaving the old figure standing.
   */
  const depositFields = () => ({
    refundableAmount: hasDeposit ? Number(refundableAmount) : 0,
    refundExpectedOn: hasDeposit && refundExpectedOn ? refundExpectedOn : undefined,
  });

  /** Puts a sent-back expense into the form above, where it was typed in the first place. */
  const openForCorrection = (expense: Expense) => {
    setEditing(expense);
    setEditError(null);
    setRevisionReason('');
    setCandidates(null);
    setExpenseDate(expense.expenseDate);
    setCategoryId(expense.categoryId);
    setOtherName('');
    setDescription(expense.description);
    setBillNumber(expense.billNumber ?? '');
    setAmountBeforeTax(String(expense.amountBeforeTax));
    setGstPercent(String(expense.gstPercent));
    setNoBillReason(expense.noBillReason ?? '');
    setHasDeposit(expense.refundableAmount > 0);
    setRefundableAmount(expense.refundableAmount > 0 ? String(expense.refundableAmount) : '');
    setRefundExpectedOn(expense.refundExpectedOn ?? '');
    setPhoto(null);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const stopCorrecting = () => {
    setEditing(null);
    setEditError(null);
    setRevisionReason('');
    setDescription('');
    setBillNumber('');
    setAmountBeforeTax('');
    setNoBillReason('');
    clearDeposit();
    setPhoto(null);
    setExpenseDate(today());
  };

  /**
   * Saves the correction and sends it back for approval in one act.
   *
   * <p>Booking and sending are deliberately two acts for a new expense — a wrong figure caught
   * before it goes costs a correction on screen rather than an approval chased back. A
   * correction is not that: the row has already been sent once and looked at, the office is
   * waiting on this exact answer, and leaving it sitting as a draft is how a returned expense
   * is never seen again.</p>
   */
  const correct = async () => {
    if (!editing) return;
    setEditError(null);
    let headId = categoryId;
    if (categoryId === CATEGORY_OTHER) {
      try {
        headId = (await nameCategory.mutateAsync({ name: otherName.trim() })).id;
      } catch (error) {
        setNamingError(apiErrorDetail(error));
        return;
      }
      setCategoryId(headId);
      setOtherName('');
    }
    const edit = {
      id: editing.id,
      expenseDate,
      categoryId: headId,
      description: description.trim(),
      billNumber: billNumber || undefined,
      amountBeforeTax: Number(amountBeforeTax),
      gstPercent: Number(gstPercent),
      noBillReason: noBillReason || undefined,
      ...depositFields(),
      version: editing.version,
    };
    try {
      /*
        Two doors, and which one is open depends on whether anybody has signed it.

        A draft or a returned row is edited and then sent — two calls, because they are two
        acts and the second can fail on its own. An approved one is re-opened instead: one
        call that cancels the approval, keeps the number and puts it back in the queue. It
        cannot be the first pair, because between the edit and the send there would be a
        moment when an approved expense carried a figure nobody had approved.
      */
      const saved = reopening
        ? await reopen.mutateAsync({ ...edit, reason: revisionReason.trim() })
        : await change.mutateAsync(edit);
      // The bill after the figures, and only if one was picked: "there is no bill" is one of
      // the two things an expense comes back for, and the photograph is the answer to it.
      if (photo) {
        await attachBill.mutateAsync({
          expenseId: saved.id,
          siteId: saved.siteId,
          file: photo,
        });
      }
      if (!reopening) {
        await submit.mutateAsync(saved.id);
      }
      stopCorrecting();
    } catch (error) {
      setEditError(apiErrorDetail(error));
    }
  };

  const book = async (force: boolean) => {
    setNamingError(null);
    let headId = categoryId;
    if (categoryId === CATEGORY_OTHER) {
      try {
        headId = (await nameCategory.mutateAsync({ name: otherName.trim() })).id;
      } catch (error) {
        setNamingError(apiErrorDetail(error));
        return;
      }
      // Now a head like any other, and selected — the next bill of the same kind picks it
      // off the list rather than naming it a second time.
      setCategoryId(headId);
      setOtherName('');
    }

    create.mutate(
      {
        force,
        siteLabel: site ? `${site.code} ${site.name}` : 'site',
        photo: photo ?? undefined,
        input: {
          id: expenseId,
          siteId,
          expenseDate,
          categoryId: headId,
          description: description.trim(),
          billNumber: billNumber || undefined,
          amountBeforeTax: Number(amountBeforeTax),
          gstPercent: Number(gstPercent),
          noBillReason: noBillReason || undefined,
          ...depositFields(),
          duplicateOverrideReason: force ? overrideReason : undefined,
        },
      },
      {
        onSuccess: () => {
          setDescription('');
          setBillNumber('');
          setAmountBeforeTax('');
          setNoBillReason('');
          clearDeposit();
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
      <Typography variant="h1">{editing ? 'Correct the expense' : 'Add expense'}</Typography>

      {/*
        What the office actually said, at the top of the form that answers it. A supervisor
        looking at a returned row two days later does not remember the reason, and a correction
        made without it is the same expense sent back a second time.
      */}
      {editing && (
        <Alert severity={editing.workflowStatus === 'REJECTED' ? 'error' : 'warning'}>
          <Typography fontWeight={600}>
            {editing.expenseNumber} —{' '}
            {editing.workflowStatus === 'REJECTED'
              ? 'the office rejected this'
              : editing.workflowStatus === 'RETURNED'
                ? 'the office sent this back to be fixed'
                : reopening
                  ? 'this has already been approved'
                  : 'a draft you have not sent yet'}
          </Typography>
          {editing.rejectionReason && (
            <Typography variant="body2">{editing.rejectionReason}</Typography>
          )}
          <Typography variant="body2">
            {reopening
              ? 'It keeps its number, its approval is cancelled, and it goes back for a new one.'
              : 'Fix it below and it goes back for approval when you save.'}
          </Typography>
        </Alert>
      )}

      {/*
        The reason, at the top where the banner explaining why it is wanted is. Required for
        this one case: the approver is about to be asked to sign the same expense a second
        time, and he cannot act on "it changed".
      */}
      {reopening && (
        <TextField
          label="What was wrong with it"
          value={revisionReason}
          onChange={(e) => setRevisionReason(e.target.value)}
          helperText="The office approved this figure once. Say what has to change and why."
        />
      )}

      {/*
        What he is carrying, above the form that spends it. Drawn only when the office has
        actually handed him something — a strip saying "you are holding nothing" on the screen
        of every supervisor who was never given a float is a line that teaches the reader to
        stop looking at that part of the page.
      */}
      <FloatStrip siteId={siteId} />

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField
          select
          label="Site"
          value={siteId}
          onChange={(e) => setSiteId(e.target.value)}
          // An expense does not move site: the site is what scoped every approval already
          // taken on it. Changing it is booking a different expense, and the screen above
          // does that.
          disabled={Boolean(editing)}
          helperText={editing ? 'An expense stays at the site it was booked to' : undefined}
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
        <Stack spacing={1} sx={{ flexGrow: 1, minWidth: 220 }}>
          <TextField
            select
            label="What kind"
            value={categoryId}
            onChange={(e) => setCategoryId(e.target.value)}
          >
            {(categories.data ?? []).map((category) => (
              <MenuItem key={category.id} value={category.id}>
                {category.name}
              </MenuItem>
            ))}
            {/*
              Last, and only for somebody allowed to name a head. The starting taxonomy is
              the CPWD one and no site's spending fits it entirely — without this answer the
              bill goes under whichever head is least wrong, and the office reads a
              "Miscellaneous" figure it cannot break down.
            */}
            {canNameHead && <MenuItem value={CATEGORY_OTHER}>Other…</MenuItem>}
          </TextField>
          {categoryId === CATEGORY_OTHER && (
            <TextField
              label="What kind of expense is it"
              value={otherName}
              onChange={(e) => setOtherName(e.target.value)}
              helperText="A few words the office will recognise — it becomes a head everyone can book to."
            />
          )}
        </Stack>
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

      {/*
        The part of the bill that is coming back.

        Behind a switch, and off by default, because almost no bill has one — a box asking
        "how much of this is refundable" on every ₹200 of cartage is a box that gets a zero
        typed into it out of habit, and then gets a zero out of the same habit on the one bill
        that does. The vendor is still paid the whole amount; what this changes is what the
        job is told the bill cost, and it puts the money on a register that chases it.
      */}
      <Paper variant="outlined" sx={{ p: 2 }}>
        <FormControlLabel
          control={
            <Switch
              checked={hasDeposit}
              onChange={(e) => {
                setHasDeposit(e.target.checked);
                if (!e.target.checked) {
                  setRefundableAmount('');
                  setRefundExpectedOn('');
                }
              }}
            />
          }
          label="Part of this comes back to us"
        />
        <Typography variant="body2" color="text.secondary">
          A security deposit on a connection, money down on hired plant, a cylinder deposit —
          money we placed and get back when the thing is returned. It is not what the work cost.
        </Typography>

        {hasDeposit && (
          <Stack spacing={2} sx={{ mt: 2 }}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                label="Refundable amount"
                type="number"
                inputMode="decimal"
                value={refundableAmount}
                onChange={(e) => setRefundableAmount(e.target.value)}
                error={refundableAmount.length > 0 && !depositValid}
                helperText={
                  refundableAmount.length > 0 && !depositValid
                    ? `It is part of this bill, so it cannot be more than ${formatAmount(total)}.`
                    : 'Out of the total above, not on top of it'
                }
                sx={{ minWidth: 200 }}
              />
              <TextField
                label="Expected back on"
                type="date"
                value={refundExpectedOn}
                onChange={(e) => setRefundExpectedOn(e.target.value)}
                InputLabelProps={{ shrink: true }}
                helperText="Leave blank if nobody knows"
                sx={{ minWidth: 200 }}
              />
            </Stack>
            {depositValid && deposit > 0 && (
              <Alert severity="info">
                The vendor is still paid {formatAmount(total)}. Of that, {formatAmount(deposit)}{' '}
                is ours and comes back — so this bill costs the work{' '}
                {formatAmount(total - deposit)}, and the deposit goes on the register until it
                is settled.
              </Alert>
            )}
          </Stack>
        )}
      </Paper>

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
            onClick={() => void book(true)}
            sx={{ minHeight: 48, mt: 1 }}
          >
            Book it anyway
          </Button>
        </Alert>
      )}

      {/*
        Its own line rather than folded into the booking error: the expense has not been
        saved, and what failed was naming the kind, which is the one part of this form that
        needs the server.
      */}
      {namingError && <Alert severity="error">{namingError}</Alert>}
      {create.isError && !candidates && (
        <Alert severity="error">{apiErrorDetail(create.error)}</Alert>
      )}
      {/*
        The draft that was just booked, with the send beside it.

        Booking and sending stay separate acts — a wrong figure caught before it goes costs a
        correction on screen rather than an approval chased back — but "when you are ready" is
        usually now, and the row it means is the one that has just scrolled out of sight under
        the recent list. The same button on the same expense, where the person is looking.
      */}
      {/* Hidden while a correction is open: the form no longer holds what that banner describes. */}
      {booked?.outcome === 'SENT' && !editing && (
        <Alert
          severity="success"
          action={
            justSent ? undefined : (
              <Button
                color="inherit"
                disabled={submit.isPending}
                onClick={() => submit.mutate(booked.expense.id)}
                sx={{ minHeight: 48 }}
              >
                {submit.isPending ? 'Sending…' : 'Send for approval'}
              </Button>
            )
          }
        >
          {justSent
            ? `${booked.expense.expenseNumber} has gone for approval.`
            : `Saved ${booked.expense.expenseNumber} as a draft. Send it now, or leave it and send it from the list below.`}
        </Alert>
      )}
      {booked?.outcome === 'QUEUED' && !editing && (
        <Alert severity="info">
          No connection. This expense{booked.photoQueued ? ' and its photograph are' : ' is'} saved
          on this phone and goes out by itself when there is a signal.
        </Alert>
      )}

      {editError && <Alert severity="error">{editError}</Alert>}

      <Stack direction="row" spacing={2}>
        {editing ? (
          <>
            <Button
              variant="contained"
              color="secondary"
              disabled={!complete || !reasonGiven || correcting}
              onClick={() => void correct()}
              sx={{ minHeight: 48 }}
            >
              {correcting
                ? 'Sending…'
                : reopening
                  ? 'Re-open and send for approval again'
                  : 'Save and send for approval'}
            </Button>
            <Button onClick={stopCorrecting} disabled={correcting} sx={{ minHeight: 48 }}>
              Cancel
            </Button>
          </>
        ) : (
          <Button
            variant="contained"
            color="secondary"
            disabled={!complete || create.isPending || nameCategory.isPending}
            onClick={() => void book(false)}
            sx={{ minHeight: 48 }}
          >
            {create.isPending || nameCategory.isPending ? 'Saving…' : 'Save as draft'}
          </Button>
        )}
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
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                  <Typography fontWeight={600}>{expense.expenseNumber}</Typography>
                  <StatusChip status={chipStatus(expense.workflowStatus)} />
                  {/*
                    Only once somebody has decided. Before that the row is carrying its head's
                    proposal, and a label saying "Company expense" on a bill nobody has looked
                    at yet is the screen answering a question on the approver's behalf.
                  */}
                  {expense.allocatedAt && <AllocationChip expense={expense} />}
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
              {/*
                Both answers to a row the office sent back, side by side. Sending it again
                unchanged is the right move when nothing was wrong with the expense — the
                approver had a question and it was answered on the telephone — and correcting
                it is the right move the rest of the time. Offering only the first is what left
                a rejected expense with nowhere to go but a second identical submission.
              */}
              {canSubmit && isSendable(expense.workflowStatus) && (
                <Stack direction="row" spacing={1}>
                  <Button
                    variant="outlined"
                    disabled={correcting || editing?.id === expense.id}
                    onClick={() => openForCorrection(expense)}
                    sx={{ minHeight: 48 }}
                  >
                    {editing?.id === expense.id ? 'Correcting above' : 'Correct it'}
                  </Button>
                  <Button
                    variant="contained"
                    color="secondary"
                    disabled={submit.isPending}
                    onClick={() => submit.mutate(expense.id)}
                    sx={{ minHeight: 48 }}
                  >
                    Send for approval
                  </Button>
                </Stack>
              )}
              {/*
                And the row that has already been through. Until now a mistyped figure that
                had been approved had one answer — telephone the office — because voiding it
                and booking a replacement gives the bill a second number and leaves the
                supplier's paper disagreeing with the system. Offered only while nothing has
                been paid against it: after that the answer really is a void, and the server
                says so in those words.
              */}
              {canSubmit && expense.workflowStatus === 'APPROVED' && expense.paidAmount === 0 && (
                <Button
                  variant="outlined"
                  disabled={correcting || editing?.id === expense.id}
                  onClick={() => openForCorrection(expense)}
                  sx={{ minHeight: 48 }}
                >
                  {editing?.id === expense.id ? 'Re-opening above' : 'Re-open to correct'}
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
/**
 * What the person filling this form is carrying at this site.
 *
 * <p>The one screen where the figure changes anything, and the one screen that could not tell
 * him. A supervisor about to book a ₹9,000 purchase knows he was handed something last week and
 * rarely what is left of it, so the answer came by telephone or not at all — and the bill got
 * booked either way, because the lorry was at the gate.</p>
 *
 * <p><b>The figure has a sign and both signs are said in words.</b> Positive is cash still in
 * his pocket. Negative means he has already paid more than he was given, which is not an error
 * and not a debt of his: the company owes him that much, and it is the fact he most wants on
 * the screen when he is about to spend again.</p>
 *
 * <p>Its own component so that the balance query is scoped to it. The strip is silent for
 * everybody the office has never handed a float to — which is most people — and a line reading
 * "you are holding nothing" on every one of their screens is how a reader learns to stop
 * looking at that part of the page.</p>
 */
function FloatStrip({ siteId }: { siteId: string }) {
  const float = useMyFloat(siteId || undefined);
  const mine = (float.data ?? []).find((row) => row.siteId === siteId);
  if (!mine || (mine.inHandAmount === 0 && mine.issuedAmount === 0)) {
    return null;
  }
  const owed = mine.inHandAmount < 0;
  return (
    <Alert severity={owed ? 'warning' : 'info'} icon={false}>
      <Typography fontWeight={600}>
        {owed
          ? `The company owes you ${formatAmount(-mine.inHandAmount)}`
          : `You are holding ${formatAmount(mine.inHandAmount)} of site cash`}
      </Typography>
      <Typography variant="body2">
        {formatAmount(mine.issuedAmount)} handed to you, {formatAmount(mine.spentAmount)} charged
        back against bills
        {mine.returnedAmount > 0 && <>, {formatAmount(mine.returnedAmount)} returned</>}.
        {owed
          ? ' You have paid past your float — the office settles it by handing you cash or another advance.'
          : ' The office charges an approved bill back against this when you paid it yourself.'}
      </Typography>
    </Alert>
  );
}

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
