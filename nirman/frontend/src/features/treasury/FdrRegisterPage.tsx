import AddIcon from '@mui/icons-material/Add';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Paper,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { tokens } from '../../app/theme';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { useAuth } from '../auth/AuthContext';
import { DepositPhotos } from './DepositPhotos';
import {
  useAddDepositPhoto,
  useCloseDeposit,
  useCreateDeposit,
  useDepositRegister,
  useRemoveDepositPhoto,
  useReopenDeposit,
  useUpdateDeposit,
} from './api';
import { TreasuryTabs } from './TreasuryTabs';
import type { BankDeposit } from './types';

/**
 * Every fixed deposit the company holds, across every contract and none.
 *
 * <p>The per-contract register answers "what has this job locked up". This answers the two it
 * cannot, which are the ones asked at a bank rather than at a site: what do we hold altogether,
 * and how much of it is free to bid with. Before this, an FDR existed only as columns on whatever
 * contract it happened to be pledged against, so one bought for a tender that was refused was
 * invisible — and the answer to "what have we got" lived in a folder and somebody's memory.</p>
 *
 * <p>The idle figure is the one worth the screen. A certificate held and pledged to nothing is
 * working capital available today, and it is exactly what a contractor deciding whether he can
 * afford to bid needs to know.</p>
 */
