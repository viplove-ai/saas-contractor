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
  TableSortLabel,
  TextField,
  Typography,
} from '@mui/material';
import { FormControlLabel, Switch } from '@mui/material';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { useAuth } from '../auth/AuthContext';
import {
  useAdminSites,
  useDeleteProject,
  useProjectPortfolio,
  useProjects,
  useRestoreProject,
} from './api';
import { headlineAmount, summarise, type PortfolioBand, type PortfolioSummary } from './projectPortfolio';
import { formatQuotedPercent } from './projectFigures';
import { completionLabel } from './projectSchedule';
import {
  DEFAULT_SORT,
  sortProjects,
  type SortColumn,
  type SortDirection,
  type SortOrder,
} from './projectSort';
import { DeleteRecordDialog } from '../../shared/DeleteRecordDialog';
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
const NONE_DELETED = 'Nothing has been deleted. Projects only appear here when an administrator ' +
  'removes one, and they can be put back from here at any time.';

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
  const [deleting, setDeleting] = useState<AdminProject | null>(null);
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const [showDeleted, setShowDeleted] = useState(false);
  const [billingOnly, setBillingOnly] = useState(false);
  /*
    The order is the table's, not the server's — the list is one page of everything the org
    has, so a click reorders what is already loaded rather than asking for it again. It starts
    on the order the server sends so the first paint is not a re-sort of itself.
  */
  const [sort, setSort] = useState<SortOrder>(DEFAULT_SORT);

  const canWrite = hasPermission('project:write');
  const canDelete = hasPermission('project:delete');

  const projects = useProjects(search, status, showDeleted);
  /*
    The figures at the head of the page are the company's, not the filtered list's, so they
    come from their own unfiltered query. It is skipped on the deleted list, where they would
    be answering a question nobody standing there is asking.
  */
  const portfolio = useProjectPortfolio(!showDeleted);
  // The site list follows the same side of the line, so a deleted project's site count is
  // the sites that went down with it rather than a flat zero.
  const sites = useAdminSites('', showDeleted);
  const deleteProject = useDeleteProject();
  const restoreProject = useRestoreProject();
  const filtered = search !== '' || status !== '' || billingOnly;

  /*
    Narrowed here rather than on the server. Mode is one flag on a list the screen already
    holds, and adding a query parameter for it would mean a round trip to answer a question the
    page can answer itself — unlike deleted, which is genuinely a different set of rows.
  */
  const visibleProjects = sortProjects(
    (projects.data ?? []).filter((project) => !billingOnly || project.mode === 'BILLING_ONLY'),
    sort,
  );

  /*
    A second click on the column already sorted turns it around; a first click on any other
    starts it ascending — which for a date is the earliest first and for money the smallest,
    the way every table anybody has used before this one behaves.
  */
  const sortBy = (column: SortColumn) =>
    setSort((current) => ({
      column,
      direction:
        current.column === column && current.direction === 'asc' ? ('desc' as SortDirection) : ('asc' as SortDirection),
    }));

  const summary = summarise(portfolio.data?.content ?? [], portfolio.data?.totalElements);

  const sitesOf = (projectId: string) =>
    sites.data?.filter((site) => site.projectId === projectId) ?? [];
  const siteCount = (projectId: string): number => sitesOf(projectId).length;

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
        {canWrite && !showDeleted && (
          <Button variant="contained" color="secondary" onClick={openNew}>
            Add a project
          </Button>
        )}
      </Stack>

      {/*
        The order book in four figures. Above the filters because it is about the company
        rather than about what the boxes below it are asking for, and off the deleted list
        entirely — that is the other register, and what a removed contract was worth is not a
        total anybody is working to.
      */}
      {!showDeleted && portfolio.data && <PortfolioQuickView summary={summary} />}

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
        {/*
          Status has nothing to say about a deleted project — a deleted ACTIVE contract is
          not a thing anybody looks for — so the picker goes away rather than sitting there
          returning nothing.
        */}
        {!showDeleted && (
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
        )}
        {/*
          Only for someone who could put one back. A switch rather than another value in the
          status picker, because deleted is not a status a project moves through: it is the
          other list, and mixing the two is exactly what this screen exists to avoid.
        */}
        {/*
          Its own switch for the same reason deleted has one: billing-only is not a stage a
          project passes through, it is a different kind of record, and folding it into the
          status picker would mix two questions the screen exists to keep apart.
        */}
        <FormControlLabel
          control={
            <Switch
              checked={billingOnly}
              onChange={(event) => setBillingOnly(event.target.checked)}
            />
          }
          label="Billing only"
        />
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
          {visibleProjects.map((project) => (
            <ProjectCard
              key={project.id}
              project={project}
              siteCount={siteCount(project.id)}
              canWrite={canWrite}
              canDelete={canDelete}
              onEdit={() => openEdit(project)}
              onDelete={() => setDeleting(project)}
              onRestore={() => restoreProject.mutate(project.id)}
            />
          ))}
          {visibleProjects.length === 0 && (
            <Typography component="li" color="text.secondary">
              {showDeleted ? NONE_DELETED : filtered ? NOTHING_MATCHED : NO_PROJECTS_YET}
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
                {/*
                  A floor under the name column. The sort arrows took their width out of the one
                  column that flexes, and "Kausani Guest House Extension" over four lines makes
                  every row three rows tall — the same wrap the phone card exists to avoid.
                */}
                <SortableHeader column="code" sort={sort} onSort={sortBy} minWidth={180}>
                  Project
                </SortableHeader>
                {/*
                  The quote rather than the client department, which is on all but a handful
                  of these rows the same word repeated down the column. The percent is the one
                  fact about a contract that is different on every row and changes what every
                  rupee of it is worth, and the client is still what the search box matches on.
                */}
                <SortableHeader align="right" column="quotedPercent" sort={sort} onSort={sortBy}>
                  Quoted %
                </SortableHeader>
                <SortableHeader align="right" column="quotedCost" sort={sort} onSort={sortBy}>
                  Quoted value
                </SortableHeader>
                {/*
                  When it is due rather than how many sites it has. The site count answered a
                  question about setting the system up, which is asked once; the date is asked
                  every time the list is opened. What the count was there to catch — a project
                  nothing can be recorded against — is caught by the chip beside the status,
                  where it only appears when it is true.
                */}
                <SortableHeader align="right" column="completion" sort={sort} onSort={sortBy}>
                  Completion
                </SortableHeader>
                {/*
                  On the deleted list, why it went is the fact worth a column — and it is prose,
                  which nothing is gained by ordering, so that one header stays a plain label.
                */}
                {showDeleted ? (
                  <TableCell>Reason</TableCell>
                ) : (
                  <SortableHeader column="status" sort={sort} onSort={sortBy}>
                    Status
                  </SortableHeader>
                )}
                {canWrite && <TableCell align="right">Actions</TableCell>}
              </TableRow>
            </TableHead>
            <TableBody>
              {visibleProjects.map((project) => (
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
                  <TableCell align="right">
                    <Typography variant="caption">
                      {formatQuotedPercent(project.quotedPercent)}
                    </Typography>
                  </TableCell>
                  <TableCell align="right">
                    <Typography variant="caption">
                      {project.quotedCost == null ? '—' : formatAmount(project.quotedCost)}
                    </Typography>
                  </TableCell>
                  <TableCell align="right">
                    <CompletionCell project={project} stacked />
                  </TableCell>
                  <TableCell>
                    {showDeleted ? (
                      <Stack spacing={0.25}>
                        <Typography variant="body2">{project.deletedReason || '—'}</Typography>
                        <Typography variant="caption" color="text.secondary">
                          {formatDeletedAt(project.deletedAt)}
                        </Typography>
                      </Stack>
                    ) : (
                      <Stack direction="row" spacing={0.5} alignItems="center">
                        <Chip
                          size="small"
                          color={STATUS_COLOR[project.status]}
                          label={STATUS_LABEL[project.status]}
                        />
                        {/*
                          The half of the site count that was worth a column: a project with
                          no site is one nobody can record anything against, which is the state
                          this screen exists to get you out of. Two sites and three sites were
                          never a difference anybody acted on from here.
                        */}
                        {siteCount(project.id) === 0 && (
                          <Chip size="small" variant="outlined" color="warning" label="No sites" />
                        )}
                      </Stack>
                    )}
                  </TableCell>
                  {canWrite && (
                    <TableCell align="right">
                      <Stack direction="row" spacing={1} justifyContent="flex-end">
                        {showDeleted ? (
                          <Button
                            size="small"
                            onClick={() => restoreProject.mutate(project.id)}
                            disabled={restoreProject.isPending}
                          >
                            Restore
                          </Button>
                        ) : (
                          <>
                            <Button size="small" onClick={() => openEdit(project)}>
                              Edit
                            </Button>
                            {/*
                              Carries the project through. Arriving at Sites on "all
                              projects" after asking for one project's sites makes the
                              admin repeat the choice they just made.
                            */}
                            <Button
                              size="small"
                              component={Link}
                              to={`/sites?projectId=${project.id}`}
                            >
                              Sites
                            </Button>
                            {canDelete && (
                              <Button
                                size="small"
                                color="error"
                                onClick={() => setDeleting(project)}
                              >
                                Delete
                              </Button>
                            )}
                          </>
                        )}
                      </Stack>
                    </TableCell>
                  )}
                </TableRow>
              ))}
              {visibleProjects.length === 0 && (
                <TableRow>
                  <TableCell colSpan={canWrite ? 6 : 5}>
                    <Typography color="text.secondary" sx={{ py: 2 }}>
                      {showDeleted ? NONE_DELETED : filtered ? NOTHING_MATCHED : NO_PROJECTS_YET}
                    </Typography>
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </Paper>
      )}

      <ProjectFormDialog open={formOpen} project={editing} onClose={() => setFormOpen(false)} />

      {deleting && (
        <DeleteRecordDialog
          open
          kind="project"
          label={`${deleting.code} — ${deleting.name}`}
          cascade={sitesOf(deleting.id).map((site) => site.code)}
          onConfirm={(reason) =>
            deleteProject.mutateAsync({ id: deleting.id, reason })
          }
          onClose={() => setDeleting(null)}
        />
      )}
    </Stack>
  );
}

