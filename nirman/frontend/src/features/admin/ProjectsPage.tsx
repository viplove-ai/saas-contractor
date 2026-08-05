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
import { Link } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
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
        <Paper elevation={0} sx={{ border: 1, borderColor: 'divider', overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Project</TableCell>
                <TableCell>Client</TableCell>
                <TableCell align="right">Contract value</TableCell>
                <TableCell align="right">Sites</TableCell>
                <TableCell>Status</TableCell>
                {canWrite && <TableCell align="right">Actions</TableCell>}
              </TableRow>
            </TableHead>
            <TableBody>
              {projects.data.map((project) => (
                <TableRow key={project.id} hover>
                  <TableCell>
                    <Typography fontWeight={600}>{project.code}</Typography>
                    <Typography variant="body2" color="text.secondary">
                      {project.name}
                    </Typography>
                  </TableCell>
                  <TableCell>{project.clientDepartment || '—'}</TableCell>
                  <TableCell align="right">
                    <Typography variant="caption">
                      {project.contractValue == null ? '—' : formatAmount(project.contractValue)}
                    </Typography>
                  </TableCell>
                  <TableCell align="right">
                    {siteCount(project.id) === 0 ? (
                      <Typography variant="body2" color="text.secondary">
                        None yet
                      </Typography>
                    ) : (
                      <Typography variant="caption">{siteCount(project.id)}</Typography>
                    )}
                  </TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      color={STATUS_COLOR[project.status]}
                      label={STATUS_LABEL[project.status]}
                    />
                  </TableCell>
                  {canWrite && (
                    <TableCell align="right">
                      <Stack direction="row" spacing={1} justifyContent="flex-end">
                        <Button size="small" onClick={() => openEdit(project)}>
                          Edit
                        </Button>
                        <Button size="small" component={Link} to="/sites">
                          Sites
                        </Button>
                      </Stack>
                    </TableCell>
                  )}
                </TableRow>
              ))}
              {projects.data.length === 0 && (
                <TableRow>
                  <TableCell colSpan={canWrite ? 6 : 5}>
                    <Typography color="text.secondary" sx={{ py: 2 }}>
                      No projects yet. Add the contract you are working under, then add its
                      sites and post an engineer and a supervisor to each.
                    </Typography>
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </Paper>
      )}

      <ProjectFormDialog open={formOpen} project={editing} onClose={() => setFormOpen(false)} />
    </Stack>
  );
}
