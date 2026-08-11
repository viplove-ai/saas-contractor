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
import { useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { ROLE_LABEL, seesAllSites } from '../../shared/roles';
import { useAuth } from '../auth/AuthContext';
import { useAdminSites, useUpdateUserStatus, useUsers } from './api';
import { ResetPasswordDialog } from './ResetPasswordDialog';
import { UserFormDialog } from './UserFormDialog';
import type { AdminUser } from './types';

const ROLE_FILTERS = [
  { code: '', label: 'All roles' },
  { code: 'ADMIN', label: 'Administrators' },
  { code: 'ENGINEER', label: 'Site engineers' },
  { code: 'SUPERVISOR', label: 'Site supervisors' },
  { code: 'ACCOUNTANT', label: 'Accountants' },
];

/**
 * What the Sites cell says. A member holds a set of roles, and the two kinds are not
 * exclusive — an owner can be ADMIN and supervise a site himself. Visibility follows the
 * widest role held, exactly as the server's site claim does, so a company-wide role wins
 * and the postings become a footnote rather than the answer.
 */
function siteReach(user: AdminUser): 'ALL' | 'POSTED' {
  return seesAllSites(user.roles) ? 'ALL' : 'POSTED';
}

/**
 * The admin's staff register: who can sign in, as what, and where.
 *
 * <p>Deactivating rather than deleting is the only way out of this screen. Every attendance
 * sheet, receipt and approval in the system carries the id of whoever entered it, so a
 * deleted user would take the explanation of last month's records with them.</p>
 */
export function UsersPage() {
  const { user: signedInUser, hasPermission } = useAuth();
  const [search, setSearch] = useState('');
  const [role, setRole] = useState('');
  const [editing, setEditing] = useState<AdminUser | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [resetting, setResetting] = useState<AdminUser | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const users = useUsers(search, role);
  const sites = useAdminSites('');
  const updateStatus = useUpdateUserStatus();
  const canWrite = hasPermission('user:write');

  const siteLabel = (siteId: string): string => {
    const site = sites.data?.find((candidate) => candidate.id === siteId);
    return site ? site.code : '—';
  };

  const toggleActive = async (member: AdminUser) => {
    setActionError(null);
    try {
      await updateStatus.mutateAsync({ id: member.id, active: !member.active });
    } catch (error) {
      setActionError(apiErrorDetail(error));
    }
  };

  const openNew = () => {
    setEditing(null);
    setFormOpen(true);
  };

  const openEdit = (member: AdminUser) => {
    setEditing(member);
    setFormOpen(true);
  };

  return (
    <Stack spacing={3}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        justifyContent="space-between"
        alignItems={{ sm: 'center' }}
      >
        <Stack spacing={0.5}>
          <Typography variant="h1">Members</Typography>
          <Typography color="text.secondary">
            Who can sign in, what they may do, and which sites they are posted to.
          </Typography>
        </Stack>
        {canWrite && (
          <Button variant="contained" color="secondary" onClick={openNew}>
            Onboard a member
          </Button>
        )}
      </Stack>

      {actionError && <Alert severity="error">{actionError}</Alert>}

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField
          label="Search by name or username"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <TextField
          select
          label="Role"
          value={role}
          onChange={(event) => setRole(event.target.value)}
          sx={{ minWidth: 220 }}
        >
          {ROLE_FILTERS.map((option) => (
            <MenuItem key={option.code || 'all'} value={option.code}>
              {option.label}
            </MenuItem>
          ))}
        </TextField>
      </Stack>

      {users.isLoading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      )}
      {users.isError && <Alert severity="error">{apiErrorDetail(users.error)}</Alert>}

      {users.data && (
        <Paper elevation={0} sx={{ border: 1, borderColor: 'divider', overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Member</TableCell>
                <TableCell>Roles</TableCell>
                <TableCell>Sites</TableCell>
                <TableCell>Status</TableCell>
                {canWrite && <TableCell align="right">Actions</TableCell>}
              </TableRow>
            </TableHead>
            <TableBody>
              {users.data.content.map((member) => (
                <TableRow key={member.id} hover>
                  <TableCell>
                    <Typography fontWeight={600}>{member.fullName}</Typography>
                    <Typography variant="body2" color="text.secondary">
                      {member.username}
                      {member.mobile ? ` · ${member.mobile}` : ''}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    {member.roles.map((code) => (
                      <Chip key={code} size="small" label={ROLE_LABEL[code] ?? code} sx={{ mr: 0.5 }} />
                    ))}
                  </TableCell>
                  <TableCell>
                    {/*
                      A blank cell would read as "no access" for an admin, who in fact
                      reaches everything — so the company-wide roles say so in words.
                    */}
                    {/*
                      "None yet" in grey read as a detail. It is the reason a supervisor
                      rings up to say his screens are empty, so it is a warning chip.
                    */}
                    {siteReach(member) === 'ALL' && (
                      <Typography variant="body2">
                        All sites
                        {member.siteIds.length > 0 &&
                          ` · posted at ${member.siteIds.map(siteLabel).join(', ')}`}
                      </Typography>
                    )}
                    {siteReach(member) === 'POSTED' &&
                      (member.siteIds.length === 0 ? (
                        <Chip size="small" color="warning" variant="outlined" label="Not posted" />
                      ) : (
                        member.siteIds.map(siteLabel).join(', ')
                      ))}
                  </TableCell>
                  <TableCell>
                    <Stack direction="row" spacing={0.5}>
                      <Chip
                        size="small"
                        color={member.active ? 'success' : 'default'}
                        variant={member.active ? 'filled' : 'outlined'}
                        label={member.active ? 'Active' : 'Disabled'}
                      />
                      {member.mustChangePassword && (
                        <Chip size="small" variant="outlined" label="Password not set" />
                      )}
                    </Stack>
                  </TableCell>
                  {canWrite && (
                    <TableCell align="right">
                      <Stack direction="row" spacing={1} justifyContent="flex-end">
                        <Button size="small" onClick={() => openEdit(member)}>
                          Edit
                        </Button>
                        <Button size="small" onClick={() => setResetting(member)}>
                          Reset password
                        </Button>
                        <Button
                          size="small"
                          color={member.active ? 'error' : 'primary'}
                          onClick={() => toggleActive(member)}
                          // An admin who disabled their own account would be locked out of
                          // the screen that could re-enable it; the server refuses this too.
                          disabled={member.id === signedInUser?.id}
                        >
                          {member.active ? 'Disable' : 'Enable'}
                        </Button>
                      </Stack>
                    </TableCell>
                  )}
                </TableRow>
              ))}
              {users.data.content.length === 0 && (
                <TableRow>
                  <TableCell colSpan={canWrite ? 5 : 4}>
                    <Typography color="text.secondary" sx={{ py: 2 }}>
                      No members match that search.
                    </Typography>
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </Paper>
      )}

      <UserFormDialog open={formOpen} user={editing} onClose={() => setFormOpen(false)} />
      <ResetPasswordDialog user={resetting} onClose={() => setResetting(null)} />
    </Stack>
  );
}
