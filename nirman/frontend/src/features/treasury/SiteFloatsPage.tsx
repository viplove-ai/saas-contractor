import AddIcon from '@mui/icons-material/Add';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Collapse,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { tokens } from '../../app/theme';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { useSelectedSite } from '../../shared/siteSelection';
import { useAuth } from '../auth/AuthContext';
import { IssueFloatDialog } from './IssueFloatDialog';
import {
  useFloatBalances,
  useIssueFloat,
  useSiteFloats,
  useSites,
  type IssueFloatInput,
} from './api';
import { TreasuryTabs } from './TreasuryTabs';
import type { FloatBalance, SiteFloat } from './types';

/**
 * Cash the company has handed to the people standing on its sites.
 *
 * <p>The treasury's fourth question, and the one it could not answer. The dashboard says what
 * each contract has locked up with a department; the FDR register says what the bank holds. This
 * says where the rest of the cash is — some thousands of rupees in the pockets of a handful of
 * supervisors and engineers, which until now existed only in the office's memory and a notebook,
 * because the float register has been in the database since V1 with no screen onto it.</p>
 *
 * <p><b>The balance carries a sign, and that is most of the value here.</b> Positive is money he
 * is still carrying. Negative is a man who bought at the gate with more than he was given — a
 * lorry does not wait while an accountant is telephoned — and the company owes him that much.
 * Refusing to record it never stopped it happening; it only kept it off the one screen where
 * somebody would have seen it.</p>
 *
 * <p>Read by anybody who can read expenses, and written by whoever holds {@code advance:issue},
 * which is the accountant's and the administrator's. A supervisor cannot hand himself petty
 * cash, and that separation is the whole control.</p>
 */