export function FdrRegisterPage() {
  const { hasPermission } = useAuth();
  const mayRead = hasPermission('security:read');
  const mayWrite = hasPermission('security:write');

  const register = useDepositRegister(mayRead);
  const create = useCreateDeposit();
  const update = useUpdateDeposit();
  const closing = useCloseDeposit();
  const reopen = useReopenDeposit();
  const addPhoto = useAddDepositPhoto();
  const removePhoto = useRemoveDepositPhoto();

  const [form, setForm] = useState<BankDeposit | 'new' | null>(null);
  const [closeTarget, setCloseTarget] = useState<BankDeposit | null>(null);
  const [showClosed, setShowClosed] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!mayRead) {
    return (
      <Stack spacing={2}>
        <TreasuryTabs />
        <Alert severity="info">
          The deposits the company holds are kept by the office. Ask an administrator if you need
          to see them.
        </Alert>
      </Stack>
    );
  }

  /**
   * A closed certificate is history, and the register is a statement of what the company holds
   * today — so the money the bank has already paid out is off the screen unless somebody asks
   * for it. The switch carries the count, because a filter that hides rows silently reads as an
   * empty register.
   */
  const all = register.data?.deposits ?? [];
  const rows = showClosed ? all : all.filter((row) => row.status !== 'CLOSED');
  const summary = register.data?.summary;
  const closedCount = summary?.closedCount ?? all.filter((row) => row.status === 'CLOSED').length;

  async function pickPhoto(deposit: BankDeposit, file: File) {
    setError(null);
    try {
      await addPhoto.mutateAsync({ depositId: deposit.id, file });
    } catch (cause) {
      setError(apiErrorDetail(cause));
    }
  }

  async function dropPhoto(deposit: BankDeposit, attachmentId: string) {
    setError(null);
    try {
      await removePhoto.mutateAsync({ depositId: deposit.id, attachmentId });
    } catch (cause) {
      setError(apiErrorDetail(cause));
    }
  }

  async function reopenRow(deposit: BankDeposit) {
    setError(null);
    try {
      await reopen.mutateAsync({ id: deposit.id, version: deposit.version });
    } catch (cause) {
      setError(apiErrorDetail(cause));
    }
  }

  const columns: RecordColumn<BankDeposit>[] = [
    {
      key: 'certificate',
      header: 'Certificate',
      card: 'title',
      cell: (row) => (
        <Stack spacing={0.25}>
          <Typography variant="body2" fontWeight={600}>
            {row.depositNumber}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {row.bankName}
            {row.branch ? ` · ${row.branch}` : ''}
          </Typography>
        </Stack>
      ),
    },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      cell: (row) => (
        <Stack spacing={0.25} alignItems={{ xs: 'flex-start', sm: 'flex-end' }}>
          <Typography variant="body2">{formatAmount(row.amount)}</Typography>
          {row.interestRate != null && (
            <Typography variant="caption" color="text.secondary">
              {row.interestRate}% p.a.
            </Typography>
          )}
        </Stack>
      ),
    },
    {
      key: 'dates',
      header: 'Issued / matures',
      cell: (row) => (
        <Stack spacing={0.25}>
          <Typography variant="body2">{row.issuedOn}</Typography>
          <Typography variant="caption" color="text.secondary">
            {row.maturityOn ? maturityWording(row) : 'Maturity not recorded'}
          </Typography>
        </Stack>
      ),
    },
    {
      key: 'pledge',
      header: 'Pledged to',
      cell: (row) => <PledgeCell deposit={row} />,
    },
    {
      key: 'photos',
      header: 'Certificate on file',
      cell: (row) => (
        <DepositPhotos
          photos={row.photos}
          name={row.depositNumber}
          {...(mayWrite ? { onPick: (file: File) => void pickPhoto(row, file) } : {})}
          {...(mayWrite
            ? { onRemove: (attachmentId: string) => void dropPhoto(row, attachmentId) }
            : {})}
          busy={addPhoto.isPending}
        />
      ),
    },
    {
      key: 'status',
      header: 'Status',
      card: 'status',
      cell: (row) =>
        row.status === 'CLOSED' ? (
          <Chip size="small" label="Closed" variant="outlined" />
        ) : row.pledgedTo ? (
          <Chip size="small" label="Pledged" color="warning" variant="outlined" />
        ) : (
          <Chip size="small" label="In hand" color="success" variant="outlined" />
        ),
    },
  ];

  if (mayWrite) {
    columns.push({
      key: 'actions',
      header: '',
      card: 'actions',
      cell: (row) => (
        <>
          <Button size="small" onClick={() => setForm(row)}>
            Edit
          </Button>
          {row.status === 'HELD' ? (
            <Button size="small" color="warning" onClick={() => setCloseTarget(row)}>
              Close
            </Button>
          ) : (
            <Button size="small" onClick={() => void reopenRow(row)}>
              Reopen
            </Button>
          )}
        </>
      ),
    });
  }

  return (
    <Stack spacing={2}>
      <TreasuryTabs />

      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1}
        justifyContent="space-between"
        alignItems={{ sm: 'center' }}
      >
        <Box>
          <Typography variant="h5">Fixed deposits</Typography>
          <Typography variant="body2" color="text.secondary">
            Every FDR the company holds, whatever it is pledged against.
          </Typography>
        </Box>
        {mayWrite && (
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => setForm('new')}>
            Enter a deposit
          </Button>
        )}
      </Stack>

      {error && (
        <Alert severity="error" onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {summary && (
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          <Tile
            label="Free to bid with"
            amount={summary.idleAmount}
            count={summary.idleCount}
            hint="Held and pledged to nothing."
            accent
          />
          <Tile
            label="Pledged to contracts"
            amount={summary.pledgedAmount}
            count={summary.pledgedCount}
            hint="A department is holding these."
          />
          <Tile
            label="Held altogether"
            amount={summary.heldAmount}
            count={summary.heldCount}
            hint={`${summary.maturingSoonCount} maturing within 90 days.`}
          />
        </Stack>
      )}

      {closedCount > 0 && (
        <Stack direction="row" justifyContent="flex-end">
          <FormControlLabel
            control={
              <Switch
                checked={showClosed}
                onChange={(event) => setShowClosed(event.target.checked)}
              />
            }
            label={`Closed (${closedCount})`}
          />
        </Stack>
      )}

      {register.isLoading ? (
        <Stack alignItems="center" sx={{ py: 6 }}>
          <CircularProgress />
        </Stack>
      ) : (
        <RecordTable
          columns={columns}
          rows={rows}
          rowKey={(row) => row.id}
          ariaLabel="Fixed deposits"
          empty={
            <Typography variant="body2" color="text.secondary">
              {all.length > 0
                ? 'Nothing is held today. Turn on Closed to read the certificates the bank has paid out.'
                : 'No fixed deposits entered yet. Enter each certificate as it is bought, and the register will tell you what is free to bid with.'}
            </Typography>
          }
        />
      )}

      {form && (
        <DepositForm
          deposit={form === 'new' ? null : form}
          saving={create.isPending || update.isPending}
          onClose={() => setForm(null)}
          onSave={async (values) => {
            setError(null);
            try {
              if (form === 'new') {
                await create.mutateAsync(values);
              } else {
                await update.mutateAsync({ ...values, id: form.id, version: form.version });
              }
              setForm(null);
            } catch (cause) {
              setError(apiErrorDetail(cause));
            }
          }}
        />
      )}

      {closeTarget && (
        <CloseDialog
          deposit={closeTarget}
          saving={closing.isPending}
          onClose={() => setCloseTarget(null)}
          onConfirm={async (closedOn, reason) => {
            setError(null);
            try {
              await closing.mutateAsync({
                id: closeTarget.id,
                closedOn,
                reason,
                version: closeTarget.version,
              });
              setCloseTarget(null);
            } catch (cause) {
              setError(apiErrorDetail(cause));
            }
          }}
        />
      )}
    </Stack>
  );
}