/** "4 March 2026" — the day is what an admin scanning the register is matching on. */
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
 * The order book at a glance: what is running, what is stopped, what has not started and what
 * is done, each as a count and a rupee figure.
 *
 * <p>Four bands and not five statuses, and money that is the quoted cost alone — see
 * {@link summarise} for why both of those are the way they are. The two notes underneath are
 * the load-bearing part: a total that quietly left rows out reads exactly like a complete
 * one, and the whole value of a headline is that somebody trusts it without checking.</p>
 */
function PortfolioQuickView({ summary }: { summary: PortfolioSummary }) {
  const tiles: { label: string; band: PortfolioBand; lead?: boolean }[] = [
    { label: 'Work in hand', band: summary.running, lead: true },
    { label: 'On hold', band: summary.onHold },
    { label: 'Yet to start', band: summary.planned },
    { label: 'Completed', band: summary.finished },
  ];
  return (
    <Paper
      component="section"
      aria-label="Order book"
      elevation={0}
      sx={{ p: 2, border: 1, borderColor: 'divider' }}
    >
      <Stack spacing={1.5}>
        <Box
          sx={{
            display: 'grid',
            // Two up on a phone, four across from a tablet. A single row of four on a 375px
            // screen puts each figure in 90px, which wraps every one of them.
            gridTemplateColumns: { xs: '1fr 1fr', sm: 'repeat(4, 1fr)' },
            gap: 2,
          }}
        >
          {tiles.map((tile) => (
            <Stack key={tile.label} spacing={0.25} sx={{ minWidth: 0 }}>
              <Typography variant="caption" color="text.secondary">
                {tile.label}
              </Typography>
              <Typography
                variant="h6"
                component="p"
                fontWeight={700}
                color={tile.lead ? 'secondary.main' : 'text.primary'}
              >
                {headlineAmount(tile.band.value)}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {tile.band.count === 1 ? '1 project' : `${tile.band.count} projects`}
              </Typography>
            </Stack>
          ))}
        </Box>
        {summary.all.unpriced > 0 && (
          <Typography variant="caption" color="text.secondary">
            {summary.all.unpriced === 1
              ? '1 project carries no quoted value: it is counted above but not totalled.'
              : `${summary.all.unpriced} projects carry no quoted value: they are counted above but not totalled.`}
          </Typography>
        )}
        {summary.uncounted > 0 && (
          <Typography variant="caption" color="warning.main">
            {`These figures cover the ${summary.all.count} most recent projects. ${summary.uncounted} older ones are not in them.`}
          </Typography>
        )}
      </Stack>
    </Paper>
  );
}

