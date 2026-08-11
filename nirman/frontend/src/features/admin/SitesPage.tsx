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
import { Link } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { useAuth } from '../auth/AuthContext';
import { useAdminSites, useProjects, useUsers } from './api';
import { SiteFormDialog } from './SiteFormDialog';
import type { AdminSite, SiteStatus } from './types';

const STATUS_COLOR: Record<SiteStatus, 'success' | 'default' | 'warning' | 'error'> = {
  ACTIVE: 'success',
  PLANNED: 'default',
  SUSPENDED: 'warning',
  CLOSED: 'error',
};

/**
 * The admin's site register, and the screen where a site gets its people.
 *
 * <p>The staff columns are the point of it. Everywhere else in the system, site access is
 * an invisible row in {@code user_site_assignments}; here it is a name in a column, so an
 * admin can see at a glance which site nobody is watching.</p>
 */
export function SitesPage() {
  const { hasPermission } = useAuth();
  const [projectId, setProjectId] = useState('');
  const [editing, setEditing] = useState<AdminSite | null>(null);
  const [formOpen, setFormOpen] = useState(false);

  const projects = useProjects();
  const sites = useAdminSites(projectId);
  // Both lists at once: a site names one of each, and the table has to turn those two ids
  // into names without a request per row.
  const engineers = useUsers('', 'ENGINEER');
  const supervisors = useUsers('', 'SUPERVISOR');
  const canWrite = hasPermission('site:write');

  const staffName = (id: string | undefined, pool: 'engineer' | 'supervisor'): string => {
    if (!id) {
      return 'Not posted';
    }
    const source = pool === 'engineer' ? engineers.data?.content : supervisors.data?.content;
    return source?.find((member) => member.id === id)?.fullName ?? '—';
  };

  const projectName = (id: string): string => {
    const project = projects.data?.find((candidate) => candidate.id === id);
    return project ? project.code : '—';
  };

  const openNew = () => {
    setEditing(null);
    setFormOpen(true);
  };

  const openEdit = (site: AdminSite) => {
    setEditing(site);
    setFormOpen(true);
  };

  const columns: RecordColumn<AdminSite>[] = [
    {
      key: 'site',
      header: 'Site',
      card: 'title',
      cell: (site) => (
        <>
          <Typography fontWeight={600}>{site.code}</Typography>
          <Typography variant="body2" color="text.secondary">
            {site.name}
          </Typography>
        </>
      ),
    },
    { key: 'project', header: 'Project', cell: (site) => projectName(site.projectId) },
    {
      key: 'engineer',
      header: 'Site engineer',
      cell: (site) => staffName(site.siteEngineerId, 'engineer'),
    },
    {
      key: 'supervisor',
      header: 'Supervisor',
      cell: (site) => staffName(site.supervisorId, 'supervisor'),
    },
    {
      key: 'shift',
      header: 'Shift',
      cell: (site) => <Typography variant="caption">{site.standardShiftHours} h</Typography>,
    },
    {
      key: 'status',
      header: 'Status',
      card: 'status',
      cell: (site) => <Chip size="small" color={STATUS_COLOR[site.status]} label={site.status} />,
    },
    ...(canWrite
      ? [
          {
            key: 'actions',
            header: 'Actions',
            align: 'right' as const,
            card: 'actions' as const,
            cell: (site: AdminSite) => (
              <Button size="small" onClick={() => openEdit(site)}>
                Edit
              </Button>
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
          <Typography variant="h1">Sites</Typography>
          <Typography color="text.secondary">
            Each site's engineer and supervisor. Posting someone here is what lets them work
            on it — and on nothing else.
          </Typography>
        </Stack>
        {canWrite && (
          <Button
            variant="contained"
            color="secondary"
            onClick={openNew}
            // A site has to belong to a project, so with none on file the button would
            // only open a form that cannot be submitted.
            disabled={projects.data?.length === 0}
          >
            Add a site
          </Button>
        )}
      </Stack>

      <TextField
        select
        label="Project"
        value={projectId}
        onChange={(event) => setProjectId(event.target.value)}
        sx={{ maxWidth: 320 }}
      >
        <MenuItem value="">All projects</MenuItem>
        {(projects.data ?? []).map((project) => (
          <MenuItem key={project.id} value={project.id}>
            {project.code} — {project.name}
          </MenuItem>
        ))}
      </TextField>

      {sites.isLoading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      )}
      {sites.isError && <Alert severity="error">{apiErrorDetail(sites.error)}</Alert>}

      {sites.data && (
        <RecordTable
          columns={columns}
          rows={sites.data}
          rowKey={(site) => site.id}
          ariaLabel="Sites"
          empty={
            <Typography color="text.secondary">
              No sites yet.{' '}
              {projects.data?.length === 0 && (
                <>
                  A site belongs to a project, and there are none yet —{' '}
                  <Link to="/projects">add a project first</Link>.
                </>
              )}
            </Typography>
          }
        />
      )}

      <SiteFormDialog open={formOpen} site={editing} onClose={() => setFormOpen(false)} />
    </Stack>
  );
}
