import {
  Alert,
  Button,
  Chip,
  CircularProgress,
  Stack,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatQuantity } from '../../shared/formatters';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { useAuth } from '../auth/AuthContext';
import { useDecideStockCorrection, useStockCorrections } from './api';
import { RaiseCorrectionDialog } from './RaiseCorrectionDialog';
import type { StockCorrection, StockCorrectionStatus } from './types';

/**
 * The corrections this store has asked for, and what became of them.
 *
 * <p>It exists because the two halves of putting a stock figure right sit with two different
 * people and always did. The storekeeper is the one who can see the shed; moving a balance is
 * the office's, because a role that can adjust a balance can hide a loss. Before this screen
 * the second fact simply cancelled the first — twelve bags booked against eleven delivered
 * stayed wrong until a physical count found it in another quarter — and the correction, when
 * it happened at all, happened by telephone.</p>
 *
 * <p>So the count is not the correction. It is the request for one, and the ledger is entered
 * through exactly the door it always was: accepting posts an ordinary signed adjustment, with
 * the period lock and the negative-stock refusal still in front of it.</p>
 */
export function StockCorrectionsPanel({
  storeId,
  siteId,
}: {
  storeId: string;
  siteId: string;
}) {
  const { hasPermission } = useAuth();
  const corrections = useStockCorrections(storeId || undefined);
  const decide = useDecideStockCorrection();

  const [raising, setRaising] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const canRaise = hasPermission('inventory:correct');
  const canDecide = hasPermission('inventory:adjust');

  const act = async (correction: StockCorrection, action: 'ACCEPT' | 'REJECT') => {
    setActionError(null);
    try {
      await decide.mutateAsync({ id: correction.id, action });
    } catch (error) {
      setActionError(apiErrorDetail(error));
    }
  };

  const columns: RecordColumn<StockCorrection>[] = [
    {
      key: 'material',
      header: 'Material',
      card: 'title',
      cell: (row) => (
        <>
          <Typography fontWeight={600}>{row.materialName}</Typography>
          <Typography variant="body2" color="text.secondary">
            {row.correctionDate} · {row.reason}
          </Typography>
        </>
      ),
    },
    {
      /*
        Signed, and shown as the direction rather than as a number with a minus in front of
        it. "2 BAG off" is what the man who counted the shed said; "-2" is what the ledger
        will do about it, and the office is reading this to decide whether the first is true.
      */
      key: 'delta',
      header: 'Out by',
      align: 'right',
      cell: (row) => (
        <Typography
          variant="body2"
          color={row.quantityDelta < 0 ? 'error.main' : 'success.main'}
        >
          {formatQuantity(Math.abs(row.quantityDelta), row.unitCode)}{' '}
          {row.quantityDelta < 0 ? 'short' : 'over'}
        </Typography>
      ),
    },
    {
      key: 'status',
      header: 'Answer',
      card: 'status',
      cell: (row) => <StatusMark status={row.status} remarks={row.decisionRemarks} />,
    },
  ];

  if (canDecide) {
    columns.push({
      key: 'actions',
      header: 'Actions',
      align: 'right',
      card: 'actions',
      cell: (row) =>
        row.status === 'PENDING' ? (
          <>
            <Button size="small" disabled={decide.isPending} onClick={() => void act(row, 'ACCEPT')}>
              Post it
            </Button>
            <Button
              size="small"
              color="error"
              disabled={decide.isPending}
              onClick={() => void act(row, 'REJECT')}
            >
              Not so
            </Button>
          </>
        ) : null,
    });
  }

  const rows = corrections.data ?? [];
  const waiting = rows.filter((row) => row.status === 'PENDING').length;

  return (
    <Stack spacing={2}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        justifyContent="space-between"
        alignItems={{ sm: 'center' }}
      >
        <Stack spacing={0.5}>
          <Typography color="text.secondary">
            What the store says the ledger has wrong. Nothing here has moved any stock — the
            office posts the adjustment, or says no.
          </Typography>
          {waiting > 0 && (
            <Typography variant="body2" color="text.secondary">
              {waiting} {waiting === 1 ? 'is' : 'are'} waiting for the office.
            </Typography>
          )}
        </Stack>
        {canRaise && (
          <Button
            variant="contained"
            color="secondary"
            onClick={() => setRaising(true)}
            disabled={!storeId}
            sx={{ minHeight: 48 }}
          >
            Report a difference
          </Button>
        )}
      </Stack>

      {actionError && (
        <Alert severity="error" onClose={() => setActionError(null)}>
          {actionError}
        </Alert>
      )}
      {corrections.isLoading && <CircularProgress />}
      {corrections.isError && (
        <Alert severity="error">{apiErrorDetail(corrections.error)}</Alert>
      )}

      {corrections.data && (
        <RecordTable
          columns={columns}
          rows={rows}
          rowKey={(row) => row.id}
          ariaLabel="Stock corrections"
          empty={
            <Typography color="text.secondary">
              Nothing has been reported as wrong at this store.
            </Typography>
          }
        />
      )}

      <RaiseCorrectionDialog
        open={raising}
        storeId={storeId}
        siteId={siteId}
        onClose={() => setRaising(false)}
      />
    </Stack>
  );
}

/**
 * Three states and three different things to say. "Pending" on its own reads as a delay; what
 * the storekeeper needs to know is that somebody else has to look at it, and what he needs
 * from a refusal is the reason — that is the whole answer to the count he made.
 */
function StatusMark({
  status,
  remarks,
}: {
  status: StockCorrectionStatus;
  remarks?: string | undefined;
}) {
  if (status === 'ACCEPTED') {
    return <Chip size="small" color="success" label="Posted to the ledger" />;
  }
  if (status === 'REJECTED') {
    return (
      <Stack spacing={0.5} alignItems="flex-start">
        <Chip size="small" variant="outlined" color="error" label="Office says no" />
        {remarks && (
          <Typography variant="caption" color="text.secondary">
            {remarks}
          </Typography>
        )}
      </Stack>
    );
  }
  return <Chip size="small" color="warning" variant="outlined" label="Waiting on the office" />;
}
