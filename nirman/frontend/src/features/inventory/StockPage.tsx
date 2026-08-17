import {
  Alert,
  Button,
  Chip,
  CircularProgress,
  Drawer,
  FormControlLabel,
  IconButton,
  Paper,
  Stack,
  Switch,
  Tab,
  Tabs,
  Typography,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import { useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { useAuth } from '../auth/AuthContext';
import { formatAmount, formatQuantity } from '../../shared/formatters';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { useLedger, useStock } from './api';
import { CorrectMaterialNameDialog } from './CorrectMaterialNameDialog';
import { EquipmentPanel } from './EquipmentPanel';
import { RaiseCorrectionDialog } from './RaiseCorrectionDialog';
import { StockCorrectionsPanel } from './StockCorrectionsPanel';
import { StorePicker } from './StorePicker';
import type { LedgerRow, StockRow, TxnType } from './types';

/** What each movement type is called on a screen a storekeeper reads. */
const MOVEMENT_LABEL: Record<TxnType, string> = {
  OPENING_STOCK: 'Opening stock',
  RECEIPT: 'Received',
  ISSUE: 'Issued',
  TRANSFER_OUT: 'Sent out',
  TRANSFER_IN: 'Transferred in',
  RETURN: 'Returned',
  WASTAGE: 'Wastage',
  DAMAGE: 'Damaged',
  ADJUSTMENT: 'Adjustment',
};

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * What the store holds, and how it got there.
 *
 * <p>The second half is the point. Nobody disputes a stock figure in the abstract; they
 * dispute it standing in the store looking at a different number, and what settles it is
 * the list of movements behind it. So every row opens its own ledger, and the running
 * balance is on each line of that ledger — the argument ends at the movement where the two
 * counts diverged.</p>
 */
export function StockPage() {
  const [siteId, setSiteId] = useState('');
  const [storeId, setStoreId] = useState('');
  const [asOf, setAsOf] = useState(today());
  const [lowOnly, setLowOnly] = useState(false);
  const [openRow, setOpenRow] = useState<StockRow | null>(null);
  const [tab, setTab] = useState<'MATERIAL' | 'EQUIPMENT' | 'CORRECTIONS'>('MATERIAL');

  const stock = useStock(storeId || undefined, lowOnly);

  const columns: RecordColumn<StockRow>[] = [
    {
      key: 'material',
      header: 'Material',
      card: 'title',
      cell: (row) => (
        <>
          <Typography fontWeight={600}>{row.materialName}</Typography>
          <Typography variant="body2" color="text.secondary">
            {row.materialCode}
            {row.low && (
              <>
                {' · '}
                <Typography component="span" variant="body2" color="warning.main">
                  below {formatQuantity(row.minStockLevel, row.baseUnitCode)}
                </Typography>
              </>
            )}
          </Typography>
        </>
      ),
    },
    {
      key: 'inStock',
      header: 'In stock',
      align: 'right',
      cell: (row) => formatQuantity(row.quantityBase, row.baseUnitCode),
    },
    {
      /*
        In transit is deliberately its own column rather than folded into the quantity:
        material on a lorry cannot be issued, and a storekeeper who sees it in the stock
        figure will promise it to somebody.
      */
      key: 'coming',
      header: 'Coming',
      align: 'right',
      cell: (row) =>
        row.inTransitQtyBase > 0 ? formatQuantity(row.inTransitQtyBase, row.baseUnitCode) : '—',
    },
    {
      key: 'rate',
      header: 'Average rate',
      align: 'right',
      cell: (row) => formatAmount(row.movingAvgRate),
    },
    {
      key: 'value',
      header: 'Value',
      align: 'right',
      cell: (row) => formatAmount(row.stockValue),
    },
  ];

  return (
    <Stack spacing={2}>
      <Typography variant="h1">Stock</Typography>

      <StorePicker
        siteId={siteId}
        storeId={storeId}
        date={asOf}
        onSiteChange={setSiteId}
        onStoreChange={setStoreId}
        onDateChange={setAsOf}
        dateLabel="As at"
      />

      {/*
        Two registers at one store, kept apart because they are two kinds of fact. Material
        is consumed and the ledger says what is left; plant is held, and the same mixer is
        here in March and in June. One list carrying both would have to answer "how much is
        left" about a machine.
      */}
      <Tabs
        value={tab}
        onChange={(_event, next: 'MATERIAL' | 'EQUIPMENT' | 'CORRECTIONS') => setTab(next)}
        aria-label="What is at this store"
      >
        <Tab value="MATERIAL" label="Material" />
        <Tab value="EQUIPMENT" label="Equipment" />
        {/*
          The third tab is not a third register. It is the queue of things the store says the
          first one has wrong — kept beside the balances rather than on an admin screen,
          because the man who can see the shed is the man standing on this page.
        */}
        <Tab value="CORRECTIONS" label="Differences" />
      </Tabs>

      {tab === 'EQUIPMENT' && <EquipmentPanel storeId={storeId} />}
      {tab === 'CORRECTIONS' && <StockCorrectionsPanel storeId={storeId} siteId={siteId} />}

      {tab === 'MATERIAL' && (
        <>
      <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap">
        <FormControlLabel
          control={
            <Switch checked={lowOnly} onChange={(e) => setLowOnly(e.target.checked)} />
          }
          label="Only what is running low"
        />
        {stock.data && (
          <Chip label={`Stock value ${formatAmount(stock.data.totalValue)}`} variant="outlined" />
        )}
      </Stack>

      {stock.isLoading && <CircularProgress />}
      {stock.isError && <Alert severity="error">{apiErrorDetail(stock.error)}</Alert>}
      {stock.data?.rows.length === 0 && (
        <Alert severity="info">
          {lowOnly
            ? 'Nothing is below its reorder level at this store.'
            : 'This store holds nothing yet.'}
        </Alert>
      )}

      {stock.data && stock.data.rows.length > 0 && (
        <RecordTable
          columns={columns}
          rows={stock.data.rows}
          rowKey={(row) => `${row.storeId}-${row.materialId}`}
          onRowClick={setOpenRow}
          ariaLabel="Stock"
        />
      )}
        </>
      )}

      <Drawer
        anchor="right"
        open={Boolean(openRow)}
        onClose={() => setOpenRow(null)}
        PaperProps={{ sx: { width: { xs: '100%', sm: 560 }, p: 2 } }}
      >
        {openRow && <LedgerPanel row={openRow} onClose={() => setOpenRow(null)} />}
      </Drawer>
    </Stack>
  );
}

function LedgerPanel({ row, onClose }: { row: StockRow; onClose: () => void }) {
  const { hasPermission } = useAuth();
  const ledger = useLedger(row.storeId, row.materialId);
  const [renaming, setRenaming] = useState(false);
  const [reporting, setReporting] = useState(false);

  return (
    <Stack spacing={2}>
      <Stack direction="row" alignItems="center" justifyContent="space-between">
        <div>
          <Typography variant="h2" sx={{ fontSize: '1.25rem' }}>
            {row.materialName}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {row.storeName} · {formatQuantity(row.quantityBase, row.baseUnitCode)} at{' '}
            {formatAmount(row.movingAvgRate)}
          </Typography>
        </div>
        <IconButton aria-label="Close" onClick={onClose} sx={{ width: 48, height: 48 }}>
          <CloseIcon />
        </IconButton>
      </Stack>

      {ledger.isLoading && <CircularProgress />}
      {ledger.isError && <Alert severity="error">{apiErrorDetail(ledger.error)}</Alert>}
      {ledger.data?.content.length === 0 && (
        <Typography color="text.secondary">No movements recorded.</Typography>
      )}

      <Stack spacing={1}>
        {(ledger.data?.content ?? []).map((movement) => (
          <LedgerLine key={movement.id} movement={movement} unit={row.baseUnitCode} />
        ))}
      </Stack>

      {/*
        The two things the man reading this list can say about it, and neither is an edit. A
        movement is never rewritten — the whole reason a balance is believed is that what is
        behind it can be read back — so a wrong figure is reported and the office posts the
        correction, and a wrong name is corrected on the material rather than on the rows.
      */}
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
        {hasPermission('inventory:correct') && (
          <Button size="small" variant="outlined" onClick={() => setReporting(true)}>
            Report a difference
          </Button>
        )}
        {hasPermission('masterdata:provisional') && (
          <Button size="small" onClick={() => setRenaming(true)}>
            Correct the name
          </Button>
        )}
      </Stack>

      <RaiseCorrectionDialog
        open={reporting}
        storeId={row.storeId}
        siteId={row.siteId ?? ''}
        materialId={row.materialId}
        onClose={() => setReporting(false)}
      />
      <CorrectMaterialNameDialog
        open={renaming}
        materialId={row.materialId}
        onClose={() => setRenaming(false)}
      />
    </Stack>
  );
}

function LedgerLine({ movement, unit }: { movement: LedgerRow; unit?: string | undefined }) {
  const inward = movement.direction > 0;
  return (
    <Paper elevation={0} sx={{ p: 1.5, border: 1, borderColor: 'divider' }}>
      <Stack direction="row" justifyContent="space-between" spacing={1}>
        <div>
          <Typography fontWeight={600}>{MOVEMENT_LABEL[movement.txnType]}</Typography>
          <Typography variant="body2" color="text.secondary">
            {movement.txnDate}
            {movement.reason && <> · {movement.reason}</>}
          </Typography>
        </div>
        <Stack alignItems="flex-end">
          <Typography fontWeight={600} color={inward ? 'success.main' : 'text.primary'}>
            {inward ? '+' : '−'}
            {formatQuantity(movement.quantityBase, unit)}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            balance {formatQuantity(movement.balanceAfter, unit)}
          </Typography>
        </Stack>
      </Stack>
    </Paper>
  );
}