/** Overdue reads as overdue: a matured certificate nobody has encashed is money sitting idle. */
function maturityWording(deposit: BankDeposit): string {
  if (deposit.daysToMaturity == null) {
    return deposit.maturityOn ?? '';
  }
  if (deposit.daysToMaturity < 0) {
    return `Matured ${-deposit.daysToMaturity} day(s) ago`;
  }
  return `Matures ${deposit.maturityOn} · ${deposit.daysToMaturity} day(s)`;
}

/**
 * Who has the certificate, and who has had it.
 *
 * <p>The history is shown as well as the live holder, because one FDR moving from a refused
 * tender to the guarantee on the job that was won is a single thread, and reading it is the
 * reason the register exists.</p>
 */
function PledgeCell({ deposit }: { deposit: BankDeposit }) {
  const past = deposit.history.filter((row) => row.securityId !== deposit.pledgedTo?.securityId);
  return (
    <Stack spacing={0.25}>
      {deposit.pledgedTo ? (
        <Typography variant="body2">
          <Link to={`/projects/${deposit.pledgedTo.projectId}/securities`}>
            {deposit.pledgedTo.projectCode ?? 'a contract'}
          </Link>
          {' · '}
          {deposit.pledgedTo.securityType.replace(/_/g, ' ').toLowerCase()}
        </Typography>
      ) : (
        <Typography variant="body2" color="text.secondary">
          Not pledged
        </Typography>
      )}
      {past.length > 0 && (
        <Typography variant="caption" color="text.secondary">
          Was with {past.map((row) => row.projectCode ?? 'a contract').join(', ')}
        </Typography>
      )}
    </Stack>
  );
}

function Tile({
  label,
  amount,
  count,
  hint,
  accent,
}: {
  label: string;
  amount: number;
  count: number;
  hint: string;
  accent?: boolean;
}) {
  return (
    <Paper
      variant="outlined"
      sx={{
        p: 2,
        flex: 1,
        borderColor: accent ? tokens.signal : 'divider',
      }}
    >
      <Typography variant="overline" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h6">{formatAmount(amount)}</Typography>
      <Typography variant="caption" color="text.secondary">
        {count} certificate(s). {hint}
      </Typography>
    </Paper>
  );
}

