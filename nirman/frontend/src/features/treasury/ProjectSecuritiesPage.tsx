import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  IconButton,
  Paper,
  Stack,
  Tooltip,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { tokens } from '../../app/theme';
import { useAuth } from '../auth/AuthContext';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { AddSecurityDialog, SecurityActionDialog, type SecurityAction } from './SecurityDialogs';
import {
  useDeleteSecurity,
  useProjectDirectory,
  useProjectSecurities,
  useSecurityProposal,
} from './api';
import {
  INSTRUMENT_LABEL,
  STATUS_STYLE,
  TYPE_COLOR,
  TYPE_LABEL,
  TYPE_TINT,
  releaseWording,
  urgencyColor,
} from './palette';
import type { Security, SecurityProposal } from './types';

/**
 * One contract's deposits and guarantees.
 *
 * <p>Its own route rather than a panel on the project page, for the reason the planning screen
 * has one: features do not import from one another, and a deposit register is not a fact about
 * the schedule of work.</p>
 *
 * <p>The page has two halves. Above, what the contract and its notice say ought to be lodged —
 * arithmetic, recomputed on every visit, binding nothing. Below, the register of what actually
 * was. The gap between them is the screen's whole use: a guarantee the contract calls for and
 * nobody has placed is a gate on being paid at all, and it shows here as a proposal with no row
 * against it.</p>
 */
