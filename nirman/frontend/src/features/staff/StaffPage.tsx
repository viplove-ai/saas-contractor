import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { useAuth } from '../auth/AuthContext';
import { OfferLetterDialog } from './OfferLetterDialog';
import { SalaryHistoryDialog } from './SalaryHistoryDialog';
import { StaffProfileDialog } from './StaffProfileDialog';
import { useStaff, useStaffDashboard } from './api';
import { EMPLOYMENT_LABEL, type StaffProfile } from './types';

/**
 * The staff, and what is about to need attention about them.
 *
 * <p>The tiles are counts and the list underneath is the register, but the part that earns
 * the screen is the middle: a probation that quietly ran past its end is somebody working on
 * probation terms months after they were meant to stop, and a member with no bank account on
 * file is one the payroll total is silently lying about. Neither shows up anywhere else.</p>
 */
export function StaffPage() {
  const { hasPermission, user } = useAuth();
  const dashboard = useStaffDashboard();
  const staff = useStaff();
  const [editing, setEditing] = useState<StaffProfile | null>(null);
  const [showingSalary, setShowingSalary] = useState<StaffProfile | null>(null);
  const [offering, setOffering] = useState<StaffProfile | null>(null);

  const canWrite = hasPermission('staff:write');
  // The letter goes out over the administrator's own signature, so nobody else is offered it.
  const canOffer = canWrite && (user?.roles?.includes('ADMIN') ?? false);

  return (
    <Stack spacing={3}>
      <Stack spacing={0.5}>
        <Typography variant="h1">Staff</Typography>
        <Typography color="text.secondary">
          What an employer has to hold about the people on the payroll: how to reach them, who
          to telephone, where the salary goes, and what was agreed.
        </Typography>
      </Stack>

      {dashboard.isError && <Alert severity="error">{apiErrorDetail(dashboard.error)}</Alert>}
      {dashboard.data && (
        <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
          <Tile label="On the payroll" value={String(dashboard.data.totalActive)} />
          <Tile label="Permanent" value={String(dashboard.data.permanent)} />
          <Tile label="Contractual" value={String(dashboard.data.contractual)} />
          <Tile label="On probation" value={String(dashboard.data.onProbation)} />
          <Tile
            label="Payroll a month"
            value={formatAmount(dashboard.data.monthlyPayroll)}
            hint="From the salary history, summed on every read"
          />
        </Stack>
      )}

      {dashboard.data && dashboard.data.attentionNeeded.length > 0 && (
        <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'warning.main' }}>
          <Typography variant="h2" sx={{ fontSize: '1.1rem', mb: 1 }}>
            Needs attention
          </Typography>
          <Stack component="ul" spacing={0.5} sx={{ m: 0, pl: 2.5 }}>
            {dashboard.data.attentionNeeded.map((alert, index) => (
              <Typography component="li" key={`${alert.userId}-${index}`} variant="body2">
                <strong>{alert.fullName}</strong> — {alert.reason}
                {alert.on && ` (${alert.on})`}
              </Typography>
            ))}
          </Stack>
        </Paper>
      )}

      {staff.isLoading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      )}
      {staff.isError && <Alert severity="error">{apiErrorDetail(staff.error)}</Alert>}

      {/* Phone: a card each, the same answer the other registers give. */}
      {staff.data && (
        <Stack
          component="ul"
          aria-label="Staff"
          spacing={2}
          sx={{ display: { xs: 'flex', md: 'none' }, listStyle: 'none', m: 0, p: 0 }}
        >
          {staff.data.map((member) => (
            <Paper
              key={member.userId}
              component="li"
              elevation={0}
              sx={{ p: 2, border: 1, borderColor: 'divider' }}
            >
              <Stack spacing={1}>
                <Box>
                  <Typography fontWeight={600}>{member.fullName}</Typography>
                  <Typography variant="body2" color="text.secondary">
                    {member.username} · {EMPLOYMENT_LABEL[member.employmentType]}
                  </Typography>
                </Box>
                <Typography variant="body2" color="text.secondary">
                  {member.currentSalary === undefined
                    ? 'No salary recorded'
                    : `${formatAmount(member.currentSalary)} a month`}
                </Typography>
                {statusChip(member)}
                {canWrite && (
                  <Stack direction="row" spacing={1}>
                    <Button size="small" onClick={() => setEditing(member)}>
                      Record
                    </Button>
                    <Button size="small" onClick={() => setShowingSalary(member)}>
                      Salary
                    </Button>
                    {canOffer && (
                      <Button size="small" onClick={() => setOffering(member)}>
                        Offer letter
                      </Button>
                    )}
                  </Stack>
                )}
              </Stack>
            </Paper>
          ))}
        </Stack>
      )}

      {staff.data && (
        <Paper
          elevation={0}
          sx={{
            display: { xs: 'none', md: 'block' },
            border: 1,
            borderColor: 'divider',
            overflowX: 'auto',
          }}
        >
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Member</TableCell>
                <TableCell>Engagement</TableCell>
                <TableCell>Joined</TableCell>
                <TableCell align="right">Paid a month</TableCell>
                <TableCell>State</TableCell>
                {canWrite && <TableCell align="right">Actions</TableCell>}
              </TableRow>
            </TableHead>
            <TableBody>
              {staff.data.map((member) => (
                <TableRow key={member.userId} hover>
                  <TableCell>
                    <Typography fontWeight={600}>{member.fullName}</Typography>
                    <Typography variant="body2" color="text.secondary">
                      {member.username}
                    </Typography>
                  </TableCell>
                  <TableCell>{EMPLOYMENT_LABEL[member.employmentType]}</TableCell>
                  <TableCell>{member.joinedOn ?? '—'}</TableCell>
                  {/*
                    What is paid, not what was agreed. The two are allowed to differ, and the
                    difference is the thing worth seeing — an offer that was never honoured.
                  */}
                  <TableCell align="right">
                    {member.currentSalary === undefined ? '—' : formatAmount(member.currentSalary)}
                  </TableCell>
                  <TableCell>{statusChip(member)}</TableCell>
                  {canWrite && (
                    <TableCell align="right">
                      <Stack direction="row" spacing={1} justifyContent="flex-end">
                        <Button size="small" onClick={() => setEditing(member)}>
                          Record
                        </Button>
                        <Button size="small" onClick={() => setShowingSalary(member)}>
                          Salary
                        </Button>
                        {canOffer && (
                          <Button size="small" onClick={() => setOffering(member)}>
                            Offer letter
                          </Button>
                        )}
                      </Stack>
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
      )}

      {editing && (
        <StaffProfileDialog open member={editing} onClose={() => setEditing(null)} />
      )}
      {offering && (
        <OfferLetterDialog open member={offering} onClose={() => setOffering(null)} />
      )}
      {showingSalary && (
        <SalaryHistoryDialog
          open
          member={showingSalary}
          onClose={() => setShowingSalary(null)}
        />
      )}
    </Stack>
  );
}

/** The one thing about this member that is not routine, or nothing. */
function statusChip(member: StaffProfile) {
  if (member.version === undefined) {
    return <Chip size="small" color="warning" label="No record yet" />;
  }
  if (member.probationOverdue) {
    return <Chip size="small" color="error" label="Probation overdue" />;
  }
  if (member.exitDate) {
    return <Chip size="small" label={`Left ${member.exitDate}`} />;
  }
  if (!member.bankAccountNo) {
    return <Chip size="small" color="warning" label="No bank account" />;
  }
  return <Chip size="small" color="success" label="On file" />;
}

function Tile({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <Paper
      elevation={0}
      sx={{ p: 2, border: 1, borderColor: 'divider', minWidth: 170, flex: '1 1 170px' }}
    >
      <Typography variant="overline" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h2" sx={{ fontSize: '1.5rem' }}>
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
