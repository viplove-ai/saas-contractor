import { Box, Paper, Stack, Typography } from '@mui/material';
import { Link } from 'react-router-dom';
import { edgeRadius, marginNote } from '../../app/sketch';
import { tokens } from '../../app/theme';
import { useAuth } from '../auth/AuthContext';

/**
 * Every screen this account can reach, grouped by the act it belongs to.
 *
 * <p>It is no longer the landing screen — /today is — and that changed what it is for. It was
 * doing two jobs at once: telling a supervisor what to do next (badly, because eighteen equal
 * tiles rank nothing) and being the index of the product (well). Today does the first job. This
 * is the index, reached from <b>More</b> in the bottom bar, and an index is allowed to be long.</p>
 *
 * <p>The GROUPS table below is unchanged from the version that shipped, including its reasoning:
 * grouping is by act rather than by module, the order is fixed rather than per-role, and a group
 * whose tiles are all filtered out disappears with its heading.</p>
 */
interface Tile {
  title: string;
  to?: string;
  permission?: string;
  phase?: string;
}

interface Group {
  title: string;
  hint: string;
  tiles: Tile[];
}

const GROUPS: Group[] = [
  {
    title: 'Today at site',
    hint: 'What gets entered as the day happens',
    tiles: [
      { title: 'Mark attendance', to: '/attendance/mark', permission: 'attendance:create' },
      { title: 'Receive material', to: '/inventory/receive', permission: 'inventory:receive' },
      { title: 'Issue material', to: '/inventory/issue', permission: 'inventory:issue' },
      { title: 'Add expense', to: '/expenses/new', permission: 'expense:create' },
      { title: 'Daily report', to: '/dpr/new', permission: 'dpr:draft' },
    ],
  },
  {
    title: 'Check and sign off',
    hint: 'Somebody else entered it and it is waiting on you',
    tiles: [
      { title: 'Verify attendance', to: '/attendance/verify', permission: 'attendance:verify' },
      { title: 'Verify reports', to: '/dprs', permission: 'dpr:verify' },
      { title: 'Approvals and payments', to: '/approvals', permission: 'expense:read' },
    ],
  },
  {
    title: 'Look something up',
    hint: 'The registers, as they stand right now',
    tiles: [
      { title: 'Stock', to: '/inventory/stock', permission: 'inventory:read' },
      { title: 'Workers', to: '/workers', permission: 'worker:read' },
    ],
  },
  {
    title: 'How it is going',
    hint: 'Cost, progress and what the records are missing',
    tiles: [
      { title: 'Company dashboard', to: '/dashboard', permission: 'dashboard:company' },
      { title: 'Site dashboard', to: '/dashboard/site', permission: 'dashboard:site' },
      { title: 'Data quality', to: '/dashboard/quality', permission: 'dashboard:dataquality' },
    ],
  },
  {
    title: 'Set up',
    hint: 'Who works here and what they work on',
    tiles: [
      { title: 'Members', to: '/users', permission: 'user:read' },
      { title: 'Projects', to: '/projects', permission: 'project:write' },
      { title: 'Sites', to: '/sites', permission: 'site:write' },
    ],
  },
  {
    title: 'This device',
    hint: 'Records still waiting here, and your own account',
    tiles: [
      { title: 'Not sent yet', to: '/sync' },
      { title: 'Your account', to: '/profile' },
    ],
  },
];

export function HomePage() {
  const { user, hasPermission } = useAuth();

  const groups = GROUPS.map((group) => ({
    ...group,
    tiles: group.tiles.filter((tile) => !tile.permission || hasPermission(tile.permission)),
  })).filter((group) => group.tiles.length > 0);

  return (
    <Stack spacing={4} sx={{ pb: 4 }}>
      <Box>
        <Typography variant="h1">All screens</Typography>
        <Typography sx={{ ...marginNote, mt: 0.5 }}>
          {user?.fullName} · {user?.roles.join(', ')}
          {user && !user.allSites && ` · ${user.siteIds.length} site(s) assigned`}
        </Typography>
      </Box>

      {groups.map((group, groupIndex) => (
        <Stack key={group.title} spacing={1.5} component="section">
          <Box>
            <Typography variant="h2" sx={{ fontSize: '1.35rem' }}>
              {group.title}
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.25 }}>
              {group.hint}
            </Typography>
          </Box>

          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr 1fr', sm: 'repeat(3, 1fr)' },
              gap: 1.5,
            }}
          >
            {group.tiles.map((tile, tileIndex) => {
              const available = Boolean(tile.to);
              return (
                <Paper
                  key={tile.title}
                  variant="outlined"
                  {...(available ? { component: Link, to: tile.to! } : {})}
                  sx={{
                    p: 2,
                    minHeight: 72,
                    height: '100%',
                    display: 'flex',
                    flexDirection: 'column',
                    justifyContent: 'center',
                    textDecoration: 'none',
                    color: 'inherit',
                    // Six edge variants, indexed by position, so a grid of sixteen tiles reads
                    // as sixteen drawn boxes rather than one box stamped sixteen times.
                    borderRadius: edgeRadius(groupIndex * 3 + tileIndex),
                    borderColor: available ? tokens.ink : tokens.line,
                    boxShadow: available ? '2px 3px 0 rgba(20,24,29,0.10)' : 'none',
                    opacity: available ? 1 : 0.6,
                  }}
                >
                  <Typography fontWeight={600}>{tile.title}</Typography>
                  {!available && (
                    <Typography variant="body2" color="text.secondary">
                      {tile.phase ?? 'Coming soon'}
                    </Typography>
                  )}
                </Paper>
              );
            })}
          </Box>
        </Stack>
      ))}
    </Stack>
  );
}
