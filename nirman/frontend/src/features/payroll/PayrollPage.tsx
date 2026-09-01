import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  MenuItem,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { useAuth } from '../auth/AuthContext';
import { PayslipDialog } from './PayslipDialog';
import {
  useDownloadPayrollPdf,
  useFinalisePayrollRun,
  useOpenPayrollRun,
  usePayrollRun,
  usePayrollRuns,
  useRedrawPayrollRun,
} from './api';
import type { Payslip } from './types';

/**
 * The month's payroll: drawn, corrected, finalised once.
 *
 * <p><b>The list of who could not be drawn is the part that earns the screen.</b> A payroll of
 * eleven in an office of fourteen looks exactly like a complete one, and the three missing are
 * always the three nobody got round to recording a structure for. So they are named, with the
 * sentence that says what to do about each.</p>
 *
 * <p><b>Finalising is offered once and never undone.</b> By the time a month is final its
 * payslips are in twenty pockets, and a run that could be reopened would be a set of documents
 * that can still change. A figure found wrong afterwards is corrected the way an over-measured
 * quantity is: in the next month, as a line that says what it is.</p>
 */
export function PayrollPage() {
  const { hasPermission } = useAuth();
  const runs = usePayrollRuns();
  const [selected, setSelected] = useState<string | undefined>();
  const run = usePayrollRun(selected);
  const open = useOpenPayrollRun();
  const redraw = useRedrawPayrollRun();
  const finalise = useFinalisePayrollRun();
  const download = useDownloadPayrollPdf();
  const [editing, setEditing] = useState<Payslip | null>(null);
  const [month, setMonth] = useState(() => lastMonth());
  const [payableDays, setPayableDays] = useState('26');
  const [error, setError] = useState<string | null>(null);

  const canProcess = hasPermission('payroll:process');

  // The month just gone is what an office opens this screen for, so it is chosen for it.
  useEffect(() => {
    if (!selected && runs.data && runs.data.length > 0) {
      setSelected(runs.data[0]!.id);
    }
  }, [runs.data, selected]);

  const act = async (work: () => Promise<unknown>) => {
    setError(null);
    try {
      await work();
    } catch (caught) {
      setError(apiErrorDetail(caught));
    }
  };

  const draft = run.data?.status === 'DRAFT';
  /*
    The firm does not deduct professional tax, so the column is not shown — except on a month
    drawn while it did, whose deductions have to keep coming to the total beside them. The same
    rule the register PDF follows, and the reason the figure is still on the response at all.
  */
  const anyProfessionalTax = (run.data?.payslips ?? []).some((slip) => slip.professionalTax !== 0);

  return (
    <Stack spacing={3}>
      <Stack spacing={0.5}>
        <Typography variant="h1">Payroll</Typography>
        <Typography color="text.secondary">
          A month is drawn from the salary structures, corrected for the days and the figures
          nobody can compute, and finalised once — because by then the payslips have been
          handed over.
        </Typography>
      </Stack>

      {error && <Alert severity="error">{error}</Alert>}
      {runs.isError && <Alert severity="error">{apiErrorDetail(runs.error)}</Alert>}

      {canProcess && (
        <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems="flex-start">
            <TextField
              label="Month"
              type="month"
              value={month}
              onChange={(event) => setMonth(event.target.value)}
              InputLabelProps={{ shrink: true }}
              size="small"
            />
            <TextField
              label="Paid against"
              value={payableDays}
              onChange={(event) => setPayableDays(event.target.value)}
              size="small"
              select
              sx={{ minWidth: 200 }}
              helperText="The denominator every slip prorates on"
            >
              <MenuItem value="26">26 days</MenuItem>
              <MenuItem value="30">30 days</MenuItem>
              <MenuItem value="31">31 days</MenuItem>
              <MenuItem value="">The calendar&apos;s own count</MenuItem>
            </TextField>
            <Button
              variant="contained"
              disabled={open.isPending}
              onClick={() =>
                act(async () => {
                  const created = await open.mutateAsync({
                    periodMonth: `${month}-01`,
                    ...(payableDays ? { payableDays: Number(payableDays) } : {}),
                  });
                  setSelected(created.id);
                })
              }
            >
              Draw this month
            </Button>
          </Stack>
        </Paper>
      )}

      {runs.data && runs.data.length > 0 && (
        <TextField
          label="Month"
          select
          size="small"
          value={selected ?? ''}
          onChange={(event) => setSelected(event.target.value)}
          sx={{ maxWidth: 340 }}
        >
          {runs.data.map((summary) => (
            <MenuItem key={summary.id} value={summary.id}>
              {monthLabel(summary.periodMonth)} — {summary.headcount} people,{' '}
              {formatAmount(summary.netPayable)}
              {summary.status === 'DRAFT' ? ' (draft)' : ''}
            </MenuItem>
          ))}
        </TextField>
      )}

      {runs.data && runs.data.length === 0 && (
        <Alert severity="info">No month has been drawn yet.</Alert>
      )}

      {run.isLoading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      )}
      {run.isError && <Alert severity="error">{apiErrorDetail(run.error)}</Alert>}

      {run.data && (
        <>
          <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap alignItems="center">
            <Typography variant="h2" sx={{ fontSize: '1.2rem' }}>
              {monthLabel(run.data.periodMonth)}
            </Typography>
            <Chip
              size="small"
              color={draft ? 'warning' : 'success'}
              label={draft ? 'Draft' : 'Finalised'}
            />
            <Typography variant="body2" color="text.secondary">
              paid against {run.data.payableDays} days
            </Typography>
          </Stack>

          <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
            <Tile label="On the payroll" value={String(run.data.totals.headcount)} />
            <Tile label="Gross earnings" value={formatAmount(run.data.totals.grossEarnings)} />
            <Tile label="Deductions" value={formatAmount(run.data.totals.totalDeductions)} />
            <Tile
              label="Net payable"
              value={formatAmount(run.data.totals.netPayable)}
              hint="What has to leave the bank"
            />
            <Tile
              label="Cost of employing"
              value={formatAmount(run.data.totals.employerCost)}
              hint="Paid to them, plus remitted for them"
            />
          </Stack>

          {/*
            Named, not counted. A payroll of eleven in an office of fourteen looks complete,
            and the three missing are always the three nobody recorded a structure for.
          */}
          {run.data.notDrawn.length > 0 && (
            <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'warning.main' }}>
              <Typography variant="h2" sx={{ fontSize: '1.1rem', mb: 1 }}>
                Not on this payroll
              </Typography>
              <Stack component="ul" spacing={0.5} sx={{ m: 0, pl: 2.5 }}>
                {run.data.notDrawn.map((row) => (
                  <Typography component="li" key={row.userId} variant="body2">
                    <strong>{row.fullName}</strong> — {row.reason}
                  </Typography>
                ))}
              </Stack>
            </Paper>
          )}

          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
            <Button
              size="small"
              onClick={() =>
                act(() =>
                  download.mutateAsync({
                    path: `/payroll/runs/${run.data.id}/register.pdf`,
                    fileName: `payroll-register-${run.data.periodMonth.slice(0, 7)}.pdf`,
                  }),
                )
              }
            >
              Register (PDF)
            </Button>
            <Button
              size="small"
              disabled={run.data.payslips.length === 0}
              onClick={() =>
                act(() =>
                  download.mutateAsync({
                    path: `/payroll/runs/${run.data.id}/payslips.pdf`,
                    fileName: `payslips-${run.data.periodMonth.slice(0, 7)}.pdf`,
                  }),
                )
              }
            >
              All payslips (PDF)
            </Button>
            {canProcess && draft && (
              <>
                <Button size="small" onClick={() => act(() => redraw.mutateAsync(run.data.id))}>
                  Draw again
                </Button>
                <Button
                  size="small"
                  variant="contained"
                  disabled={run.data.payslips.length === 0 || finalise.isPending}
                  onClick={() => act(() => finalise.mutateAsync(run.data.id))}
                >
                  Finalise the month
                </Button>
              </>
            )}
          </Stack>

          {draft && (
            <Alert severity="info">
              Finalising is offered once and cannot be undone: by then the payslips have been
              handed over. A figure found wrong afterwards is corrected in the next month, as a
              line that says what it is.
            </Alert>
          )}

          <Paper elevation={0} sx={{ border: 1, borderColor: 'divider', overflowX: 'auto' }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Member</TableCell>
                  <TableCell align="right">Days</TableCell>
                  <TableCell align="right">Gross</TableCell>
                  <TableCell align="right">Earned</TableCell>
                  <TableCell align="right">EPF</TableCell>
                  <TableCell align="right">ESI</TableCell>
                  {anyProfessionalTax && <TableCell align="right">Prof. tax</TableCell>}
                  <TableCell align="right">TDS</TableCell>
                  <TableCell align="right">Advance</TableCell>
                  <TableCell align="right">Net</TableCell>
                  <TableCell align="right">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {run.data.payslips.map((slip) => (
                  <TableRow key={slip.id} hover>
                    <TableCell>
                      <Typography fontWeight={600}>{slip.employeeName}</Typography>
                      <Typography variant="body2" color="text.secondary">
                        {[slip.employeeNumber, slip.designation].filter(Boolean).join(' · ') ||
                          '—'}
                      </Typography>
                    </TableCell>
                    <TableCell align="right">
                      {slip.paidDays} / {slip.payableDays}
                    </TableCell>
                    <TableCell align="right">{formatAmount(slip.structGross)}</TableCell>
                    <TableCell align="right">{formatAmount(slip.totalEarnings)}</TableCell>
                    <TableCell align="right">{formatAmount(slip.pfEmployee)}</TableCell>
                    <TableCell align="right">{formatAmount(slip.esiEmployee)}</TableCell>
                    {anyProfessionalTax && (
                      <TableCell align="right">{formatAmount(slip.professionalTax)}</TableCell>
                    )}
                    <TableCell align="right">{formatAmount(slip.tds)}</TableCell>
                    <TableCell align="right">{formatAmount(slip.salaryAdvance)}</TableCell>
                    <TableCell align="right">
                      <strong>{formatAmount(slip.netAmount)}</strong>
                    </TableCell>
                    <TableCell align="right">
                      <Stack direction="row" spacing={1} justifyContent="flex-end">
                        {canProcess && draft && (
                          <Button size="small" onClick={() => setEditing(slip)}>
                            Edit
                          </Button>
                        )}
                        <Button
                          size="small"
                          onClick={() =>
                            act(() =>
                              download.mutateAsync({
                                path: `/payroll/payslips/${slip.id}/pdf`,
                                fileName: `payslip-${slip.employeeName}.pdf`,
                              }),
                            )
                          }
                        >
                          Slip
                        </Button>
                      </Stack>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Paper>
        </>
      )}

      {editing && (
        <PayslipDialog open slip={editing} onClose={() => setEditing(null)} />
      )}
    </Stack>
  );
}

function Tile({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider', minWidth: 170 }}>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h2" sx={{ fontSize: '1.4rem' }}>
        {value}
      </Typography>
      {hint && (
        <Typography variant="caption" color="text.secondary">
          {hint}
        </Typography>
      )}
    </Paper>
  );
}

/** The month just gone, which is the one an office opens this screen to run. */
function lastMonth(): string {
  const now = new Date();
  const previous = new Date(now.getFullYear(), now.getMonth() - 1, 1);
  return `${previous.getFullYear()}-${String(previous.getMonth() + 1).padStart(2, '0')}`;
}

function monthLabel(isoDate: string): string {
  const [year, month] = isoDate.split('-');
  return new Date(Number(year), Number(month) - 1, 1).toLocaleDateString(undefined, {
    month: 'long',
    year: 'numeric',
  });
}
