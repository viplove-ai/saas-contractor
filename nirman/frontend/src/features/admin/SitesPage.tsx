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
import { FormControlLabel, Switch } from '@mui/material';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { useAuth } from '../auth/AuthContext';
import { useAdminSites, useDeleteSite, useProjects, useRestoreSite, useUsers } from './api';
import { DeleteRecordDialog } from '../../shared/DeleteRecordDialog';
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
  /**
   * The project filter lives in the URL rather than in state alone.
   *
   * <p>Coming here from a project's Sites button is coming here to look at that project, so
   * the picker opens on it instead of making the admin pick again. Keeping it in the query
   * string rather than in a prop means the narrowed screen is a link — it survives a reload,
   * comes back on the browser's Back button, and can be sent to somebody.</p>
   */
  const [searchParams, setSearchParams] = useSearchParams();
  const projectId = searchParams.get('projectId') ?? '';
  const setProjectId = (id: string) => {
    // replace, not push: re-picking is correcting the same question, and a history entry
    // per selection would make Back walk through every choice instead of leaving the screen.
    setSearchParams(id === '' ? {} : { projectId: id }, { replace: true });
  };
  const [editing, setEditing] = useState<AdminSite | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [deleting, setDeleting] = useState<AdminSite | null>(null);
  const [showDeleted, setShowDeleted] = useState(false);

  const projects = useProjects();
  // A deleted site belongs to a live project when it was removed on its own, and to a
  // deleted one when it went down with the cascade — so naming the project on the deleted
  // list needs both registers. Only fetched for someone who can see that list at all.
  const deletedProjects = useProjects('', '', true, hasPermission('project:delete'));
  const sites = useAdminSites(projectId, showDeleted);
  // Both lists at once: a site names any number of each, and the table has to turn those
  // ids into names without a request per row.
  const engineers = useUsers('', 'ENGINEER');
  const supervisors = useUsers('', 'SUPERVISOR');
  const deleteSite = useDeleteSite();
  const restoreSite = useRestoreSite();
  const canWrite = hasPermission('site:write');
  const canDelete = hasPermission('site:delete');

  /**
   * The names under one post. A site may carry several of each (see SiteStaff), and the
   * register has to show all of them: a cell that named the first and stopped would read as
   * the site having one supervisor, which is the thing the column used to enforce and no
   * longer does.
   */
  const staffNames = (ids: string[], pool: 'engineer' | 'supervisor'): string[] => {
    const source = pool === 'engineer' ? engineers.data?.content : supervisors.data?.content;
    return ids.map((id) => source?.find((member) => member.id === id)?.fullName ?? '—');
  };

  const projectName = (id: string): string => {
    const project = [...(projects.data ?? []), ...(deletedProjects.data ?? [])].find(
      (candidate) => candidate.id === id,
    );
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
            Each site's engineers and supervisors — as many of each as it has. Posting
            someone here is what lets them work on it, and on nothing else.
          </Typography>
        </Stack>
        {canWrite && !showDeleted && (
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

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }}>
        <TextField
          select
          label="Project"
          value={projectId}
          onChange={(event) => setProjectId(event.target.value)}
          sx={{ maxWidth: 320, flex: 1 }}
        >
          <MenuItem value="">All projects</MenuItem>
          {(projects.data ?? []).map((project) => (
            <MenuItem key={project.id} value={project.id}>
              {project.code} — {project.name}
            </MenuItem>
          ))}
        </TextField>
        {canDelete && (
          <FormControlLabel
            control={
              <Switch
                checked={showDeleted}
                onChange={(event) => setShowDeleted(event.target.checked)}
              />
            }
            label="Deleted"
          />
        )}
      </Stack>

      {sites.isLoading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      )}
      {sites.isError && <Alert severity="error">{apiErrorDetail(sites.error)}</Alert>}

      {/*
        Phone: a card each, the same answer the projects register already gives. This table
        has seven columns and the last of them is Actions, so on a 375px screen Edit and
        Delete sat off the right edge — reachable only by scrolling a table sideways, which
        nobody thinks to try. The card keeps every fact the row carries.
      */}
      {sites.data && (
        <Stack
          component="ul"
          aria-label="Sites"
          spacing={2}
          sx={{ display: { xs: 'flex', md: 'none' }, listStyle: 'none', m: 0, p: 0 }}
        >
          {sites.data.map((site) => (
            <SiteCard
              key={site.id}
              site={site}
              projectCode={projectName(site.projectId)}
              engineers={staffNames(site.siteEngineerIds, 'engineer')}
              supervisors={staffNames(site.supervisorIds, 'supervisor')}
              canWrite={canWrite}
              canDelete={canDelete}
              deletedView={showDeleted}
              onEdit={() => openEdit(site)}
              onDelete={() => setDeleting(site)}
              onRestore={() => restoreSite.mutate(site.id)}
            />
          ))}
          {sites.data.length === 0 && (
            <Typography component="li" color="text.secondary">
              {showDeleted
                ? 'No sites have been deleted. One removed here can always be put back.'
                : 'No sites yet.'}
            </Typography>
          )}
        </Stack>
      )}

      {sites.data && (
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
                <TableCell>Site</TableCell>
                <TableCell>Project</TableCell>
                <TableCell>Site engineers</TableCell>
                <TableCell>Supervisors</TableCell>
                <TableCell>Shift</TableCell>
                <TableCell>{showDeleted ? 'Reason' : 'Status'}</TableCell>
                {canWrite && <TableCell align="right">Actions</TableCell>}
              </TableRow>
            </TableHead>
            <TableBody>
              {sites.data.map((site) => (
                <TableRow key={site.id} hover>
                  <TableCell>
                    <Typography fontWeight={600}>{site.code}</Typography>
                    <Typography variant="body2" color="text.secondary">
                      {site.name}
                    </Typography>
                  </TableCell>
                  <TableCell>{projectName(site.projectId)}</TableCell>
                  <TableCell>
                    <StaffList names={staffNames(site.siteEngineerIds, 'engineer')} />
                  </TableCell>
                  <TableCell>
                    <StaffList names={staffNames(site.supervisorIds, 'supervisor')} />
                  </TableCell>
                  <TableCell>
                    <Typography variant="caption">{site.standardShiftHours} h</Typography>
                  </TableCell>
                  <TableCell>
                    {showDeleted ? (
                      <Stack spacing={0.25}>
                        <Typography variant="body2">{site.deletedReason || '—'}</Typography>
                        <Typography variant="caption" color="text.secondary">
                          {formatDeletedAt(site.deletedAt)}
                        </Typography>
                      </Stack>
                    ) : (
                      <Chip size="small" color={STATUS_COLOR[site.status]} label={site.status} />
                    )}
                  </TableCell>
                  {canWrite && (
                    <TableCell align="right">
                      {showDeleted ? (
                        <Button
                          size="small"
                          onClick={() => restoreSite.mutate(site.id)}
                          disabled={restoreSite.isPending}
                        >
                          Restore
                        </Button>
                      ) : (
                        <Stack direction="row" spacing={1} justifyContent="flex-end">
                          <Button size="small" onClick={() => openEdit(site)}>
                            Edit
                          </Button>
                          {canDelete && (
                            <Button size="small" color="error" onClick={() => setDeleting(site)}>
                              Delete
                            </Button>
                          )}
                        </Stack>
                      )}
                    </TableCell>
                  )}
                </TableRow>
              ))}
              {sites.data.length === 0 && (
                <TableRow>
                  <TableCell colSpan={canWrite ? 7 : 6}>
                    <Typography color="text.secondary" sx={{ py: 2 }}>
                      {showDeleted ? (
                        'No sites have been deleted. One removed here can always be put back.'
                      ) : (
                        <>
                          No sites yet.{' '}
                          {projects.data?.length === 0 && (
                            <>
                              A site belongs to a project, and there are none yet —{' '}
                              <Link to="/projects">add a project first</Link>.
                            </>
                          )}
                        </>
                      )}
                    </Typography>
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </Paper>
      )}

      <SiteFormDialog open={formOpen} site={editing} onClose={() => setFormOpen(false)} />

      {deleting && (
        <DeleteRecordDialog
          open
          kind="site"
          label={`${deleting.code} — ${deleting.name}`}
          onConfirm={(reason) => deleteSite.mutateAsync({ id: deleting.id, reason })}
          onClose={() => setDeleting(null)}
        />
      )}
    </Stack>
  );
}