/** The certificate as the bank issued it, transcribed. Amounts and dates, nothing derived. */
function DepositForm({
  deposit,
  saving,
  onClose,
  onSave,
}: {
  deposit: BankDeposit | null;
  saving: boolean;
  onClose: () => void;
  onSave: (values: {
    depositNumber: string;
    bankName: string;
    branch?: string | undefined;
    amount: number;
    issuedOn: string;
    maturityOn?: string | undefined;
    interestRate?: number | undefined;
    notes?: string | undefined;
  }) => void;
}) {
  const [depositNumber, setDepositNumber] = useState(deposit?.depositNumber ?? '');
  const [bankName, setBankName] = useState(deposit?.bankName ?? '');
  const [branch, setBranch] = useState(deposit?.branch ?? '');
  const [amount, setAmount] = useState(deposit?.amount?.toString() ?? '');
  const [issuedOn, setIssuedOn] = useState(deposit?.issuedOn ?? '');
  const [maturityOn, setMaturityOn] = useState(deposit?.maturityOn ?? '');
  const [interestRate, setInterestRate] = useState(deposit?.interestRate?.toString() ?? '');
  const [notes, setNotes] = useState(deposit?.notes ?? '');

  const incomplete = !depositNumber.trim() || !bankName.trim() || !amount.trim() || !issuedOn;

  return (
    <Dialog open fullWidth maxWidth="sm" onClose={onClose}>
      <DialogTitle>{deposit ? `Edit ${deposit.depositNumber}` : 'Enter a fixed deposit'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <TextField
            label="Certificate number"
            value={depositNumber}
            onChange={(event) => setDepositNumber(event.target.value)}
            helperText="As printed on the FDR. One row per certificate."
          />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Bank"
              value={bankName}
              onChange={(event) => setBankName(event.target.value)}
              fullWidth
            />
            <TextField
              label="Branch (optional)"
              value={branch}
              onChange={(event) => setBranch(event.target.value)}
              fullWidth
            />
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Amount"
              value={amount}
              onChange={(event) => setAmount(event.target.value)}
              fullWidth
            />
            <TextField
              label="Interest rate (optional)"
              value={interestRate}
              onChange={(event) => setInterestRate(event.target.value)}
              helperText="Per cent per annum."
              fullWidth
            />
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Issued on"
              type="date"
              value={issuedOn}
              onChange={(event) => setIssuedOn(event.target.value)}
              InputLabelProps={{ shrink: true }}
              fullWidth
            />
            <TextField
              label="Matures on (optional)"
              type="date"
              value={maturityOn}
              onChange={(event) => setMaturityOn(event.target.value)}
              InputLabelProps={{ shrink: true }}
              helperText="Leave empty until it is read off the certificate."
              fullWidth
            />
          </Stack>
          <TextField
            label="Notes (optional)"
            value={notes}
            onChange={(event) => setNotes(event.target.value)}
            multiline
            minRows={2}
          />
          {deposit && (
            <Alert severity="info">
              Photographs are added from the register row, and stay with the certificate when it
              is released and pledged elsewhere.
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={incomplete || saving}
          onClick={() =>
            onSave({
              depositNumber: depositNumber.trim(),
              bankName: bankName.trim(),
              branch: branch.trim() || undefined,
              amount: Number(amount),
              issuedOn,
              maturityOn: maturityOn || undefined,
              interestRate: interestRate.trim() ? Number(interestRate) : undefined,
              notes: notes.trim() || undefined,
            })
          }
        >
          {deposit ? 'Save changes' : 'Enter deposit'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/**
 * The bank has paid it out.
 *
 * <p>Refused by the server while a department is still holding the certificate, and the dialog
 * says so up front rather than letting somebody discover it after typing a date: a certificate
 * the register calls closed and a department is holding is the same money counted two ways.</p>
 */
function CloseDialog({
  deposit,
  saving,
  onClose,
  onConfirm,
}: {
  deposit: BankDeposit;
  saving: boolean;
  onClose: () => void;
  onConfirm: (closedOn: string, reason: string | undefined) => void;
}) {
  const [closedOn, setClosedOn] = useState('');
  const [reason, setReason] = useState('');

  return (
    <Dialog open fullWidth maxWidth="xs" onClose={onClose}>
      <DialogTitle>Close {deposit.depositNumber}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {deposit.pledgedTo && (
            <Alert severity="warning">
              {deposit.pledgedTo.projectCode ?? 'A contract'} still has this lodged. Release it
              there first.
            </Alert>
          )}
          <TextField
            label="Closed on"
            type="date"
            value={closedOn}
            onChange={(event) => setClosedOn(event.target.value)}
            InputLabelProps={{ shrink: true }}
            helperText="The day the bank paid it out."
          />
          <TextField
            label="Reason (optional)"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            placeholder="Matured and encashed"
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          color="warning"
          disabled={!closedOn || saving}
          onClick={() => onConfirm(closedOn, reason.trim() || undefined)}
        >
          Close deposit
        </Button>
      </DialogActions>
    </Dialog>
  );
}
