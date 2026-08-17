import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { useMaterials, useRaiseStockCorrection, useUnits } from './api';

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * The storekeeper saying what the shed actually holds.
 *
 * <p>Two boxes rather than a signed number, because a minus sign typed into a quantity is the
 * mistake this screen is trying to catch. He says <em>short</em> or <em>over</em> and how
 * much; the sign is assembled on the way out.</p>
 *
 * <p>The reason is required and the helper text says why: this is a request to move a balance
 * with no document behind it, and the man asking is the one best placed to make a loss
 * unreadable. "Two bags short, counted twice against challan 4412" is a sentence the office
 * can act on. "Correction" is not.</p>
 */
export function RaiseCorrectionDialog({
  open,
  storeId,
  siteId,
  materialId: prefilled,
  date,
  onClose,
}: {
  open: boolean;
  storeId: string;
  siteId: string;
  materialId?: string;
  /** The day the difference belongs to. The report's day when this is opened from one. */
  date?: string;
  onClose: () => void;
}) {
  const materials = useMaterials();
  const units = useUnits();
  const raise = useRaiseStockCorrection();

  const [materialId, setMaterialId] = useState(prefilled ?? '');
  const [unitId, setUnitId] = useState('');
  const [direction, setDirection] = useState<'SHORT' | 'OVER'>('SHORT');
  const [quantity, setQuantity] = useState('');
  const [correctionDate, setCorrectionDate] = useState(date ?? today);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);

  // Re-opened for a different row, or opened fresh. Keeping the last count in the boxes is
  // how somebody reports Tuesday's difference against Wednesday's material.
  useEffect(() => {
    if (open) {
      setMaterialId(prefilled ?? '');
      setUnitId('');
      setDirection('SHORT');
      setQuantity('');
      setCorrectionDate(date ?? today());
      setReason('');
      setError(null);
    }
  }, [open, prefilled, date]);

  const material = (materials.data ?? []).find((row) => row.id === materialId);

  /* Only the units this material has a conversion for. The server refuses the rest, and
     offering steel in cubic metres to find that out is a worse answer than not offering it. */
  const unitChoices = (units.data ?? []).filter((unit) =>
    material
      ? new Set([material.baseUnitId, ...(material.altUnitIds ?? [])]).has(unit.id)
      : false,
  );

  // Picking a material preselects what it is stocked in, which is what he counted in nearly
  // every time — cement comes in bags and the shed is counted in bags.
  useEffect(() => {
    if (material && !unitChoices.some((unit) => unit.id === unitId)) {
      setUnitId(material.baseUnitId);
    }
  }, [material, unitChoices, unitId]);

  const complete =
    Boolean(materialId) &&
    Boolean(unitId) &&
    Number(quantity) > 0 &&
    reason.trim().length > 0 &&
    Boolean(storeId);

  const submit = async () => {
    setError(null);
    try {
      await raise.mutateAsync({
        storeId,
        materialId,
        unitId,
        quantityDelta: direction === 'SHORT' ? -Number(quantity) : Number(quantity),
        correctionDate,
        reason: reason.trim(),
      });
      onClose();
    } catch (caught) {
      setError(apiErrorDetail(caught));
    }
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>What the store actually holds</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Alert severity="info">
            This moves nothing on its own. The office reads it and posts the correction, or
            says why not.
          </Alert>

          <TextField
            select
            label="Material"
            value={materialId}
            onChange={(e) => setMaterialId(e.target.value)}
            disabled={Boolean(prefilled)}
          >
            {(materials.data ?? []).map((row) => (
              <MenuItem key={row.id} value={row.id}>
                {row.name}
              </MenuItem>
            ))}
          </TextField>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <ToggleButtonGroup
              exclusive
              value={direction}
              onChange={(_event, value) => value !== null && setDirection(value)}
              sx={{ flexWrap: 'wrap' }}
            >
              <ToggleButton value="SHORT" sx={{ minHeight: 48, px: 2 }}>
                Less than the ledger says
              </ToggleButton>
              <ToggleButton value="OVER" sx={{ minHeight: 48, px: 2 }}>
                More
              </ToggleButton>
            </ToggleButtonGroup>
          </Stack>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Out by"
              type="number"
              inputMode="decimal"
              value={quantity}
              onChange={(e) => setQuantity(e.target.value)}
              sx={{ flexGrow: 1 }}
            />
            <TextField
              select
              label="In"
              value={unitId}
              onChange={(e) => setUnitId(e.target.value)}
              disabled={!material}
              sx={{ minWidth: 140 }}
            >
              {unitChoices.map((unit) => (
                <MenuItem key={unit.id} value={unit.id}>
                  {unit.code}
                </MenuItem>
              ))}
            </TextField>
          </Stack>

          {/*
            The day the difference belongs to, not the day it is being typed. A count made on
            the 31st and sent on the 2nd is March's, and the period lock is asked about this.
          */}
          <TextField
            label="Which day"
            type="date"
            value={correctionDate}
            onChange={(e) => setCorrectionDate(e.target.value)}
            InputLabelProps={{ shrink: true }}
            sx={{ maxWidth: 220 }}
          />

          <TextField
            label="What happened"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            multiline
            minRows={2}
            helperText="Counted against which challan, on whose say-so. This is what the office has to go on."
          />

          {error && <Alert severity="error">{error}</Alert>}
          {!siteId && (
            <Typography variant="body2" color="text.secondary">
              Pick a site and a store first.
            </Typography>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          color="secondary"
          disabled={!complete || raise.isPending}
          onClick={() => void submit()}
        >
          {raise.isPending ? 'Sending…' : 'Send it to the office'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