export function SiteFloatsPage() {
  const { hasPermission } = useAuth();
  const mayRead = hasPermission('expense:read');
  const mayIssue = hasPermission('advance:issue');

  const sites = useSites();
  const [siteId, setSiteId] = useSelectedSite(sites.data);
  const balances = useFloatBalances(mayRead ? siteId || undefined : undefined);
  const floats = useSiteFloats(mayRead ? siteId || undefined : undefined);
  const issue = useIssueFloat();

  const [issuing, setIssuing] = useState(false);
  const [openHolder, setOpenHolder] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (!mayRead) {
    return (
      <Stack spacing={2}>
        <TreasuryTabs />
        <Alert severity="info">
          Petty cash held at the sites is the office&apos;s register. Ask an administrator if you
          need to see it.
        </Alert>
      </Stack>
    );
  }

  const rows = balances.data ?? [];
  /*
    The two totals are kept apart rather than netted. "We have 34,000 out at this site and we owe
    two men 6,000" is two facts an accountant acts on separately — the first is cash to account
    for, the second is cash to hand over — and one net figure of 28,000 is neither of them.
  */
  const outWithStaff = rows
    .filter((row) => row.inHandAmount > 0)
    .reduce((sum, row) => sum + row.inHandAmount, 0);
  const owedToStaff = rows
    .filter((row) => row.inHandAmount < 0)
    .reduce((sum, row) => sum - row.inHandAmount, 0);

  async function handIt(input: IssueFloatInput) {
    setError(null);
    try {
      await issue.mutateAsync(input);
      setIssuing(false);
    } catch (cause) {
      setError(apiErrorDetail(cause));
    }
  }

  const columns: RecordColumn<FloatBalance>[] = [
    {
      key: 'holder',
      header: 'Holding it',
      card: 'title',
      cell: (row) => (
        <Stack spacing={0.25}>
          <Typography variant="body2" fontWeight={600}>
            {row.holderName ?? 'Somebody no longer on the rolls'}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {row.openFloats === 0
              ? 'Nothing open'
              : `${row.openFloats} float${row.openFloats > 1 ? 's' : ''} open${
                  row.oldestOpenOn ? `, oldest ${row.oldestOpenOn}` : ''
                }`}
          </Typography>
        </Stack>
      ),
    },
    {
      key: 'issued',
      header: 'Handed over',
      align: 'right',
      cell: (row) => <Typography variant="body2">{formatAmount(row.issuedAmount)}</Typography>,
    },
    {
      key: 'spent',
      header: 'Spent on bills',
      align: 'right',
      cell: (row) => <Typography variant="body2">{formatAmount(row.spentAmount)}</Typography>,
    },
    {
      key: 'returned',
      header: 'Cash back',
      align: 'right',
      cell: (row) => <Typography variant="body2">{formatAmount(row.returnedAmount)}</Typography>,
    },
    {
      key: 'inHand',
      header: 'With him',
      align: 'right',
      card: 'status',
      cell: (row) => <InHand amount={row.inHandAmount} />,
    },
    {
      key: 'floats',
      header: '',
      card: 'actions',
      cell: (row) => (
        <Button
          size="small"
          onClick={() => setOpenHolder(openHolder === row.userId ? null : row.userId)}
        >
          {openHolder === row.userId ? 'Hide the floats' : 'Show the floats'}
        </Button>
      ),
    },
  ];

  return (
    <Stack spacing={2}>
      <TreasuryTabs />

      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1}
        justifyContent="space-between"
        alignItems={{ sm: 'center' }}
      >
        <Box>
          <Typography variant="h5">Site floats</Typography>
          <Typography variant="body2" color="text.secondary">
            Cash handed to the people on the site, and what is left of it.
          </Typography>
        </Box>
        {mayIssue && (
          <Button
            variant="contained"
            startIcon={<AddIcon />}
            disabled={!siteId}
            onClick={() => setIssuing(true)}
          >
            Hand over cash
          </Button>
        )}
      </Stack>

      <TextField
        select
        label="Site"
        value={siteId}
        onChange={(e) => setSiteId(e.target.value)}
        sx={{ maxWidth: 280 }}
      >
        {(sites.data ?? []).map((site) => (
          <MenuItem key={site.id} value={site.id}>
            {site.code} — {site.name}
          </MenuItem>
        ))}
      </TextField>

      {error && (
        <Alert severity="error" onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <Tile
          label="Out with the staff"
          amount={outWithStaff}
          hint="Cash the office has handed over and not yet accounted for."
          accent
        />
        <Tile
          label="Owed back to the staff"
          amount={owedToStaff}
          hint="Bills they paid past their float. The company owes them this."
        />
      </Stack>

      {balances.isLoading ? (
        <CircularProgress />
      ) : (
        <RecordTable
          columns={columns}
          rows={rows}
          rowKey={(row) => row.userId}
          ariaLabel="Site floats by holder"
          empty={
            <Typography color="text.secondary">
              Nobody is holding cash at this site.
            </Typography>
          }
        />
      )}

      {/*
        The floats behind one person's balance. Opened rather than shown, because the balance is
        what the office argues about and the float is only what it argues from — the date, the
        purpose, the reference on the transfer.
      */}
      <Collapse in={Boolean(openHolder)} unmountOnExit>
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="subtitle2" gutterBottom>
            {rows.find((row) => row.userId === openHolder)?.holderName ?? 'This holder'} — every
            float handed over
          </Typography>
          <FloatRows rows={(floats.data ?? []).filter((row) => row.issuedToUserId === openHolder)} />
        </Paper>
      </Collapse>

      {issuing && siteId && (
        <IssueFloatDialog
          siteId={siteId}
          saving={issue.isPending}
          onClose={() => setIssuing(false)}
          onSave={handIt}
        />
      )}
    </Stack>
  );
}

/**
 * The one number the holder and the office argue about, with its sign said in words.
 *
 * <p>A negative amount is not drawn as a minus sign and left there. "−2,000" on a cash register
 * is read as a mistake by everyone who has not been told otherwise, and the fact it carries —
 * that the company owes this man money — is the one that needs acting on.</p>
 */
function InHand({ amount }: { amount: number }) {
  if (amount < 0) {
    return (
      <Stack spacing={0.25} alignItems={{ xs: 'flex-start', sm: 'flex-end' }}>
        <Typography variant="body2" fontWeight={600} color="error.main">
          {formatAmount(-amount)}
        </Typography>
        <Typography variant="caption" color="error.main">
          owed to him
        </Typography>
      </Stack>
    );
  }
  return (
    <Stack spacing={0.25} alignItems={{ xs: 'flex-start', sm: 'flex-end' }}>
      <Typography variant="body2" fontWeight={600}>
        {formatAmount(amount)}
      </Typography>
      <Typography variant="caption" color="text.secondary">
        {amount === 0 ? 'all accounted for' : 'in his pocket'}
      </Typography>
    </Stack>
  );
}

/** One holder's floats, newest first. Nested, so it draws as rules rather than as cards. */
function FloatRows({ rows }: { rows: SiteFloat[] }) {
  const columns: RecordColumn<SiteFloat>[] = [
    {
      key: 'number',
      header: 'Float',
      card: 'title',
      cell: (row) => (
        <Stack spacing={0.25}>
          <Typography variant="body2" fontWeight={600}>
            {row.advanceNumber}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {row.advanceDate} · {row.paymentMode.toLowerCase()}
            {row.referenceNumber ? ` · ${row.referenceNumber}` : ''}
          </Typography>
        </Stack>
      ),
    },
    { key: 'purpose', header: 'What for', cell: (row) => row.purpose },
    {
      key: 'amount',
      header: 'Handed over',
      align: 'right',
      cell: (row) => formatAmount(row.amount),
    },
    {
      key: 'spent',
      header: 'Spent',
      align: 'right',
      cell: (row) => formatAmount(row.adjustedAmount),
    },
    {
      key: 'balance',
      header: 'Left',
      align: 'right',
      cell: (row) => <InHand amount={row.balanceAmount} />,
    },
    {
      key: 'status',
      header: 'Status',
      card: 'status',
      cell: (row) => <FloatStatusChip status={row.settlementStatus} />,
    },
  ];

  return (
    <RecordTable
      columns={columns}
      rows={rows}
      rowKey={(row) => row.id}
      nested
      ariaLabel="Floats handed to this holder"
      empty={<Typography color="text.secondary">No floats at this site.</Typography>}
    />
  );
}

function FloatStatusChip({ status }: { status: SiteFloat['settlementStatus'] }) {
  switch (status) {
    case 'OVERSPENT':
      return <Chip size="small" color="error" variant="outlined" label="Overspent" />;
    case 'SETTLED':
      return <Chip size="small" variant="outlined" label="Settled" />;
    case 'PARTIALLY_SETTLED':
      return <Chip size="small" color="warning" variant="outlined" label="Part settled" />;
    case 'CANCELLED':
      return <Chip size="small" variant="outlined" label="Cancelled" />;
    default:
      return <Chip size="small" color="success" variant="outlined" label="Open" />;
  }
}

function Tile({
  label,
  amount,
  hint,
  accent,
}: {
  label: string;
  amount: number;
  hint: string;
  accent?: boolean;
}) {
  return (
    <Paper
      variant="outlined"
      sx={{ p: 2, flex: 1, borderColor: accent ? tokens.signal : 'divider' }}
    >
      <Typography variant="overline" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h6">{formatAmount(amount)}</Typography>
      <Typography variant="caption" color="text.secondary">
        {hint}
      </Typography>
    </Paper>
  );
}
