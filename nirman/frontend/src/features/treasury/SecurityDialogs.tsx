import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import {
  today,
  useCreateSecurity,
  useDepositRegister,
  useForfeitSecurity,
  useLodgeSecurity,
  useRecordRetained,
  useRedeploySecurity,
  useReleaseSecurity,
  useUpdateSecurity,
} from './api';
import { INSTRUMENT_LABEL, TYPE_LABEL } from './palette';
import type {
  BankDeposit,
  Security,
  SecurityInstrument,
  SecurityProposal,
  SecurityType,
} from './types';

/** Everything the field may be lodged as. A retention is not here: it is not lodged at all. */
const LODGEABLE: SecurityInstrument[] = ['FDR', 'BANK_GUARANTEE', 'DD', 'CASH'];

interface AddProps {
  open: boolean;
  projectId: string;
  /** Pre-filled from the contract's own arithmetic, and editable — the bank rounds its way. */
  proposal: SecurityProposal | null;
  onClose: () => void;
}

/**
 * Recording a deposit the contract calls for.
 *
 * <p>The proposal fills the form and never binds it. An FDR is bought for whatever figure the
 * bank issued, a department sometimes asks for a rounder one, and a register that refused
 * anything but the computed amount would be a register the office keeps beside a notebook.</p>
 */
