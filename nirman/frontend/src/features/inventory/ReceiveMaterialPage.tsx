import AddIcon from '@mui/icons-material/Add';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import {
  Alert,
  Button,
  Divider,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useMemo, useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount, formatQuantity } from '../../shared/formatters';
import { ReferenceNotice } from '../../shared/ReferenceNotice';
import { StatusChip } from '../../shared/StatusChip';
import { useAuth } from '../auth/AuthContext';
import { useCreateReceipt, useMaterials, useReceipts, useUnits, useVerifyReceipt } from './api';
import { StorePicker } from './StorePicker';
import type { LineDraft } from './types';

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function emptyLine(): LineDraft {
  return { key: crypto.randomUUID(), materialId: '', unitId: '', quantity: '', rate: '' };
}

/**
 * Booking a delivery at the gate.
 *
 * <p>The screen is deliberately in the units the challan is written in. A lorry arrives
 * with a paper saying two tonnes of steel, and asking the storekeeper to convert that to
 * kilograms before he can type it is asking him to make an arithmetic mistake at the gate.
 * He types what the paper says; the server converts and stores base units.</p>
 *
 * <p><b>Nothing here changes the stock.</b> What this produces is a claim about what turned
 * up, waiting for an engineer to check it against the challan. The queue underneath is
 * where that happens, and it is on the same screen because at a small site it is often the
 * same person twenty minutes later.</p>
 */