/**
 * A column header that sorts, and says so to a screen reader as well as to an eye.
 *
 * <p>`TableSortLabel` inside the cell rather than a click handler on the cell itself: the
 * arrow only appears on the column in force, `aria-sort` lands on the header where a reader
 * looks for it, and the whole thing is reachable from the keyboard without any of that being
 * written twice per column.</p>
 */
function SortableHeader({
  column,
  sort,
  onSort,
  align,
  minWidth,
  children,
}: {
  column: SortColumn;
  sort: SortOrder;
  onSort: (column: SortColumn) => void;
  align?: 'right' | undefined;
  minWidth?: number | undefined;
  children: React.ReactNode;
}) {
  const active = sort.column === column;
  return (
    <TableCell
      align={align ?? 'left'}
      sortDirection={active ? sort.direction : false}
      sx={{ whiteSpace: 'nowrap', minWidth }}
    >
      <TableSortLabel
        active={active}
        direction={active ? sort.direction : 'asc'}
        onClick={() => onSort(column)}
      >
        {children}
      </TableSortLabel>
    </TableCell>
  );
}

/**
 * When a contract is due, and how long that leaves, in one cell.
 *
 * <p>The countdown is dimmer than the date on a job that is running and coloured on one that
 * is not: a contract past its date is the single thing on this screen somebody would want to
 * be caught by, and the rest of the column is a date they are only reading.</p>
 */
