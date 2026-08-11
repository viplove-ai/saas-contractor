import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { isSiteScopedRole, ROLE_LABEL } from '../../shared/roles';
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

function isSiteScoped(user: AdminUser): boolean {
  return user.roles.some(isSiteScopedRole);
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

  const columns: RecordColumn<AdminUser>[] = [
    {
      key: 'member',
      header: 'Member',
      card: 'title',
      cell: (member) => (
        <>
          <Typography fontWeight={600}>{member.fullName}</Typography>
          <Typography variant="body2" color="text.secondary">
            {member.username}
            {member.mobile ? ` · ${member.mobile}` : ''}
          </Typography>
        </>
      ),
    },
    {
      key: 'role',
      header: 'Role',
      cell: (member) =>
        member.roles.map((code) => (
          <Chip key={code} size="small" label={ROLE_LABEL[code] ?? code} sx={{ mr: 0.5 }} />
        )),
    },
    {
      key: 'sites',
      header: 'Sites',
      cell: (member) =>
        /*
          A blank cell would read as "no access" for an admin, who in fact reaches
          everything — so the company-wide roles say so in words.
        */
        isSiteScoped(member)
          ? member.siteIds.map(siteLabel).join(', ') || 'None yet'
          : 'All sites',
    },
    {
      key: 'status',
      header: 'Status',
      card: 'status',
      cell: (member) => (
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
      ),
    },
    ...(canWrite
      ? [
          {
            key: 'actions',
            header: 'Actions',
            align: 'right' as const,
            card: 'actions' as const,
            cell: (member: AdminUser) => (
              <>
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
                  // An admin who disabled their own account would be locked out of the
                  // screen that could re-enable it; the server refuses this too.
                  disabled={member.id === signedInUser?.id}
                >
                  {member.active ? 'Disable' : 'Enable'}
                </Button>
              </>
            ),
          },
        ]
      : []),
  ];

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
        <RecordTable
          columns={columns}
          rows={users.data.content}
          rowKey={(member) => member.id}
          ariaLabel="Members"
          empty={<Typography color="text.secondary">No members match that search.</Typography>}
        />
      )}

      <UserFormDialog open={formOpen} user={editing} onClose={() => setFormOpen(false)} />
      <ResetPasswordDialog user={resetting} onClose={() => setResetting(null)} />
    </Stack>
  );
}