export function ProjectSecuritiesPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const { hasPermission } = useAuth();
  const mayRead = hasPermission('security:read');
  const mayWrite = hasPermission('security:write');

  const securities = useProjectSecurities(projectId, mayRead);
  const proposal = useSecurityProposal(projectId, mayRead);
  const directory = useProjectDirectory(mayWrite);
  const remove = useDeleteSecurity();

  const [adding, setAdding] = useState<SecurityProposal | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [action, setAction] = useState<SecurityAction | null>(null);
  const [acting, setActing] = useState<Security | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (!mayRead) {
    return (
      <Stack spacing={2}>
        <BackLink projectId={projectId} />
        <Alert severity="info">
          Deposits and guarantees are kept by the office. Ask an administrator if you need to
          see them.
        </Alert>
      </Stack>
    );
  }

  const rows = securities.data ?? [];
  const held = rows
    .filter((row) => row.status === 'LODGED')
    .reduce((sum, row) => sum + row.heldAmount, 0);

  function openAction(next: SecurityAction, security: Security) {
    setActing(security);
    setAction(next);
  }

  async function removeRow(security: Security) {
    setError(null);
    try {
      await remove.mutateAsync({ id: security.id, projectId: security.projectId });
    } catch (cause) {
      setError(apiErrorDetail(cause));
    }
  }

  const columns: RecordColumn<Security>[] = [
    {
      key: 'kind',
      header: 'Kind',
      card: 'title',
      cell: (row) => (
        <Stack spacing={0.5}>
          <Chip
            size="small"
            label={TYPE_LABEL[row.securityType]}
            sx={{
              bgcolor: TYPE_TINT[row.securityType],
              color: TYPE_COLOR[row.securityType],
              fontWeight: 600,
              borderRadius: 1,
              alignSelf: 'flex-start',
            }}
          />
          <Typography variant="body2" color="text.secondary">
            {INSTRUMENT_LABEL[row.instrument]}
            {row.referenceNo ? ` · ${row.referenceNo}` : ''}
          </Typography>
        </Stack>
      ),
    },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      cell: (row) => (
        <>
          <Typography variant="caption" fontWeight={700}>
            {formatAmount(row.amount)}
          </Typography>
          {/*
            The held figure is only worth printing where it differs — which is a retention part
            way through, and nothing else. Printing it on every row would invite the reader to
            add two columns that are the same money.
          */}
          {row.heldAmount !== row.amount && (
            <Typography variant="body2" color="text.secondary">
              {formatAmount(row.heldAmount)} held
            </Typography>
          )}
        </>
      ),
    },
    {
      key: 'bank',
      header: 'Where',
      cell: (row) => (
        <>
          <Typography variant="body2">{row.bankName ?? '—'}</Typography>
          {row.maturityOn && (
            <Typography variant="body2" color="text.secondary">
              Matures {row.maturityOn}
            </Typography>
          )}
        </>
      ),
    },
    {
      key: 'release',
      header: 'Due back',
      align: 'right',
      cell: (row) =>
        row.status === 'RELEASED' ? (
          <Typography variant="body2" sx={{ color: tokens.ok }}>
            Released {row.releasedOn}
            {row.redeployedToProjectCode && (
              <>
                <br />
                Now funding {row.redeployedToProjectCode}
              </>
            )}
          </Typography>
        ) : (
          <Typography variant="caption" sx={{ color: urgencyColor(row.daysToRelease) }}>
            {row.expectedReleaseOn ?? 'No date set'}
            <br />
            {releaseWording(row.daysToRelease)}
          </Typography>
        ),
    },
    {
      key: 'status',
      header: 'Status',
      card: 'status',
      cell: (row) => {
        const style = STATUS_STYLE[row.status];
        return (
          <Chip
            size="small"
            label={style.label}
            sx={{ bgcolor: style.bg, color: style.fg, fontWeight: 600, borderRadius: 1 }}
          />
        );
      },
    },
    {
      key: 'actions',
      header: '',
      card: 'actions',
      cell: (row) => (mayWrite ? actionsFor(row) : null),
    },
  ];

  function actionsFor(row: Security) {
    const isRetention = row.securityType === 'SECURITY_DEPOSIT';
    return (
      <>
        {row.status === 'DUE' && !isRetention && (
          <Button size="small" onClick={() => openAction('lodge', row)}>
            Lodged
          </Button>
        )}
        {isRetention && row.status !== 'RELEASED' && row.status !== 'FORFEITED' && (
          <Button size="small" onClick={() => openAction('retained', row)}>
            Withheld to date
          </Button>
        )}
        {row.status === 'LODGED' && (
          <Button size="small" onClick={() => openAction('release', row)}>
            Released
          </Button>
        )}
        {row.freeToReuse && (
          <Button size="small" onClick={() => openAction('redeploy', row)}>
            Put on a tender
          </Button>
        )}
        {(row.status === 'DUE' || row.status === 'LODGED') && (
          <>
            <Button size="small" onClick={() => openAction('edit', row)}>
              Amend
            </Button>
            <Button size="small" color="error" onClick={() => openAction('forfeit', row)}>
              Forfeited
            </Button>
          </>
        )}
        {row.status === 'DUE' && (
          <Button size="small" color="error" onClick={() => void removeRow(row)}>
            Remove
          </Button>
        )}
      </>
    );
  }

  const outstanding = (proposal.data ?? []).filter((item) => !item.alreadyRecorded);

  return (
    <Stack spacing={3} sx={{ pb: 4 }}>
      <Stack direction="row" spacing={1} alignItems="flex-start">
        <BackLink projectId={projectId} />
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="h5" component="h1">
            Deposits &amp; guarantees
          </Typography>
          <Typography color="text.secondary">
            {formatAmount(held)} of this contract is with the department
          </Typography>
        </Box>
      </Stack>

      {error && <Alert severity="error">{error}</Alert>}
      {securities.isError && <Alert severity="error">{apiErrorDetail(securities.error)}</Alert>}
      {(securities.isLoading || proposal.isLoading) && <CircularProgress />}

      {outstanding.length > 0 && (
        <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: tokens.signal }}>
          <Typography fontWeight={700} color={tokens.signal} gutterBottom>
            What this contract calls for and has no record against it
          </Typography>
          <Typography variant="body2" color="text.secondary" gutterBottom>
            Worked out from the contract and, where one was read, its notice. Nothing here is
            recorded until you record it — and the figures are a starting point, not a rule: a
            bank issues a fixed deposit for the amount it issues.
          </Typography>
          <Stack spacing={1.5} sx={{ mt: 2 }}>
            {outstanding.map((item) => (
              <Box key={item.securityType}>
                <Stack
                  direction={{ xs: 'column', sm: 'row' }}
                  spacing={1}
                  alignItems={{ sm: 'center' }}
                  justifyContent="space-between"
                >
                  <Box sx={{ minWidth: 0 }}>
                    <Typography
                      fontWeight={600}
                      sx={{ color: TYPE_COLOR[item.securityType] }}
                    >
                      {TYPE_LABEL[item.securityType]} · {formatAmount(item.amount)}
                    </Typography>
                    <Typography variant="body2" color="text.secondary">
                      {item.basis}
                      {item.expectedReleaseOn
                        ? ` Due back ${item.expectedReleaseOn}.`
                        : ' No release date can be worked out yet — set the contract calendar.'}
                    </Typography>
                  </Box>
                  {mayWrite && (
                    <Button
                      variant="outlined"
                      sx={{ flexShrink: 0 }}
                      onClick={() => {
                        setAdding(item);
                        setAddOpen(true);
                      }}
                    >
                      Record it
                    </Button>
                  )}
                </Stack>
                <Divider sx={{ mt: 1.5 }} />
              </Box>
            ))}
          </Stack>
        </Paper>
      )}

      <RecordTable
        columns={columns}
        rows={rows}
        rowKey={(row) => row.id}
        ariaLabel="Deposits against this contract"
        empty="Nothing recorded yet."
      />

      <Typography variant="body2">
        <Link to="/treasury">Every contract&apos;s deposits, together</Link>
      </Typography>

      {projectId && (
        <AddSecurityDialog
          open={addOpen}
          projectId={projectId}
          proposal={adding}
          onClose={() => {
            setAddOpen(false);
            setAdding(null);
          }}
        />
      )}
      <SecurityActionDialog
        action={action}
        security={acting}
        projectOptions={directory.data ?? []}
        onClose={() => {
          setAction(null);
          setActing(null);
        }}
      />
    </Stack>
  );
}

function BackLink({ projectId }: { projectId: string | undefined }) {
  return (
    <Tooltip title="Back to the project">
      <IconButton
        component={Link}
        to={projectId ? `/projects/${projectId}` : '/projects'}
        aria-label="Back to the project"
        edge="start"
      >
        <ArrowBackIcon />
      </IconButton>
    </Tooltip>
  );
}
