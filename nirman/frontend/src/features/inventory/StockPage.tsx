import {
  Alert,
  Chip,
  CircularProgress,
  Drawer,
  FormControlLabel,
  IconButton,
  Paper,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import { useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount, formatQuantity } from '../../shared/formatters';
import { useLedger, useStock } from './api';
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

  const stock = useStock(storeId || undefined, lowOnly);

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
        <Paper elevation={0} sx={{ border: 1, borderColor: 'divider', overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Material</TableCell>
                <TableCell align="right">In stock</TableCell>
                <TableCell align="right">Coming</TableCell>
                <TableCell align="right">Average rate</TableCell>
                <TableCell align="right">Value</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {stock.data.rows.map((row) => (
                <TableRow
                  key={`${row.storeId}-${row.materialId}`}
                  hover
                  onClick={() => setOpenRow(row)}
                  sx={{ cursor: 'pointer' }}
                >
                  <TableCell>
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
                  </TableCell>
                  <TableCell align="right">
                    {formatQuantity(row.quantityBase, row.baseUnitCode)}
                  </TableCell>
                  {/*
                    In transit is deliberately its own column rather than folded into the
                    quantity: material on a lorry cannot be issued, and a storekeeper who
                    sees it in the stock figure will promise it to somebody.
                  */}
                  <TableCell align="right">
                    {row.inTransitQtyBase > 0
                      ? formatQuantity(row.inTransitQtyBase, row.baseUnitCode)
                      : '—'}
                  </TableCell>
                  <TableCell align="right">{formatAmount(row.movingAvgRate)}</TableCell>
                  <TableCell align="right">{formatAmount(row.stockValue)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
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
  const ledger = useLedger(row.storeId, row.materialId);

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
