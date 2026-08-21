import AddIcon from '@mui/icons-material/Add';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  CircularProgress,
  Divider,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useMemo, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import {
  useBoqItems,
  useCreateSheet,
  useSheet,
  useSignSheet,
  useUnits,
  useUpdateSheet,
} from './api';
import {
  lineContents,
  shapeColumns,
  shapeForUnit,
  sheetTotal,
  type MeasurementLineInput,
} from './types';

/*
  The entry grid, and it is deliberately the printed sheet with the same columns in the same
  order. He reads across the paper and types across the screen; nothing is translated in his
  head, which is the entire reason the paper was given a fixed format.

  The one number that earns its keep is `writtenTotal`. He worked it out by hand at the foot
  of the page; the grid adds the rows up itself. Two independent arrivals at one figure, and
  signing is blocked while they differ — here for the message, and by ck_sheet_signed_agrees
  underneath for the guarantee. It catches a typing slip, a skipped row, a transposed 5.8 for
  8.5, and his own arithmetic, and it costs one box.
*/

const EMPTY_LINE: MeasurementLineInput = {
  location: '',
  nos: 1,
  mult: 1,
  length: null,
  breadth: null,
  height: null,
  deduction: false,
};

const COLUMN_LABEL: Record<string, string> = {
  length: 'L',
  breadth: 'B',
  height: 'H',
};

