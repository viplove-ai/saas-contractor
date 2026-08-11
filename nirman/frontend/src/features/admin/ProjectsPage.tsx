import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Stack,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { useAuth } from '../auth/AuthContext';
import { useAdminSites, useProjects } from './api';
import { ProjectFormDialog } from './ProjectFormDialog';
import type { AdminProject, ProjectStatus } from './types';

const STATUS_COLOR: Record<ProjectStatus, 'success' | 'default' | 'warning' | 'error'> = {
  ACTIVE: 'success',
  PLANNED: 'default',
  ON_HOLD: 'warning',
  COMPLETED: 'default',
  CLOSED: 'error',
};

const STATUS_LABEL: Record<ProjectStatus, string> = {
  ACTIVE: 'Active',
  PLANNED: 'Planned',
  ON_HOLD: 'On hold',
  COMPLETED: 'Completed',
  CLOSED: 'Closed',
};

/**
 * The contracts the company is running. First stop in setting the system up: a site belongs
 * to a project, and everything else — attendance, stock, expenses — belongs to a site.
 *
 * <p>The site count is the useful column, not the money: a project with no sites is one
 * nobody can record anything against yet, and that is the state this screen exists to get
 * you out of.</p>
 */
export function ProjectsPage() {
  const { hasPermission } = useAuth();
  const [editing, setEditing] = useState<AdminProject | null>(null);
  const [formOpen, setFormOpen] = useState(false);

  const projects = useProjects();
  const sites = useAdminSites('');
  const canWrite = hasPermission('project:write');

  const siteCount = (projectId: string): number =>
    sites.data?.filter((site) => site.projectId === projectId).length ?? 0;

  const openNew = () => {
    setEditing(null);
    setFormOpen(true);
  };

  const openEdit = (project: AdminProject) => {
    setEditing(project);
    setFormOpen(true);
  };

  const columns: RecordColumn<AdminProject>[] = [
    {
      key: 'project',
      header: 'Project',
      card: 'title',
      cell: (project) => (
        /*
          The link is on the name rather than the whole row: the row carries its own
          buttons, and a clickable row wrapped around them makes it a guess which one a
          click lands on.
        */
        <Link to={`/projects/${project.id}`} style={{ textDecoration: 'none', color: 'inherit' }}>
          <Typography fontWeight={600} sx={{ '&:hover': { textDecoration: 'underline' } }}>
            {project.code}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {project.name}
          </Typography>
        </Link>
      ),
    },
    {
      key: 'client',
      header: 'Client',
      cell: (project) => project.clientDepartment || '—',
    },
    {
      key: 'value',
      header: 'Contract value',
      align: 'right',
      cell: (project) => (
        <Typography variant="caption">
          {project.contractValue == null ? '—' : formatAmount(project.contractValue)}
        </Typography>
      ),
    },
    {
      key: 'sites',
      header: 'Sites',
      align: 'right',
      cell: (project) =>
        siteCount(project.id) === 0 ? (
          <Typography variant="body2" color="text.secondary">
            None yet
          </Typography>
        ) : (
          <Typography variant="caption">{siteCount(project.id)}</Typography>
        ),
    },
    {
      key: 'status',
      header: 'Status',
      card: 'status',
      cell: (project) => (
        <Chip
          size="small"
          color={STATUS_COLOR[project.status]}
          label={STATUS_LABEL[project.status]}
        />
      ),
    },
    ...(canWrite
      ? [
          {
            key: 'actions',
            header: 'Actions',
            align: 'right' as const,
            card: 'actions' as const,
            cell: (project: AdminProject) => (
              <>
                <Button size="small" onClick={() => openEdit(project)}>
                  Edit
                </Button>
                <Button size="small" component={Link} to="/sites">
                  Sites
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
          <Typography variant="h1">Projects</Typography>
          <Typography color="text.secondary">
            The contracts you are running. Add the project first, then its sites.
          </Typography>
        </Stack>
        {canWrite && (
          <Button variant="contained" color="secondary" onClick={openNew}>
            Add a project
          </Button>
        )}
      </Stack>

      {projects.isLoading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      )}
      {projects.isError && <Alert severity="error">{apiErrorDetail(projects.error)}</Alert>}

      {projects.data && (
        <RecordTable
          columns={columns}
          rows={projects.data}
          rowKey={(project) => project.id}
          ariaLabel="Projects"
          empty={
            <Typography color="text.secondary">
              No projects yet. Add the contract you are working under, then add its sites and
              post an engineer and a supervisor to each.
            </Typography>
          }
        />
      )}

      <ProjectFormDialog open={formOpen} project={editing} onClose={() => setFormOpen(false)} />
    </Stack>
  );
}