/**
 * One site on a phone, built to the projects register's card.
 *
 * <p>The code and the status share the top line because those are what somebody scanning
 * the list matches on, and the name is cut with an ellipsis rather than wrapped, for the
 * reason the project card gives: four lines of name per card makes the list unscannable.</p>
 *
 * <p>The two staff slots are the reason this screen exists at all — posting somebody to a
 * site is what lets them work on it — so they get a labelled row of their own rather than
 * being folded in with the shift. "Not posted" is a finding, not a blank.</p>
 */
function SiteCard({
  site,
  projectCode,
  engineers,
  supervisors,
  canWrite,
  canDelete,
  deletedView,
  onEdit,
  onDelete,
  onRestore,
}: {
  site: AdminSite;
  projectCode: string;
  engineers: string[];
  supervisors: string[];
  canWrite: boolean;
  canDelete: boolean;
  deletedView: boolean;
  onEdit: () => void;
  onDelete: () => void;
  onRestore: () => void;
}) {
  return (
    <Paper component="li" elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
      <Stack spacing={1.5}>
        <Stack direction="row" alignItems="flex-start" justifyContent="space-between" spacing={1}>
          <Box sx={{ minWidth: 0, flex: 1 }}>
            <Typography fontWeight={700}>{site.code}</Typography>
            {/* noWrap needs the parent to be allowed to shrink, hence minWidth: 0 above. */}
            <Typography variant="body2" color="text.secondary" noWrap>
              {site.name}
            </Typography>
          </Box>
          <Chip
            size="small"
            color={deletedView ? 'error' : STATUS_COLOR[site.status]}
            variant={deletedView ? 'outlined' : 'filled'}
            label={deletedView ? 'Deleted' : site.status}
          />
        </Stack>

        {/*
          Down the card rather than across it. Two names side by side under two headings fit
          when there was one of each; with three supervisors the right-hand column pushed
          into the left and both truncated. Stacked, the list grows downwards, which is the
          direction a card has room in.
        */}
        <Stack direction="row" spacing={2} alignItems="flex-start">
          <Stack spacing={0.25} sx={{ flex: 1, minWidth: 0 }}>
            <Typography variant="caption" color="text.secondary">
              {label('Site engineer', engineers.length)}
            </Typography>
            <StaffList names={engineers} />
          </Stack>
          <Stack spacing={0.25} sx={{ flex: 1, minWidth: 0 }}>
            <Typography variant="caption" color="text.secondary">
              {label('Supervisor', supervisors.length)}
            </Typography>
            <StaffList names={supervisors} />
          </Stack>
        </Stack>

        <Typography variant="body2" color="text.secondary">
          {projectCode} · {site.standardShiftHours} h shift
        </Typography>

        {deletedView && (
          <Typography variant="body2" color="text.secondary">
            Deleted {formatDeletedAt(site.deletedAt)}
            {site.deletedReason ? ` — ${site.deletedReason}` : ''}
          </Typography>
        )}

        {canWrite && (
          <Stack direction="row" spacing={1}>
            {/* 48px: the app's floor for anything meant to be hit with a glove on. */}
            {deletedView ? (
              <Button size="small" variant="outlined" onClick={onRestore} sx={{ minHeight: 48, flex: 1 }}>
                Restore
              </Button>
            ) : (
              <>
                <Button size="small" variant="outlined" onClick={onEdit} sx={{ minHeight: 48, flex: 1 }}>
                  Edit
                </Button>
                {canDelete && (
                  <Button
                    size="small"
                    variant="outlined"
                    color="error"
                    onClick={onDelete}
                    sx={{ minHeight: 48, flex: 1 }}
                  >
                    Delete
                  </Button>
                )}
              </>
            )}
          </Stack>
        )}
      </Stack>
    </Paper>
  );
}

