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

/** Life of a contract, not the alphabet: the filter reads as the order work moves through. */
const STATUS_ORDER: ProjectStatus[] = ['PLANNED', 'ACTIVE', 'ON_HOLD', 'COMPLETED', 'CLOSED'];

/*
 * An empty list means two different things now, and telling somebody to add their first
 * contract when they have twelve and mistyped a code would be the wrong instruction.
 */
const NO_PROJECTS_YET =
  'No projects yet. Add the contract you are working under, then add its sites and post an ' +
  'engineer and a supervisor to each.';
const NOTHING_MATCHED = 'No project matches that. Clear the search or the status to see them all.';

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
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');

  const projects = useProjects(search, status);
  const sites = useAdminSites('');
  const canWrite = hasPermission('project:write');
  const filtered = search !== '' || status !== '';

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

      {/*
        Two filters and no more. Code, name and client are one box because that is how a
        project is asked for out loud — "the Kausani one", "the CPWD Bageshwar job" — and
        which of the three fields it matched is of no interest. Status is the second because
        a company running for a few years accumulates closed contracts that are never the
        one being looked for. Both are the server's answer, not a cut of the loaded page.
      */}
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField
          label="Search by code, name or client"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          // flex alone gives a zero basis and collapses the box to its border.
          sx={{ flex: 1, minWidth: 280 }}
        />
        <TextField
          select
          label="Status"
          value={status}
          onChange={(event) => setStatus(event.target.value)}
          sx={{ minWidth: 200 }}
        >
          <MenuItem value="">Any status</MenuItem>
          {STATUS_ORDER.map((code) => (
            <MenuItem key={code} value={code}>
              {STATUS_LABEL[code]}
            </MenuItem>
          ))}
        </TextField>
      </Stack>

      {projects.isLoading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      )}
      {projects.isError && <Alert severity="error">{apiErrorDetail(projects.error)}</Alert>}

      {/*
        Phone: a card each. The table has six columns and the last two are Status and
        Actions, so on a 375px screen Edit and Sites sat past the right edge — reachable
        only by scrolling a table sideways, which nobody thinks to try. The card keeps the
        same six facts and drops the horizontal scroll.
      */}
      {projects.data && (
        <Stack
          component="ul"
          aria-label="Projects"
          spacing={2}
          sx={{ display: { xs: 'flex', md: 'none' }, listStyle: 'none', m: 0, p: 0 }}
        >
          {projects.data.map((project) => (
            <ProjectCard
              key={project.id}
              project={project}
              siteCount={siteCount(project.id)}
              canWrite={canWrite}
              onEdit={() => openEdit(project)}
            />
          ))}
          {projects.data.length === 0 && (
            <Typography component="li" color="text.secondary">
              {filtered ? NOTHING_MATCHED : NO_PROJECTS_YET}
            </Typography>
          )}
        </Stack>
      )}

      {projects.data && (
        <Paper
          elevation={0}
          sx={{ display: { xs: 'none', md: 'block' }, border: 1, borderColor: 'divider', overflowX: 'auto' }}
        >
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
                    {/*
                      The link is on the name rather than the whole row: the row carries its
                      own buttons, and a clickable row wrapped around them makes it a guess
                      which one a click lands on.
                    */}
                    <Link
                      to={`/projects/${project.id}`}
                      style={{ textDecoration: 'none', color: 'inherit' }}
                    >
                      <Typography fontWeight={600} sx={{ '&:hover': { textDecoration: 'underline' } }}>
                        {project.code}
                      </Typography>
                      <Typography variant="body2" color="text.secondary">
                        {project.name}
                      </Typography>
                    </Link>
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
                      {filtered ? NOTHING_MATCHED : NO_PROJECTS_YET}
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

/**
 * One project on a phone.
 *
 * <p>The code and the status go on the top line together, because those are what somebody
 * scanning the list is matching against. The name is given a single line and cut with an
 * ellipsis rather than allowed to wrap: "Kausani Guest House Extension" over four lines
 * pushed every card past a screen height and made the list unscannable, and the full name
 * is one tap away on the detail screen.</p>
 *
 * <p>Contract value and the site count sit on one row as a pair. The site count is the one
 * that decides what you do next — a project with no sites can have nothing recorded against
 * it — so it keeps the words rather than being reduced to a bare number.</p>
 */
function ProjectCard({
  project,
  siteCount,
  canWrite,
  onEdit,
}: {
  project: AdminProject;
  siteCount: number;
  canWrite: boolean;
  onEdit: () => void;
}) {
  return (
    <Paper component="li" elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
      <Stack spacing={1.5}>
        <Stack direction="row" alignItems="flex-start" justifyContent="space-between" spacing={1}>
          <Box
            component={Link}
            to={`/projects/${project.id}`}
            sx={{ textDecoration: 'none', color: 'inherit', minWidth: 0, flex: 1 }}
          >
            <Typography fontWeight={700}>{project.code}</Typography>
            {/* noWrap needs the parent to be allowed to shrink, hence minWidth: 0 above. */}
            <Typography variant="body2" color="text.secondary" noWrap>
              {project.name}
            </Typography>
          </Box>
          <Chip
            size="small"
            color={STATUS_COLOR[project.status]}
            label={STATUS_LABEL[project.status]}
          />
        </Stack>

        <Stack direction="row" spacing={2} justifyContent="space-between" alignItems="baseline">
          <Stack spacing={0.25} sx={{ minWidth: 0 }}>
            <Typography variant="caption" color="text.secondary">
              Contract value
            </Typography>
            <Typography variant="body2" fontWeight={600}>
              {project.contractValue == null ? '—' : formatAmount(project.contractValue)}
            </Typography>
          </Stack>
          <Stack spacing={0.25} alignItems="flex-end">
            <Typography variant="caption" color="text.secondary">
              Client
            </Typography>
            <Typography variant="body2">{project.clientDepartment || '—'}</Typography>
          </Stack>
        </Stack>

        <Typography variant="body2" color="text.secondary">
          {siteCount === 0 ? 'No sites yet' : `${siteCount} site${siteCount === 1 ? '' : 's'}`}
        </Typography>

        {canWrite && (
          <Stack direction="row" spacing={1}>
            {/* 48px: the app's floor for anything meant to be hit with a glove on. */}
            <Button size="small" variant="outlined" onClick={onEdit} sx={{ minHeight: 48, flex: 1 }}>
              Edit
            </Button>
            <Button
              size="small"
              variant="outlined"
              component={Link}
              to="/sites"
              sx={{ minHeight: 48, flex: 1 }}
            >
              Sites
            </Button>
          </Stack>
        )}
      </Stack>
    </Paper>
  );
}
