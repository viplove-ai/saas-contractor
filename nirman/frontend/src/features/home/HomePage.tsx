import { Grid, Paper, Stack, Typography } from '@mui/material';
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

interface Tile {
  title: string;
  /** Set once the screen exists; until then the tile shows which phase brings it. */
  to?: string;
  /** The permission that makes the tile worth showing. The backend re-checks regardless. */
  permission?: string;
  phase?: string;
}

const TILES: Tile[] = [
  { title: 'Mark attendance', to: '/attendance/mark', permission: 'attendance:create' },
  { title: 'Verify attendance', to: '/attendance/verify', permission: 'attendance:verify' },
  { title: 'Receive material', to: '/inventory/receive', permission: 'inventory:receive' },
  { title: 'Issue material', to: '/inventory/issue', permission: 'inventory:issue' },
  { title: 'Stock', to: '/inventory/stock', permission: 'inventory:read' },
  { title: 'Add expense', to: '/expenses/new', permission: 'expense:create' },
  { title: 'Approvals and payments', to: '/approvals', permission: 'expense:read' },
  { title: 'Workers', to: '/workers', permission: 'worker:read' },
  { title: 'Members', to: '/users', permission: 'user:read' },
  { title: 'Projects', to: '/projects', permission: 'project:write' },
  { title: 'Sites', to: '/sites', permission: 'site:write' },
  { title: 'Your account', to: '/profile' },
  { title: 'Submit DPR', phase: 'Phase 6' },
  { title: 'Reports', phase: 'Phase 6' },
];

export function HomePage() {
  const { user, hasPermission } = useAuth();

  // A tile the role cannot use is not shown at all. A greyed-out row of other people's
  // screens tells a supervisor nothing they can act on, and on a phone it pushes the
  // handful of tiles they do use below the fold. Tiles a later phase brings stay, since
  // "not built yet" is a different thing from "not yours".
  const visible = TILES.filter((tile) => !tile.permission || hasPermission(tile.permission));

  return (
    <Stack spacing={3}>
      <Stack spacing={0.5}>
        <Typography variant="h1">Nirman</Typography>
        <Typography color="text.secondary">
          Signed in as {user?.fullName} ({user?.roles.join(', ')})
          {user && !user.allSites && ` — ${user.siteIds.length} site(s) assigned`}
        </Typography>
      </Stack>

      <Grid container spacing={2}>
        {visible.map((tile) => {
          const available = Boolean(tile.to);
          return (
            <Grid item xs={6} sm={4} key={tile.title}>
              <Paper
                elevation={0}
                {...(available ? { component: Link, to: tile.to! } : {})}
                sx={{
                  p: 2,
                  minHeight: 96,
                  border: 1,
                  borderColor: available ? 'secondary.main' : 'divider',
                  display: 'flex',
                  flexDirection: 'column',
                  justifyContent: 'space-between',
                  textDecoration: 'none',
                  color: 'inherit',
                  opacity: available ? 1 : 0.6,
                }}
              >
                <Typography fontWeight={600}>{tile.title}</Typography>
                <Typography variant="body2" color="text.secondary">
                  {available ? 'Open' : (tile.phase ?? 'Coming soon')}
                </Typography>
              </Paper>
            </Grid>
          );
        })}
      </Grid>
    </Stack>
  );
}