/** "4 Mar 2026" — the day is what an admin scanning the register is matching on. */
function formatDeletedAt(value: string | undefined): string {
  if (!value) {
    return '';
  }
  return new Date(value).toLocaleDateString('en-IN', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
}

/**
 * The people holding one post on a site, numbered and one to a line.
 *
 * <p>Comma-separated was fine while the register held one name each. It stopped being fine
 * the moment a site could carry three: "Vivek Aggarwal, Ramesh Negi, Deepak Joshi" reads as
 * one long string, and the question the column is actually answering — how many people are
 * watching this site — has to be counted out of it by eye. Numbering answers it before the
 * names are read.</p>
 */
function StaffList({ names }: { names: string[] }) {
  if (names.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary">
        Not posted
      </Typography>
    );
  }
  // One name keeps its number off: "1. Uttam Rana" on a site with one engineer is a list
  // implying a second entry that does not exist.
  if (names.length === 1) {
    return <Typography variant="body2">{names[0]}</Typography>;
  }
  return (
    <Stack component="ol" spacing={0.25} sx={{ listStyle: 'none', m: 0, p: 0 }}>
      {names.map((name, index) => (
        <Typography component="li" variant="body2" key={`${name}-${index}`}>
          <Box component="span" sx={{ color: 'text.secondary', mr: 0.75 }}>
            {index + 1}.
          </Box>
          {name}
        </Typography>
      ))}
    </Stack>
  );
}

/** "Supervisors (3)" once there is more than one, and the bare noun when there is not. */
function label(noun: string, count: number): string {
  return count > 1 ? `${noun}s (${count})` : noun;
}