function CompletionCell({ project, stacked }: { project: AdminProject; stacked?: boolean }) {
  // Read once per render rather than hoisted to a constant: a list left open across midnight
  // would otherwise go on counting to yesterday.
  const label = completionLabel(project, new Date());
  const note = label.note && (
    <Typography
      variant="caption"
      component={stacked ? 'div' : 'span'}
      color={label.late ? 'warning.main' : 'text.secondary'}
      // Kept whole either way: a column narrow enough to break inside the brackets leaves
      // "(152 days" over "late)", a phrase somebody has to reassemble before reading it.
      sx={{ whiteSpace: 'nowrap' }}
    >
      {stacked ? `(${label.note})` : ` (${label.note})`}
    </Typography>
  );
  /*
    In the table the countdown goes under the date rather than after it. Held on one line the
    column claims the width of the whole phrase — enough, with six columns, to push the last
    one off the edge on a laptop — and the row is already two lines tall because of the project
    name beside it, so the second line is free. The card has the width and reads as a sentence.
  */
  return (
    <Typography variant="caption" component={stacked ? 'div' : 'span'}>
      <Box component={stacked ? 'div' : 'span'} sx={{ whiteSpace: 'nowrap' }}>
        {label.date}
      </Box>
      {note}
    </Typography>
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
 * <p>The two halves of the money sit on one row as a pair: what the work pays, and the quote
 * that got it there. The site count keeps its own line and its words — it is the one that
 * decides what you do next, since a project with no sites can have nothing recorded against
 * it, and a bare number would not say that.</p>
 */
function ProjectCard({
  project,
  siteCount,
  canWrite,
  canDelete,
  onEdit,
  onDelete,
  onRestore,
}: {
  project: AdminProject;
  siteCount: number;
  canWrite: boolean;
  canDelete: boolean;
  onEdit: () => void;
  onDelete: () => void;
  onRestore: () => void;
}) {
  const deleted = project.deletedAt !== undefined;
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
          <Stack direction="row" spacing={0.5} alignItems="center">
            {/*
              Beside the status, not instead of it: a billing-only tender still moves through
              PLANNED and ACTIVE like any other, and what it says is how much of the system is
              running against it — no muster, no store, no daily report.
            */}
            {project.mode === 'BILLING_ONLY' && (
              <Chip size="small" variant="outlined" label="Billing only" />
            )}
            <Chip
              size="small"
              color={deleted ? 'error' : STATUS_COLOR[project.status]}
              variant={deleted ? 'outlined' : 'filled'}
              label={deleted ? 'Deleted' : STATUS_LABEL[project.status]}
            />
          </Stack>
        </Stack>

        <Stack direction="row" spacing={2} justifyContent="space-between" alignItems="baseline">
          <Stack spacing={0.25} sx={{ minWidth: 0 }}>
            <Typography variant="caption" color="text.secondary">
              Quoted value
            </Typography>
            <Typography variant="body2" fontWeight={600}>
              {project.quotedCost == null ? '—' : formatAmount(project.quotedCost)}
            </Typography>
          </Stack>
          <Stack spacing={0.25} alignItems="flex-end">
            <Typography variant="caption" color="text.secondary">
              Quoted %
            </Typography>
            <Typography variant="body2">{formatQuotedPercent(project.quotedPercent)}</Typography>
          </Stack>
        </Stack>

        <Stack direction="row" spacing={1} alignItems="baseline" justifyContent="space-between">
          <Typography variant="body2" color="text.secondary">
            Due <CompletionCell project={project} />
          </Typography>
          {/* Only when it is true — see the table's chip. */}
          {siteCount === 0 && (
            <Typography variant="body2" color="warning.main">
              No sites yet
            </Typography>
          )}
        </Stack>

        {deleted && (
          <Typography variant="body2" color="text.secondary">
            Deleted {formatDeletedAt(project.deletedAt)}
            {project.deletedReason ? ` — ${project.deletedReason}` : ''}
          </Typography>
        )}

        {canWrite && (
          <Stack direction="row" spacing={1}>
            {/* 48px: the app's floor for anything meant to be hit with a glove on. */}
            {deleted ? (
              <Button
                size="small"
                variant="outlined"
                onClick={onRestore}
                sx={{ minHeight: 48, flex: 1 }}
              >
                Restore
              </Button>
            ) : (
              <>
                <Button
                  size="small"
                  variant="outlined"
                  onClick={onEdit}
                  sx={{ minHeight: 48, flex: 1 }}
                >
                  Edit
                </Button>
                <Button
                  size="small"
                  variant="outlined"
                  component={Link}
                  to={`/sites?projectId=${project.id}`}
                  sx={{ minHeight: 48, flex: 1 }}
                >
                  Sites
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