export function ReceiveMaterialPage() {
  const { hasPermission } = useAuth();
  const [siteId, setSiteId] = useState('');
  const [storeId, setStoreId] = useState('');
  const [receiptDate, setReceiptDate] = useState(today());
  const [challanNumber, setChallanNumber] = useState('');
  const [invoiceNumber, setInvoiceNumber] = useState('');
  const [vehicleNumber, setVehicleNumber] = useState('');
  const [lines, setLines] = useState<LineDraft[]>([emptyLine()]);

  const materials = useMaterials();
  const units = useUnits();
  const create = useCreateReceipt();
  const pending = useReceipts(storeId || undefined, 'SUBMITTED');
  const verify = useVerifyReceipt();

  const canVerify = hasPermission('inventory:verify');

  const total = useMemo(
    () =>
      lines.reduce((sum, line) => {
        const quantity = Number(line.quantity);
        const rate = Number(line.rate);
        return sum + (Number.isFinite(quantity) && Number.isFinite(rate) ? quantity * rate : 0);
      }, 0),
    [lines],
  );

  const complete = lines.filter(
    (line) => line.materialId && line.unitId && Number(line.quantity) > 0 && line.rate !== '',
  );

  const updateLine = (key: string, patch: Partial<LineDraft>) => {
    setLines((current) =>
      current.map((line) => (line.key === key ? { ...line, ...patch } : line)),
    );
  };

  /**
   * Choosing a material preselects its base unit. That is the right answer most of the
   * time — cement comes in bags and is stocked in bags — and the picker stays open for the
   * delivery that arrives in tonnes.
   */
  const chooseMaterial = (key: string, materialId: string) => {
    const material = materials.data?.find((m) => m.id === materialId);
    updateLine(key, { materialId, unitId: material?.baseUnitId ?? '' });
  };

  const submit = () => {
    create.mutate(
      {
        id: crypto.randomUUID(),
        storeId,
        receiptDate,
        challanNumber: challanNumber || undefined,
        invoiceNumber: invoiceNumber || undefined,
        vehicleNumber: vehicleNumber || undefined,
        lines: complete.map((line) => ({
          materialId: line.materialId,
          unitId: line.unitId,
          quantity: Number(line.quantity),
          rate: Number(line.rate),
        })),
      },
      {
        onSuccess: () => {
          setLines([emptyLine()]);
          setChallanNumber('');
          setInvoiceNumber('');
          setVehicleNumber('');
        },
      },
    );
  };

  return (
    <Stack spacing={2}>
      <Typography variant="h1">Receive material</Typography>

      <StorePicker
        siteId={siteId}
        storeId={storeId}
        date={receiptDate}
        onSiteChange={setSiteId}
        onStoreChange={setStoreId}
        onDateChange={setReceiptDate}
        dateLabel="Received on"
      />

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField
          label="Challan number"
          value={challanNumber}
          onChange={(e) => setChallanNumber(e.target.value)}
        />
        <TextField
          label="Invoice number"
          value={invoiceNumber}
          onChange={(e) => setInvoiceNumber(e.target.value)}
          helperText="Leave blank for a cash purchase"
        />
        <TextField
          label="Vehicle"
          value={vehicleNumber}
          onChange={(e) => setVehicleNumber(e.target.value)}
        />
      </Stack>

      <ReferenceNotice query={materials} what="materials" />
      <ReferenceNotice query={units} what="units" />

      <Stack spacing={1}>
        {lines.map((line) => (
          <Paper key={line.key} elevation={0} sx={{ p: 1.5, border: 1, borderColor: 'divider' }}>
            <Stack
              direction={{ xs: 'column', md: 'row' }}
              spacing={1.5}
              alignItems={{ md: 'center' }}
            >
              <TextField
                select
                label="Material"
                value={line.materialId}
                onChange={(e) => chooseMaterial(line.key, e.target.value)}
                sx={{ flexGrow: 1, minWidth: 200 }}
              >
                {(materials.data ?? []).map((material) => (
                  <MenuItem key={material.id} value={material.id}>
                    {material.name}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                select
                label="Unit"
                value={line.unitId}
                onChange={(e) => updateLine(line.key, { unitId: e.target.value })}
                sx={{ minWidth: 110 }}
              >
                {(units.data ?? []).map((unit) => (
                  <MenuItem key={unit.id} value={unit.id}>
                    {unit.code}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                label="Quantity"
                type="number"
                inputMode="decimal"
                value={line.quantity}
                onChange={(e) => updateLine(line.key, { quantity: e.target.value })}
                sx={{ minWidth: 120 }}
              />
              <TextField
                label="Rate"
                type="number"
                inputMode="decimal"
                value={line.rate ?? ''}
                onChange={(e) => updateLine(line.key, { rate: e.target.value })}
                helperText="Per unit above, before tax"
                sx={{ minWidth: 140 }}
              />
              <IconButton
                aria-label="Remove line"
                onClick={() =>
                  setLines((current) =>
                    current.length === 1
                      ? [emptyLine()]
                      : current.filter((l) => l.key !== line.key),
                  )
                }
                sx={{ width: 48, height: 48 }}
              >
                <DeleteOutlineIcon />
              </IconButton>
            </Stack>
          </Paper>
        ))}
      </Stack>

      <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap">
        <Button
          startIcon={<AddIcon />}
          onClick={() => setLines((current) => [...current, emptyLine()])}
          sx={{ minHeight: 48 }}
        >
          Add material
        </Button>
        <Typography color="text.secondary">Value before tax: {formatAmount(total)}</Typography>
      </Stack>

      {create.isError && <Alert severity="error">{apiErrorDetail(create.error)}</Alert>}
      {create.isSuccess && (
        <Alert severity="success">
          Booked {create.data.grnNumber}. It reaches the store once an engineer checks it
          against the challan.
        </Alert>
      )}

      <Stack direction="row" spacing={2}>
        <Button
          variant="contained"
          color="secondary"
          disabled={!storeId || complete.length === 0 || create.isPending}
          onClick={submit}
          sx={{ minHeight: 48 }}
        >
          {create.isPending ? 'Saving…' : `Book ${complete.length} material(s)`}
        </Button>
      </Stack>

      <Divider sx={{ pt: 2 }} />

      <Typography variant="h2" sx={{ fontSize: '1.25rem' }}>
        Waiting to be checked
      </Typography>
      {pending.isError && <Alert severity="error">{apiErrorDetail(pending.error)}</Alert>}
      {pending.data?.content.length === 0 && (
        <Typography color="text.secondary">Nothing is waiting at this store.</Typography>
      )}
      {verify.isError && <Alert severity="error">{apiErrorDetail(verify.error)}</Alert>}

      <Stack spacing={1}>
        {(pending.data?.content ?? []).map((receipt) => (
          <Paper key={receipt.id} elevation={0} sx={{ p: 1.5, border: 1, borderColor: 'divider' }}>
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1.5}
              justifyContent="space-between"
              alignItems={{ sm: 'center' }}
            >
              <div>
                <Stack direction="row" spacing={1} alignItems="center">
                  <Typography fontWeight={600}>{receipt.grnNumber}</Typography>
                  <StatusChip status="SUBMITTED" />
                </Stack>
                <Typography variant="body2" color="text.secondary">
                  {receipt.receiptDate}
                  {receipt.challanNumber && <> · challan {receipt.challanNumber}</>}
                  {' · '}
                  {formatAmount(receipt.totalAmount)}
                </Typography>
                {receipt.lines.map((line) => (
                  <Typography key={line.id} variant="body2" color="text.secondary">
                    {line.materialName} —{' '}
                    {formatQuantity(line.quantity, line.unitCode)} @ {formatAmount(line.rate)}
                  </Typography>
                ))}
              </div>
              {canVerify && (
                <Stack direction="row" spacing={1}>
                  <Button
                    variant="contained"
                    color="secondary"
                    disabled={verify.isPending}
                    onClick={() => verify.mutate({ id: receipt.id, action: 'VERIFY' })}
                    sx={{ minHeight: 48 }}
                  >
                    Matches challan
                  </Button>
                  <Button
                    variant="outlined"
                    disabled={verify.isPending}
                    onClick={() => verify.mutate({ id: receipt.id, action: 'REJECT' })}
                    sx={{ minHeight: 48 }}
                  >
                    Reject
                  </Button>
                </Stack>
              )}
            </Stack>
          </Paper>
        ))}
      </Stack>
    </Stack>
  );
}
