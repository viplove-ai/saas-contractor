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
import { StatusChip } from '../../shared/StatusChip';
import { useAuth } from '../auth/AuthContext';
import {
  useApproveIssue,
  useBoqItems,
  useCreateIssue,
  useIssues,
  useStock,
  useUnits,
} from './api';
import { StorePicker } from './StorePicker';
import type { LineDraft, StockRow } from './types';

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function emptyLine(): LineDraft {
  return { key: crypto.randomUUID(), materialId: '', unitId: '', quantity: '' };
}

/**
 * Issuing material to the work face.
 *
 * <p>This is the screen the whole consumption half of the system rests on, and the one the
 * field has never filled in: the Kausani register holds thirty-two deliveries and not a
 * single issue. Receipts get recorded because a vendor wants paying; nobody chases a
 * storekeeper for an issue slip. So the screen has to be worth ten seconds or the
 * consumption half of every dashboard stays empty.</p>
 *
 * <p>Three things follow from that. The material list is the store's own stock, so a
 * supervisor picks from what is in front of him and sees how much is there while he types.
 * The unit is the base unit and is not asked for — a slip is written in the unit the store
 * counts in. And the work item is chosen once at the top for the whole slip, because a man
 * carrying cement to a column is doing one job, not four.</p>
 */
export function IssueMaterialPage() {
  const { hasPermission } = useAuth();
  const [siteId, setSiteId] = useState('');
  const [storeId, setStoreId] = useState('');
  const [issueDate, setIssueDate] = useState(today());
  const [boqItemId, setBoqItemId] = useState('');
  const [purpose, setPurpose] = useState('');
  const [issuedToName, setIssuedToName] = useState('');
  const [lines, setLines] = useState<LineDraft[]>([emptyLine()]);

  const stock = useStock(storeId || undefined);
  const units = useUnits();
  const boqItems = useBoqItems(siteId || undefined);
  const create = useCreateIssue();
  const pending = useIssues(storeId || undefined, 'SUBMITTED');
  const approve = useApproveIssue();

  const canApprove = hasPermission('inventory:verify');

  /** Only what the store actually holds. Issuing what is not there is not an option to offer. */
  const available = useMemo(
    () => (stock.data?.rows ?? []).filter((row) => row.quantityBase > 0),
    [stock.data],
  );

  const byMaterial = useMemo(() => {
    const map = new Map<string, StockRow>();
    available.forEach((row) => map.set(row.materialId, row));
    return map;
  }, [available]);

  const unitIdFor = (baseUnitCode: string | undefined) =>
    units.data?.find((unit) => unit.code === baseUnitCode)?.id ?? '';

  const updateLine = (key: string, patch: Partial<LineDraft>) => {
    setLines((current) =>
      current.map((line) => (line.key === key ? { ...line, ...patch } : line)),
    );
  };

  const chooseMaterial = (key: string, materialId: string) => {
    const row = byMaterial.get(materialId);
    updateLine(key, { materialId, unitId: unitIdFor(row?.baseUnitCode) });
  };

  /** A line the store cannot cover. Flagged as it is typed rather than on submit. */
  const overdrawn = (line: LineDraft): boolean => {
    const row = byMaterial.get(line.materialId);
    return Boolean(row) && Number(line.quantity) > (row?.quantityBase ?? 0);
  };

  const complete = lines.filter(
    (line) => line.materialId && line.unitId && Number(line.quantity) > 0 && !overdrawn(line),
  );
  const chargeable = Boolean(boqItemId) || purpose.trim().length > 0;

  const submit = () => {
    create.mutate(
      {
        id: crypto.randomUUID(),
        storeId,
        issueDate,
        boqItemId: boqItemId || undefined,
        purpose: purpose || undefined,
        issuedToName: issuedToName || undefined,
        lines: complete.map((line) => ({
          materialId: line.materialId,
          unitId: line.unitId,
          quantity: Number(line.quantity),
        })),
      },
      {
        onSuccess: () => {
          setLines([emptyLine()]);
          setPurpose('');
        },
      },
    );
  };

  return (
    <Stack spacing={2}>
      <Typography variant="h1">Issue material</Typography>

      <StorePicker
        siteId={siteId}
        storeId={storeId}
        date={issueDate}
        onSiteChange={setSiteId}
        onStoreChange={setStoreId}
        onDateChange={setIssueDate}
        dateLabel="Issued on"
      />

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField
          select
          label="For which work"
          value={boqItemId}
          onChange={(e) => setBoqItemId(e.target.value)}
          sx={{ flexGrow: 1, minWidth: 220 }}
          helperText="Charging the material to a work item is what makes cost per item answerable"
        >
          <MenuItem value="">Not against a work item</MenuItem>
          {(boqItems.data ?? []).map((item) => (
            <MenuItem key={item.id} value={item.id}>
              {item.itemNumber} — {item.description}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          label="Issued to"
          value={issuedToName}
          onChange={(e) => setIssuedToName(e.target.value)}
          sx={{ minWidth: 180 }}
        />
      </Stack>

      {!boqItemId && (
        <TextField
          label="What is it for"
          value={purpose}
          onChange={(e) => setPurpose(e.target.value)}
          helperText="Needed when no work item is chosen — material that can be charged to nothing cannot be costed"
        />
      )}

      {stock.data && available.length === 0 && (
        <Alert severity="info">
          This store holds nothing yet. Book a delivery first, or have the opening stock
          declared.
        </Alert>
      )}

      <Stack spacing={1}>
        {lines.map((line) => {
          const row = byMaterial.get(line.materialId);
          const short = overdrawn(line);
          return (
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
                  sx={{ flexGrow: 1, minWidth: 220 }}
                >
                  {available.map((option) => (
                    <MenuItem key={option.materialId} value={option.materialId}>
                      {option.materialName} —{' '}
                      {formatQuantity(option.quantityBase, option.baseUnitCode)} in stock
                    </MenuItem>
                  ))}
                </TextField>
                <TextField
                  label={`Quantity${row?.baseUnitCode ? ` (${row.baseUnitCode})` : ''}`}
                  type="number"
                  inputMode="decimal"
                  value={line.quantity}
                  onChange={(e) => updateLine(line.key, { quantity: e.target.value })}
                  error={short}
                  helperText={
                    short
                      ? `Only ${formatQuantity(row?.quantityBase, row?.baseUnitCode)} in this store`
                      : undefined
                  }
                  sx={{ minWidth: 160 }}
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
          );
        })}
      </Stack>

      <Button
        startIcon={<AddIcon />}
        onClick={() => setLines((current) => [...current, emptyLine()])}
        sx={{ minHeight: 48, alignSelf: 'flex-start' }}
      >
        Add material
      </Button>

      {create.isError && <Alert severity="error">{apiErrorDetail(create.error)}</Alert>}
      {create.isSuccess && (
        <Alert severity="success">
          Recorded {create.data.issueNumber}. The stock moves once it is approved.
        </Alert>
      )}

      <Stack direction="row" spacing={2}>
        <Button
          variant="contained"
          color="secondary"
          disabled={!storeId || complete.length === 0 || !chargeable || create.isPending}
          onClick={submit}
          sx={{ minHeight: 48 }}
        >
          {create.isPending ? 'Saving…' : `Issue ${complete.length} material(s)`}
        </Button>
      </Stack>

      <Divider sx={{ pt: 2 }} />

      <Typography variant="h2" sx={{ fontSize: '1.25rem' }}>
        Waiting for approval
      </Typography>
      {pending.data?.content.length === 0 && (
        <Typography color="text.secondary">Nothing is waiting at this store.</Typography>
      )}
      {approve.isError && <Alert severity="error">{apiErrorDetail(approve.error)}</Alert>}

      <Stack spacing={1}>
        {(pending.data?.content ?? []).map((issue) => (
          <Paper key={issue.id} elevation={0} sx={{ p: 1.5, border: 1, borderColor: 'divider' }}>
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1.5}
              justifyContent="space-between"
              alignItems={{ sm: 'center' }}
            >
              <div>
                <Stack direction="row" spacing={1} alignItems="center">
                  <Typography fontWeight={600}>{issue.issueNumber}</Typography>
                  <StatusChip status="SUBMITTED" />
                </Stack>
                <Typography variant="body2" color="text.secondary">
                  {issue.issueDate}
                  {issue.purpose && <> · {issue.purpose}</>}
                </Typography>
                {issue.lines.map((line) => (
                  <Typography key={line.id} variant="body2" color="text.secondary">
                    {line.materialName} —{' '}
                    {formatQuantity(line.quantityBase, line.baseUnitCode)}
                    {line.value != null && <> · {formatAmount(line.value)}</>}
                  </Typography>
                ))}
              </div>
              {canApprove && (
                <Stack direction="row" spacing={1}>
                  <Button
                    variant="contained"
                    color="secondary"
                    disabled={approve.isPending}
                    onClick={() => approve.mutate({ id: issue.id, action: 'APPROVE' })}
                    sx={{ minHeight: 48 }}
                  >
                    Approve
                  </Button>
                  <Button
                    variant="outlined"
                    disabled={approve.isPending}
                    onClick={() => approve.mutate({ id: issue.id, action: 'REJECT' })}
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