export function AddSecurityDialog({ open, projectId, proposal, onClose }: AddProps) {
  const create = useCreateSecurity();
  const [amount, setAmount] = useState('');
  const [instrument, setInstrument] = useState<SecurityInstrument>('FDR');
  const [expectedReleaseOn, setExpectedReleaseOn] = useState('');
  const [notes, setNotes] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setAmount(proposal ? String(proposal.amount) : '');
    setInstrument(proposal?.instrument ?? 'FDR');
    setExpectedReleaseOn(proposal?.expectedReleaseOn ?? '');
    setNotes('');
    setError(null);
  }, [open, proposal]);

  const type: SecurityType = proposal?.securityType ?? 'EMD';
  const isRetention = type === 'SECURITY_DEPOSIT';

  async function submit() {
    setError(null);
    try {
      await create.mutateAsync({
        projectId,
        securityType: type,
        instrument,
        amount: Number(amount),
        basis: proposal?.basis,
        expectedReleaseOn: expectedReleaseOn || undefined,
        notes: notes || undefined,
      });
      onClose();
    } catch (cause) {
      setError(apiErrorDetail(cause));
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Record {TYPE_LABEL[type].toLowerCase()}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {proposal && <Alert severity="info">{proposal.basis}</Alert>}
          <TextField
            label="Amount"
            value={amount}
            onChange={(event) => setAmount(event.target.value)}
            helperText="What is actually being lodged, which need not be the figure above."
          />
          <TextField
            select
            label="Held as"
            value={instrument}
            onChange={(event) => setInstrument(event.target.value as SecurityInstrument)}
            disabled={isRetention}
            helperText={
              isRetention
                ? 'A security deposit is withheld from bills, never lodged.'
                : 'Most contractors lodge a fixed deposit receipt.'
            }
          >
            {(isRetention ? (['BILL_RETENTION'] as SecurityInstrument[]) : LODGEABLE).map(
              (option) => (
                <MenuItem key={option} value={option}>
                  {INSTRUMENT_LABEL[option]}
                </MenuItem>
              ),
            )}
          </TextField>
          <TextField
            label="Expected release (optional)"
            type="date"
            InputLabelProps={{ shrink: true }}
            value={expectedReleaseOn}
            onChange={(event) => setExpectedReleaseOn(event.target.value)}
            helperText="Worked out from the contract's calendar. Change it if the department has said otherwise."
          />
          <TextField
            label="Notes (optional)"
            multiline
            minRows={2}
            value={notes}
            onChange={(event) => setNotes(event.target.value)}
          />
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={() => void submit()} disabled={create.isPending}>
          Record it
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/** The acts, each of which says something different about where the money is. */
export type SecurityAction = 'lodge' | 'retained' | 'release' | 'redeploy' | 'forfeit' | 'edit';

interface ActionProps {
  action: SecurityAction | null;
  security: Security | null;
  /** For naming a released deposit's next tender. */
  projectOptions: { id: string; code: string; name: string }[];
  onClose: () => void;
}

/**
 * One dialog for the five acts and the amendment, because they share a shape — read the row,
 * ask for the two or three facts that act needs, send it with the version.
 *
 * <p>They are separate acts rather than a status a form can be set to. Each one is a statement
 * about where the company's money went, and a dropdown reading "Released" would let somebody
 * make that statement by mistake.</p>
 */
export function SecurityActionDialog({
  action,
  security,
  projectOptions,
  onClose,
}: ActionProps) {
  const lodge = useLodgeSecurity();
  const retained = useRecordRetained();
  const release = useReleaseSecurity();
  const redeploy = useRedeploySecurity();
  const forfeit = useForfeitSecurity();
  const update = useUpdateSecurity();

  const [date, setDate] = useState(today());
  const [reference, setReference] = useState('');
  const [bankName, setBankName] = useState('');
  const [branch, setBranch] = useState('');
  const [maturityOn, setMaturityOn] = useState('');
  const [expectedReleaseOn, setExpectedReleaseOn] = useState('');
  const [amount, setAmount] = useState('');
  const [bankDepositId, setBankDepositId] = useState('');
  const [toProjectId, setToProjectId] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!action || !security) return;
    setDate(today());
    setReference(security.referenceNo ?? '');
    setBankName(security.bankName ?? '');
    setBranch(security.branch ?? '');
    setMaturityOn(security.maturityOn ?? '');
    setExpectedReleaseOn(security.expectedReleaseOn ?? '');
    setAmount(String(action === 'retained' ? security.heldAmount : security.amount));
    setBankDepositId(security.bankDepositId ?? '');
    setToProjectId('');
    setReason('');
    setError(null);
  }, [action, security]);

  if (!action || !security) {
    return null;
  }

  const busy =
    lodge.isPending ||
    retained.isPending ||
    release.isPending ||
    redeploy.isPending ||
    forfeit.isPending ||
    update.isPending;

  async function submit() {
    if (!security || !action) return;
    setError(null);
    const version = security.version;
    try {
      switch (action) {
        case 'lodge':
          await lodge.mutateAsync({
            id: security.id,
            lodgedOn: date,
            referenceNo: reference || undefined,
            bankName: bankName || undefined,
            branch: branch || undefined,
            maturityOn: maturityOn || undefined,
            expectedReleaseOn: expectedReleaseOn || undefined,
            bankDepositId: bankDepositId || undefined,
            version,
          });
          break;
        case 'retained':
          await retained.mutateAsync({
            id: security.id,
            retainedToDate: Number(amount),
            asOf: date,
            version,
          });
          break;
        case 'release':
          await release.mutateAsync({
            id: security.id,
            releasedOn: date,
            reference: reference || undefined,
            version,
          });
          break;
        case 'redeploy':
          await redeploy.mutateAsync({ id: security.id, toProjectId, version });
          break;
        case 'forfeit':
          await forfeit.mutateAsync({ id: security.id, reason, version });
          break;
        case 'edit':
          await update.mutateAsync({
            id: security.id,
            instrument: security.instrument,
            amount: Number(amount),
            basis: security.basis,
            referenceNo: reference || undefined,
            bankName: bankName || undefined,
            branch: branch || undefined,
            lodgedOn: security.lodgedOn,
            maturityOn: maturityOn || undefined,
            expectedReleaseOn: expectedReleaseOn || undefined,
            notes: security.notes,
            version,
          });
          break;
      }
      onClose();
    } catch (cause) {
      setError(apiErrorDetail(cause));
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{TITLES[action]}</DialogTitle>
      <DialogContent>
        <DialogContentText sx={{ mb: 2 }}>
          {TYPE_LABEL[security.securityType]} · {formatAmount(security.amount)}
          {security.projectCode ? ` · ${security.projectCode}` : ''}
        </DialogContentText>
        <Stack spacing={2}>
          {action === 'lodge' && (
            <>
              <TextField
                label="Lodged on"
                type="date"
                InputLabelProps={{ shrink: true }}
                value={date}
                onChange={(event) => setDate(event.target.value)}
              />
              {/*
                Naming the certificate off the register is what lets one FDR's move from a
                refused tender to the guarantee on the job that was won read as a single thread
                rather than as the same number typed twice. Optional: cash and bank guarantees
                have no certificate behind them, and one the register has not caught up with is
                still lodged by typing its number below.
              */}
              <CertificatePicker
                value={bankDepositId}
                onChange={(next, certificate) => {
                  setBankDepositId(next);
                  if (certificate) {
                    setReference(certificate.depositNumber);
                    setBankName(certificate.bankName);
                    setBranch(certificate.branch ?? '');
                    setMaturityOn(certificate.maturityOn ?? '');
                  }
                }}
              />
              <TextField
                label="Deposit / guarantee number"
                value={reference}
                onChange={(event) => setReference(event.target.value)}
              />
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <TextField
                  fullWidth
                  label="Bank"
                  value={bankName}
                  onChange={(event) => setBankName(event.target.value)}
                />
                <TextField
                  fullWidth
                  label="Branch"
                  value={branch}
                  onChange={(event) => setBranch(event.target.value)}
                />
              </Stack>
              <TextField
                label="Matures on"
                type="date"
                InputLabelProps={{ shrink: true }}
                value={maturityOn}
                onChange={(event) => setMaturityOn(event.target.value)}
                helperText="The deposit's own maturity — the screen warns if it falls before the release."
              />
              <TextField
                label="Expected release"
                type="date"
                InputLabelProps={{ shrink: true }}
                value={expectedReleaseOn}
                onChange={(event) => setExpectedReleaseOn(event.target.value)}
              />
            </>
          )}

          {action === 'retained' && (
            <>
              <Alert severity="info">
                The running total the department has withheld to date, not this bill&apos;s
                slice — that is the figure on the bill summary, and a slice sent twice would
                overstate what is held.
              </Alert>
              <TextField
                label="Withheld to date"
                value={amount}
                onChange={(event) => setAmount(event.target.value)}
                helperText={`Of ${formatAmount(security.amount)} in total.`}
              />
              <TextField
                label="As at"
                type="date"
                InputLabelProps={{ shrink: true }}
                value={date}
                onChange={(event) => setDate(event.target.value)}
              />
            </>
          )}

          {action === 'release' && (
            <>
              <TextField
                label="Released on"
                type="date"
                InputLabelProps={{ shrink: true }}
                value={date}
                onChange={(event) => setDate(event.target.value)}
              />
              <TextField
                label="Reference (optional)"
                value={reference}
                onChange={(event) => setReference(event.target.value)}
                helperText="The department's release letter, or the bank's."
              />
            </>
          )}

          {action === 'redeploy' && (
            <>
              <Alert severity="info">
                Naming the tender this deposit went on to fund takes it off the &ldquo;free to
                bid with&rdquo; list. It does not move any money.
              </Alert>
              <TextField
                select
                label="Now funding"
                value={toProjectId}
                onChange={(event) => setToProjectId(event.target.value)}
              >
                {projectOptions
                  .filter((option) => option.id !== security.projectId)
                  .map((option) => (
                    <MenuItem key={option.id} value={option.id}>
                      {option.code} — {option.name}
                    </MenuItem>
                  ))}
              </TextField>
            </>
          )}

          {action === 'forfeit' && (
            <>
              <Alert severity="warning">
                The department has kept it. This is final, and the amount leaves the blocked
                total as a loss rather than as money coming back.
              </Alert>
              <TextField
                label="Why"
                multiline
                minRows={2}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                helperText="Required. Six months on, a forfeiture with no reason is indistinguishable from an error."
              />
            </>
          )}

          {action === 'edit' && (
            <>
              <TextField
                label="Amount"
                value={amount}
                onChange={(event) => setAmount(event.target.value)}
              />
              <TextField
                label="Deposit / guarantee number"
                value={reference}
                onChange={(event) => setReference(event.target.value)}
              />
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <TextField
                  fullWidth
                  label="Bank"
                  value={bankName}
                  onChange={(event) => setBankName(event.target.value)}
                />
                <TextField
                  fullWidth
                  label="Branch"
                  value={branch}
                  onChange={(event) => setBranch(event.target.value)}
                />
              </Stack>
              <TextField
                label="Matures on"
                type="date"
                InputLabelProps={{ shrink: true }}
                value={maturityOn}
                onChange={(event) => setMaturityOn(event.target.value)}
              />
              <TextField
                label="Expected release"
                type="date"
                InputLabelProps={{ shrink: true }}
                value={expectedReleaseOn}
                onChange={(event) => setExpectedReleaseOn(event.target.value)}
              />
            </>
          )}

          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          color={action === 'forfeit' ? 'error' : 'primary'}
          onClick={() => void submit()}
          disabled={
            busy ||
            (action === 'redeploy' && !toProjectId) ||
            (action === 'forfeit' && !reason.trim())
          }
        >
          {CONFIRMS[action]}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

const TITLES: Record<SecurityAction, string> = {
  lodge: 'The deposit has gone out',
  retained: 'Withheld from bills',
  release: 'The money has come back',
  redeploy: 'Put onto another tender',
  forfeit: 'The department has kept it',
  edit: 'Amend the deposit',
};

const CONFIRMS: Record<SecurityAction, string> = {
  lodge: 'Record it as lodged',
  retained: 'Save the total',
  release: 'Record the release',
  redeploy: 'Name the tender',
  forfeit: 'Record the forfeiture',
  edit: 'Save',
};

/**
 * Which certificate out of the register is being pledged.
 *
 * <p>Only the ones that can be: a closed deposit is money the bank has already paid out, and one
 * another department is holding cannot be lodged twice — the server refuses both, and offering
 * them here would be offering a choice that fails on submit.</p>
 *
 * <p>Choosing one fills the bank details beside it, since they are on the certificate and
 * retyping them is how the two come to disagree.</p>
 */
function CertificatePicker({
  value,
  onChange,
}: {
  value: string;
  onChange: (id: string, certificate: BankDeposit | null) => void;
}) {
  const register = useDepositRegister();
  const available = (register.data?.deposits ?? []).filter(
    (row) => row.status === 'HELD' && (!row.pledgedTo || row.id === value),
  );

  return (
    <TextField
      select
      label="Fixed deposit (optional)"
      value={value}
      onChange={(event) => {
        const next = event.target.value;
        onChange(next, available.find((row) => row.id === next) ?? null);
      }}
      helperText="Off the FDR register, so the certificate's own history follows it here."
    >
      <MenuItem value="">Not off the register</MenuItem>
      {available.map((row) => (
        <MenuItem key={row.id} value={row.id}>
          {row.depositNumber} · {row.bankName} · {formatAmount(row.amount)}
        </MenuItem>
      ))}
    </TextField>
  );
}
