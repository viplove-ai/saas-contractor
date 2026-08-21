import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { useAgreement, useSaveAgreement } from './api';

/*
  The tender's own details, asked once — at the first bill of that tender — and standing for
  every bill after it.

  Two kinds of thing, here for the same reason. The names print on every page of every bill
  and change only between tenders. The rate chain is the arithmetic that turns a published
  schedule rate into what this contract pays: schedule rate × coefficient, + cost index,
  − tender percentage. Both used to be retyped into forty-three worksheets, which is how the
  source workbook came to say "3rd RA Bill" on its measurement pages and "4th RA Bill" on its
  abstract.

  The derivation of a round thousand is shown live, because a rate chain asserted is a rate
  chain nobody checks.
*/

interface Props {
  projectId: string;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}

export function AgreementDialog({ projectId, open, onClose, onSaved }: Props) {
  const existing = useAgreement(projectId);
  const save = useSaveAgreement(projectId);
  const [error, setError] = useState<string | null>(null);

  const [form, setForm] = useState({
    agreementNo: '',
    division: '',
    contractorName: '',
    measuredByName: '',
    measuredByDesignation: '',
    preparedByName: '',
    preparedByDesignation: '',
    checkedByName: '',
    checkedByDesignation: '',
    executiveEngineer: '',
    cmbNo: '',
    dsrCoefficient: '1',
    costIndexPct: '0',
    tenderPct: '0',
    deviationLimitPct: '100',
  });

  useEffect(() => {
    if (existing.data) {
      setForm((current) => ({
        ...current,
        agreementNo: existing.data?.agreementNo ?? '',
        division: existing.data?.division ?? '',
        contractorName: existing.data?.contractorName ?? '',
        measuredByName: existing.data?.measuredByName ?? '',
        measuredByDesignation: existing.data?.measuredByDesignation ?? '',
        preparedByName: existing.data?.preparedByName ?? '',
        preparedByDesignation: existing.data?.preparedByDesignation ?? '',
        checkedByName: existing.data?.checkedByName ?? '',
        checkedByDesignation: existing.data?.checkedByDesignation ?? '',
        executiveEngineer: existing.data?.executiveEngineer ?? '',
        cmbNo: existing.data?.cmbNo ?? '',
        dsrCoefficient: existing.data?.dsrCoefficient ?? '1',
        costIndexPct: existing.data?.costIndexPct ?? '0',
        tenderPct: existing.data?.tenderPct ?? '0',
        deviationLimitPct: existing.data?.deviationLimitPct ?? '100',
      }));
    }
  }, [existing.data]);

  const set = (key: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [key]: event.target.value }));

  // The same three steps the server applies, shown on a round thousand.
  const preview = () => {
    const coefficient = Number(form.dsrCoefficient) || 0;
    const costIndex = Number(form.costIndexPct) || 0;
    const tender = Number(form.tenderPct) || 0;
    const afterCoefficient = Math.round(1000 * coefficient * 100) / 100;
    const afterIndex = Math.round((afterCoefficient + (afterCoefficient * costIndex) / 100) * 100) / 100;
    const final = Math.round((afterIndex + (afterIndex * tender) / 100) * 100) / 100;
    return { afterCoefficient, afterIndex, final };
  };

  const submit = async () => {
    setError(null);
    try {
      await save.mutateAsync({
        ...form,
        dsrCoefficient: form.dsrCoefficient,
        costIndexPct: form.costIndexPct,
        tenderPct: form.tenderPct,
        deviationLimitPct: form.deviationLimitPct,
      } as never);
      onSaved();
    } catch (caught) {
      setError(apiErrorDetail(caught));
    }
  };

  const { afterCoefficient, afterIndex, final } = preview();

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Tender details</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          <Alert severity="info">
            Asked once, for the first bill of this tender. Every bill after it uses the same
            details.
          </Alert>
          {error && <Alert severity="error">{error}</Alert>}

          <TextField label="Agreement no." value={form.agreementNo} onChange={set('agreementNo')} fullWidth />
          <TextField label="Contractor" value={form.contractorName} onChange={set('contractorName')} fullWidth />
          <TextField label="Division" value={form.division} onChange={set('division')} fullWidth />

          <Divider />
          <Typography variant="subtitle2">Who signs</Typography>
          <Stack direction="row" spacing={2}>
            <TextField label="Measured by" value={form.measuredByName} onChange={set('measuredByName')} sx={{ flex: 2 }} />
            <TextField label="Rank" value={form.measuredByDesignation} onChange={set('measuredByDesignation')} sx={{ flex: 1 }} />
          </Stack>
          <Stack direction="row" spacing={2}>
            <TextField label="Prepared by" value={form.preparedByName} onChange={set('preparedByName')} sx={{ flex: 2 }} />
            <TextField label="Rank" value={form.preparedByDesignation} onChange={set('preparedByDesignation')} sx={{ flex: 1 }} />
          </Stack>
          <Stack direction="row" spacing={2}>
            <TextField label="Checked by" value={form.checkedByName} onChange={set('checkedByName')} sx={{ flex: 2 }} />
            <TextField label="Rank" value={form.checkedByDesignation} onChange={set('checkedByDesignation')} sx={{ flex: 1 }} />
          </Stack>
          <TextField label="Executive Engineer" value={form.executiveEngineer} onChange={set('executiveEngineer')} fullWidth />
          <TextField label="CMB no." value={form.cmbNo} onChange={set('cmbNo')} fullWidth />

          <Divider />
          <Typography variant="subtitle2">How a schedule rate becomes this contract's rate</Typography>
          <Stack direction="row" spacing={2}>
            <TextField
              label="Coefficient"
              type="number"
              inputProps={{ step: '0.001' }}
              value={form.dsrCoefficient}
              onChange={set('dsrCoefficient')}
              sx={{ flex: 1 }}
            />
            <TextField
              label="Cost index %"
              type="number"
              inputProps={{ step: '0.01' }}
              value={form.costIndexPct}
              onChange={set('costIndexPct')}
              sx={{ flex: 1 }}
            />
            <TextField
              label="Tender %"
              type="number"
              inputProps={{ step: '0.01' }}
              value={form.tenderPct}
              onChange={set('tenderPct')}
              helperText="Negative is below"
              sx={{ flex: 1 }}
            />
          </Stack>
          <Alert severity="info" icon={false}>
            A schedule rate of 1000.00 becomes{' '}
            <strong>{afterCoefficient.toFixed(2)}</strong> after the coefficient,{' '}
            <strong>{afterIndex.toFixed(2)}</strong> after the cost index, and{' '}
            <strong>{final.toFixed(2)}</strong> after the tender percentage.
          </Alert>
          <TextField
            label="Deviation limit %"
            type="number"
            value={form.deviationLimitPct}
            onChange={set('deviationLimitPct')}
            fullWidth
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={save.isPending} onClick={() => void submit()}>
          Save and continue
        </Button>
      </DialogActions>
    </Dialog>
  );
}
