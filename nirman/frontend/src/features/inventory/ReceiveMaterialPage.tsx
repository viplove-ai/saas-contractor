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
import {
  useAddFieldMaterial,
  useCreateReceipt,
  useMaterials,
  usePriceReceipt,
  useReceipts,
  useUnits,
  useVerifyReceipt,
} from './api';
import { StorePicker } from './StorePicker';
import { MATERIAL_NOT_LISTED, type LineDraft, type Receipt, type Unit } from './types';

/**
 * Why the unit box is short. One choice is not a choice — the material is stocked that way
 * and has no conversion to anything else — and saying so is what stops a storekeeper hunting
 * for the tonnes he was expecting to find.
 */
function unitHint(choices: number): string {
  return choices <= 1
    ? 'How it is stocked. Add a conversion on the material to book it in another unit.'
    : 'As the challan reads. Converted on the way in.';
}

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
  const [namingError, setNamingError] = useState<string | null>(null);

  const materials = useMaterials();
  const units = useUnits();
  const create = useCreateReceipt();
  const nameMaterial = useAddFieldMaterial();
  const pending = useReceipts(storeId || undefined, 'SUBMITTED');

  const canVerify = hasPermission('inventory:verify');
  const canPrice = hasPermission('inventory:price');
  const canNameMaterial = hasPermission('masterdata:provisional');

  const total = useMemo(
    () =>
      lines.reduce((sum, line) => {
        const quantity = Number(line.quantity);
        const rate = Number(line.rate);
        return sum + (Number.isFinite(quantity) && Number.isFinite(rate) ? quantity * rate : 0);
      }, 0),
    [lines],
  );

  /** A line names a material either by picking one or by typing one. Both count. */
  const named = (line: LineDraft): boolean =>
    line.materialId === MATERIAL_NOT_LISTED
      ? (line.newName ?? '').trim().length > 0
      : Boolean(line.materialId);

  // A rate is not part of being complete. The storekeeper has a challan, which is a
  // delivery note and often carries no price at all; what makes his line finished is
  // knowing what arrived and how much of it.
  const complete = lines.filter(
    (line) =>
      named(line) &&
      line.unitId &&
      Number(line.quantity) > 0 &&
      (!canPrice || line.rate !== ''),
  );

  const updateLine = (key: string, patch: Partial<LineDraft>) => {
    setLines((current) =>
      current.map((line) => (line.key === key ? { ...line, ...patch } : line)),
    );
  };

  /**
   * The units a line may actually be booked in: the material's own, plus whatever it has a
   * conversion for. Every unit in the system was the old answer and it was the wrong one —
   * the server refuses a unit with no conversion, and it refuses it after the storekeeper
   * has typed the whole delivery.
   *
   * <p>A material the catalogue has never heard of has no base and no conversions, so the
   * whole list is offered: he is naming it, and the unit he names it in becomes its base.</p>
   */
  const unitsFor = (line: LineDraft): Unit[] => {
    const all = units.data ?? [];
    if (line.materialId === MATERIAL_NOT_LISTED || !line.materialId) {
      return all;
    }
    const material = materials.data?.find((m) => m.id === line.materialId);
    if (!material) {
      return all;
    }
    const allowed = new Set([material.baseUnitId, ...(material.altUnitIds ?? [])]);
    return all.filter((unit) => allowed.has(unit.id));
  };

  /**
   * Choosing a material preselects its base unit. That is the right answer most of the
   * time — cement comes in bags and is stocked in bags — and the picker stays open for the
   * delivery that arrives in tonnes.
   */
  const chooseMaterial = (key: string, materialId: string) => {
    if (materialId === MATERIAL_NOT_LISTED) {
      // No base unit to fall back on, so the unit is his to say — and the picker beside it
      // is already open.
      updateLine(key, { materialId, unitId: '' });
      return;
    }
    const material = materials.data?.find((m) => m.id === materialId);
    updateLine(key, { materialId, unitId: material?.baseUnitId ?? '', newName: undefined });
  };

  /**
   * Books the delivery, opening a material for anything he had to name himself.
   *
   * <p>The naming happens first and one line at a time, because a receipt line needs a
   * material id and there is no way to send a name instead. A name that fails to register
   * stops the receipt rather than dropping the line: a delivery booked with three of the four
   * things that arrived is worse than one not booked yet, because nobody re-counts a lorry
   * that has already been signed for.</p>
   */
  const submit = async () => {
    setNamingError(null);
    let resolved: {
      materialId: string;
      unitId: string;
      quantity: number;
      rate?: number;
    }[];
    try {
      resolved = await Promise.all(
        complete.map(async (line) => ({
          materialId:
            line.materialId === MATERIAL_NOT_LISTED
              ? (
                  await nameMaterial.mutateAsync({
                    name: (line.newName ?? '').trim(),
                    baseUnitId: line.unitId,
                  })
                ).id
              : line.materialId,
          unitId: line.unitId,
          quantity: Number(line.quantity),
          // Omitted, not zero: the server refuses a rate from anyone without the
          // permission, and zero would be a price somebody claimed.
          ...(canPrice ? { rate: Number(line.rate) } : {}),
        })),
      );
    } catch (error) {
      setNamingError(apiErrorDetail(error));
      return;
    }

    create.mutate(
      {
        id: crypto.randomUUID(),
        storeId,
        receiptDate,
        challanNumber: challanNumber || undefined,
        invoiceNumber: invoiceNumber || undefined,
        vehicleNumber: vehicleNumber || undefined,
        lines: resolved,
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
              <Stack spacing={1} sx={{ flexGrow: 1, minWidth: 200 }}>
                <TextField
                  select
                  label="Material"
                  value={line.materialId}
                  onChange={(e) => chooseMaterial(line.key, e.target.value)}
                >
                  {(materials.data ?? []).map((material) => (
                    <MenuItem key={material.id} value={material.id}>
                      {material.name}
                    </MenuItem>
                  ))}
                  {/*
                    Last, and only for somebody allowed to name one. The lorry does not wait
                    for the office to add a material, and a delivery that cannot be booked is
                    a delivery nobody ever counts.
                  */}
                  {canNameMaterial && (
                    <MenuItem value={MATERIAL_NOT_LISTED}>Not in the list…</MenuItem>
                  )}
                </TextField>
                {line.materialId === MATERIAL_NOT_LISTED && (
                  <TextField
                    label="What is it called"
                    value={line.newName ?? ''}
                    onChange={(e) => updateLine(line.key, { newName: e.target.value })}
                    helperText="As it reads on the challan. The office prices it later; pick the unit beside it."
                  />
                )}
              </Stack>
              <TextField
                select
                label="Unit"
                value={line.unitId}
                onChange={(e) => updateLine(line.key, { unitId: e.target.value })}
                helperText={unitHint(unitsFor(line).length)}
                sx={{ minWidth: 140 }}
              >
                {unitsFor(line).map((unit) => (
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
              {canPrice && (
                <TextField
                  label="Rate"
                  type="number"
                  inputMode="decimal"
                  value={line.rate ?? ''}
                  onChange={(e) => updateLine(line.key, { rate: e.target.value })}
                  helperText="Per unit above, before tax"
                  sx={{ minWidth: 140 }}
                />
              )}
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
        {canPrice ? (
          <Typography color="text.secondary">Value before tax: {formatAmount(total)}</Typography>
        ) : (
          <Typography color="text.secondary">
            The office prices this against the invoice.
          </Typography>
        )}
      </Stack>

      {namingError && <Alert severity="error">{namingError}</Alert>}
      {create.isError && <Alert severity="error">{apiErrorDetail(create.error)}</Alert>}
      {create.isSuccess && (
        <Alert severity="success">
          Booked {create.data.grnNumber}.{' '}
          {canPrice
            ? 'It reaches the store once it is checked against the challan.'
            : 'It reaches the store once the office prices it and an engineer checks it against the challan.'}
        </Alert>
      )}

      <Stack direction="row" spacing={2}>
        <Button
          variant="contained"
          color="secondary"
          disabled={
            !storeId || complete.length === 0 || create.isPending || nameMaterial.isPending
          }
          onClick={() => void submit()}
          sx={{ minHeight: 48 }}
        >
          {create.isPending || nameMaterial.isPending
            ? 'Saving…'
            : `Book ${complete.length} material(s)`}
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
      <Stack spacing={1}>
        {(pending.data?.content ?? []).map((receipt) => (
          <PendingReceipt
            key={receipt.id}
            receipt={receipt}
            canVerify={canVerify}
            canPrice={canPrice}
          />
        ))}
      </Stack>
    </Stack>
  );
}

/**
 * One delivery waiting to be checked, and priced on the way.
 *
 * <p>Pricing lives here rather than on the entry form above because it is the office's
 * answer and the form is the gate's. What the storekeeper had in his hand was a challan,
 * which frequently carries no price; the invoice reaches the office days later, and until
 * somebody has read it the rate is a guess that would go straight into the moving average.</p>
 *
 * <p>Two calls on one button, in order: the rates go up first, then the verification that
 * posts them to the ledger. They are two acts on the server — an accountant may price a week
 * of receipts without deciding whether any of them matched what turned up — but at a small
 * site it is one person with the invoice in front of him, so the screen makes it one press.</p>
 */
function PendingReceipt({
  receipt,
  canVerify,
  canPrice,
}: {
  receipt: Receipt;
  canVerify: boolean;
  canPrice: boolean;
}) {
  const price = usePriceReceipt();
  const verify = useVerifyReceipt();
  const [rates, setRates] = useState<Record<string, string>>({});

  const unpriced = receipt.lines.filter((line) => line.rate === undefined);
  /** Every line has either a rate on file or one typed here, so verification will not bounce. */
  const readyToVerify = unpriced.every((line) => Number(rates[line.id] ?? '') >= 0 && rates[line.id] !== undefined && rates[line.id] !== '');
  const busy = price.isPending || verify.isPending;

  const priceThenVerify = async () => {
    const typed = Object.entries(rates)
      .filter(([, value]) => value !== '')
      .map(([lineId, value]) => ({ lineId, rate: Number(value) }));
    if (typed.length > 0) {
      await price.mutateAsync({ id: receipt.id, lines: typed });
    }
    await verify.mutateAsync({ id: receipt.id, action: 'VERIFY' });
  };

  return (
    <Paper elevation={0} sx={{ p: 1.5, border: 1, borderColor: 'divider' }}>
      <Stack spacing={1.5}>
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
              {unpriced.length === 0 && <> · {formatAmount(receipt.totalAmount)}</>}
            </Typography>
            {receipt.lines.map((line) => (
              <Typography key={line.id} variant="body2" color="text.secondary">
                {line.materialName} — {formatQuantity(line.quantity, line.unitCode)}
                {line.rate === undefined ? ' · not priced yet' : ` @ ${formatAmount(line.rate)}`}
              </Typography>
            ))}
          </div>
          {canVerify && (
            <Stack direction="row" spacing={1}>
              <Button
                variant="contained"
                color="secondary"
                disabled={busy || !readyToVerify}
                onClick={() => void priceThenVerify()}
                sx={{ minHeight: 48 }}
              >
                Matches challan
              </Button>
              <Button
                variant="outlined"
                disabled={busy}
                onClick={() => verify.mutate({ id: receipt.id, action: 'REJECT' })}
                sx={{ minHeight: 48 }}
              >
                Reject
              </Button>
            </Stack>
          )}
        </Stack>

        {/*
          The rates the office is putting on it. Only shown to somebody who may set one, and
          only for the lines still waiting: a receipt already priced needs nothing here, and
          a rate already posted is corrected with a stock adjustment, not by retyping it.
        */}
        {canPrice && unpriced.length > 0 && (
          <Stack spacing={1}>
            <Typography variant="body2" color="text.secondary">
              Rate against the invoice, before tax:
            </Typography>
            {unpriced.map((line) => (
              <TextField
                key={line.id}
                label={line.materialName ?? 'Rate'}
                type="number"
                inputMode="decimal"
                size="small"
                value={rates[line.id] ?? ''}
                onChange={(e) =>
                  setRates((current) => ({ ...current, [line.id]: e.target.value }))
                }
                helperText={`Per ${line.unitCode ?? 'unit'}`}
                sx={{ maxWidth: 240 }}
              />
            ))}
          </Stack>
        )}

        {!canPrice && unpriced.length > 0 && (
          <Typography variant="body2" color="text.secondary">
            Waiting on the office to price it. It cannot reach the store until then.
          </Typography>
        )}

        {price.isError && <Alert severity="error">{apiErrorDetail(price.error)}</Alert>}
        {verify.isError && <Alert severity="error">{apiErrorDetail(verify.error)}</Alert>}
      </Stack>
    </Paper>
  );
}