export function MeasurementSheetPage() {
  const { id } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const projectId = searchParams.get('projectId') ?? '';

  const existing = useSheet(id);
  const boqItems = useBoqItems(projectId || existing.data?.projectId);
  const units = useUnits();
  const createSheet = useCreateSheet();
  const updateSheet = useUpdateSheet(id ?? '');
  const signSheet = useSignSheet();

  const [boqItemId, setBoqItemId] = useState('');
  const [sheetSerial, setSheetSerial] = useState('');
  const [measuredOn, setMeasuredOn] = useState(() => new Date().toISOString().slice(0, 10));
  const [locationNote, setLocationNote] = useState('');
  const [writtenTotal, setWrittenTotal] = useState('');
  const [unitWeight, setUnitWeight] = useState('');
  const [lines, setLines] = useState<MeasurementLineInput[]>([{ ...EMPTY_LINE }]);
  const [loadedId, setLoadedId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Fill the form once from a sheet opened for editing, without fighting the user's typing.
  if (existing.data && loadedId !== existing.data.id) {
    setLoadedId(existing.data.id);
    setBoqItemId(existing.data.boqItemId);
    setSheetSerial(existing.data.sheetSerial ?? '');
    setMeasuredOn(existing.data.measuredOn);
    setLocationNote(existing.data.locationNote ?? '');
    setWrittenTotal(existing.data.writtenTotal ?? '');
    setUnitWeight(existing.data.unitWeight ?? '');
    setLines(
      existing.data.lines.length > 0
        ? existing.data.lines.map((line) => ({
            location: line.location ?? '',
            nos: line.nos ?? 1,
            mult: line.mult ?? 1,
            length: line.length ?? null,
            breadth: line.breadth ?? null,
            height: line.height ?? null,
            deduction: line.deduction ?? false,
          }))
        : [{ ...EMPTY_LINE }],
    );
  }

  const selectedItem = boqItems.data?.find((item) => item.id === boqItemId);
  const unitCode = units.data?.find((unit) => unit.id === selectedItem?.unitId)?.code;
  const shape = shapeForUnit(unitCode);
  const columns = shapeColumns(shape);

  const computed = useMemo(() => sheetTotal(lines), [lines]);
  const written = writtenTotal.trim() === '' ? null : Number(writtenTotal);
  const totalsAgree = written === null || Math.abs(written - computed) <= 0.01;
  const claimed = unitWeight.trim() === '' ? computed : computed * Number(unitWeight);

  const isSigned = existing.data?.status === 'SIGNED';
  const isBilled = Boolean(existing.data?.raBillId);
  const readOnly = isSigned || isBilled;

  const setLine = (index: number, patch: Partial<MeasurementLineInput>) => {
    setLines((current) =>
      current.map((line, i) => (i === index ? { ...line, ...patch } : line)),
    );
  };

  const numeric = (value: string): number | null => {
    if (value.trim() === '') return null;
    const parsed = Number(value);
    return Number.isNaN(parsed) ? null : parsed;
  };

  const save = async (thenSign: boolean) => {
    setError(null);
    const payload = {
      sheetSerial: sheetSerial.trim() || null,
      measuredOn,
      locationNote: locationNote.trim() || null,
      writtenTotal: written,
      unitWeight: numeric(unitWeight),
      lines,
    };
    try {
      const saved = existing.data
        ? await updateSheet.mutateAsync({ ...payload, version: existing.data.version })
        : await createSheet.mutateAsync({
            ...payload,
            projectId: projectId || '',
            boqItemId,
          });
      if (thenSign) {
        await signSheet.mutateAsync(saved.id);
      }
      navigate(`/billing/${saved.projectId}/sheets`);
    } catch (caught) {
      setError(apiErrorDetail(caught));
    }
  };

  if (id && existing.isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', p: 6 }}>
        <CircularProgress />
      </Box>
    );
  }

  return (
    <Stack spacing={2} sx={{ p: 2, pb: 12 }}>
      <Typography variant="h6">
        {existing.data ? `Sheet ${existing.data.sheetSerial ?? ''}`.trim() : 'New measurement sheet'}
      </Typography>

      {isBilled && (
        <Alert severity="info">
          This sheet has been paid on a bill. A correction is a new sheet with negative rows,
          dated when the error was found — not a change to what was signed.
        </Alert>
      )}
      {isSigned && !isBilled && (
        <Alert severity="info">
          Signed. A correction is a new sheet with negative rows, not a change to this one.
        </Alert>
      )}
      {error && <Alert severity="error">{error}</Alert>}

      <Paper sx={{ p: 2 }}>
        <Stack spacing={2}>
          <TextField
            select
            label="Item"
            value={boqItemId}
            onChange={(event) => setBoqItemId(event.target.value)}
            disabled={Boolean(existing.data)}
            fullWidth
            helperText={
              selectedItem
                ? `${selectedItem.description} · ${unitCode ?? ''}`
                : 'The contract line this page measures'
            }
          >
            {(boqItems.data ?? []).map((item) => (
              <MenuItem key={item.id} value={item.id}>
                {item.itemNumber} — {item.description.slice(0, 60)}
              </MenuItem>
            ))}
          </TextField>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Sheet no."
              value={sheetSerial}
              onChange={(event) => setSheetSerial(event.target.value)}
              disabled={readOnly}
              sx={{ flex: 1 }}
              helperText="Pre-printed on the page. Blank if typed with no paper."
            />
            <TextField
              label="Measured on"
              type="date"
              value={measuredOn}
              onChange={(event) => setMeasuredOn(event.target.value)}
              disabled={readOnly}
              InputLabelProps={{ shrink: true }}
              sx={{ flex: 1 }}
            />
          </Stack>

          <TextField
            label="Location note"
            value={locationNote}
            onChange={(event) => setLocationNote(event.target.value)}
            disabled={readOnly}
            fullWidth
          />
        </Stack>
      </Paper>

      <Paper sx={{ p: 2 }}>
        <Stack spacing={1}>
          <Stack direction="row" alignItems="center" spacing={1}>
            <Typography variant="subtitle2" sx={{ flex: 1 }}>
              Rows
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {shape === 'COUNT' ? 'Nos × count' : `Nos × count × ${columns.map((c) => COLUMN_LABEL[c]).join(' × ')}`}
            </Typography>
          </Stack>

          {lines.map((line, index) => (
            <Stack key={index} spacing={0.5} sx={{ py: 1 }}>
              <Stack direction="row" spacing={1} alignItems="center">
                <Typography variant="caption" sx={{ width: 18, color: 'text.secondary' }}>
                  {index + 1}
                </Typography>
                <TextField
                  size="small"
                  placeholder="Location"
                  value={line.location ?? ''}
                  onChange={(event) => setLine(index, { location: event.target.value })}
                  disabled={readOnly}
                  sx={{ flex: 1 }}
                />
                <IconButton
                  size="small"
                  aria-label="Copy row"
                  disabled={readOnly}
                  onClick={() =>
                    setLines((current) => [
                      ...current.slice(0, index + 1),
                      { ...current[index] },
                      ...current.slice(index + 1),
                    ])
                  }
                >
                  <ContentCopyIcon fontSize="small" />
                </IconButton>
                <IconButton
                  size="small"
                  aria-label="Remove row"
                  disabled={readOnly || lines.length === 1}
                  onClick={() => setLines((current) => current.filter((_, i) => i !== index))}
                >
                  <DeleteOutlineIcon fontSize="small" />
                </IconButton>
              </Stack>

              <Stack direction="row" spacing={1} alignItems="center" sx={{ pl: 3 }}>
                <TextField
                  size="small"
                  label="Nos"
                  type="number"
                  inputProps={{ inputMode: 'decimal' }}
                  value={line.nos ?? ''}
                  onChange={(event) => setLine(index, { nos: numeric(event.target.value) })}
                  disabled={readOnly}
                  sx={{ width: 74 }}
                />
                <TextField
                  size="small"
                  label="×"
                  type="number"
                  inputProps={{ inputMode: 'decimal' }}
                  value={line.mult ?? ''}
                  onChange={(event) => setLine(index, { mult: numeric(event.target.value) })}
                  disabled={readOnly}
                  sx={{ width: 66 }}
                />
                {columns.map((column) => (
                  <TextField
                    key={column}
                    size="small"
                    label={COLUMN_LABEL[column]}
                    type="number"
                    inputProps={{ inputMode: 'decimal', step: '0.01' }}
                    value={line[column] ?? ''}
                    onChange={(event) => setLine(index, { [column]: numeric(event.target.value) })}
                    disabled={readOnly}
                    sx={{ width: 84 }}
                  />
                ))}
                <Box sx={{ flex: 1 }} />
                <Typography
                  variant="body2"
                  sx={{ fontVariantNumeric: 'tabular-nums', minWidth: 72, textAlign: 'right' }}
                  color={line.deduction ? 'error.main' : 'text.primary'}
                >
                  {lineContents(line).toFixed(2)}
                </Typography>
              </Stack>

              <Stack direction="row" alignItems="center" sx={{ pl: 3 }}>
                <Checkbox
                  size="small"
                  checked={Boolean(line.deduction)}
                  disabled={readOnly}
                  onChange={(event) => setLine(index, { deduction: event.target.checked })}
                />
                <Typography variant="caption" color="text.secondary">
                  Deduction (an opening taken out)
                </Typography>
              </Stack>
              <Divider />
            </Stack>
          ))}

          <Button
            startIcon={<AddIcon />}
            disabled={readOnly}
            onClick={() => setLines((current) => [...current, { ...EMPTY_LINE }])}
          >
            Add row
          </Button>
        </Stack>
      </Paper>

      <Paper sx={{ p: 2 }}>
        <Stack spacing={2}>
          <TextField
            label="Unit weight (kg/m)"
            type="number"
            inputProps={{ inputMode: 'decimal', step: '0.001' }}
            value={unitWeight}
            onChange={(event) => setUnitWeight(event.target.value)}
            disabled={readOnly}
            helperText="Sections only. The rows stay a length; the claim is that length × kg/m."
            fullWidth
          />

          <Stack direction="row" alignItems="center" spacing={2}>
            <TextField
              label="Total written on the sheet"
              type="number"
              inputProps={{ inputMode: 'decimal', step: '0.01' }}
              value={writtenTotal}
              onChange={(event) => setWrittenTotal(event.target.value)}
              disabled={readOnly}
              sx={{ flex: 1 }}
            />
            <Stack sx={{ minWidth: 140 }}>
              <Typography variant="caption" color="text.secondary">
                Rows come to
              </Typography>
              <Typography variant="h6" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                {computed.toFixed(2)}
              </Typography>
            </Stack>
          </Stack>

          {written !== null && !totalsAgree && (
            <Alert severity="warning">
              The sheet says {written.toFixed(2)} but the rows come to {computed.toFixed(2)} — a
              difference of {Math.abs(written - computed).toFixed(2)}. One of the rows is wrong,
              or the total is. It cannot be signed until they agree.
            </Alert>
          )}
          {written !== null && totalsAgree && (
            <Alert severity="success">Totals agree.</Alert>
          )}
          {unitWeight.trim() !== '' && (
            <Typography variant="body2" color="text.secondary">
              Claims {claimed.toFixed(4)} against the contract.
            </Typography>
          )}
        </Stack>
      </Paper>

      {!readOnly && (
        <Stack direction="row" spacing={2}>
          <Button
            variant="outlined"
            fullWidth
            disabled={!boqItemId || createSheet.isPending || updateSheet.isPending}
            onClick={() => void save(false)}
          >
            Save draft
          </Button>
          <Button
            variant="contained"
            fullWidth
            disabled={
              !boqItemId ||
              !totalsAgree ||
              written === null ||
              createSheet.isPending ||
              updateSheet.isPending ||
              signSheet.isPending
            }
            onClick={() => void save(true)}
          >
            Save &amp; sign
          </Button>
        </Stack>
      )}
    </Stack>
  );
}
