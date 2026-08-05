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

interface Group {
  title: string;
  /** One line saying what the group is for, so the heading is not just a word. */
  hint: string;
  tiles: Tile[];
}

/**
 * The tiles, grouped by what somebody came to the screen to do.
 *
 * <p>They were a flat list of eighteen for six phases, which was fine at four and stopped
 * being fine somewhere around ten: "Verify attendance" sat next to "Receive material" for no
 * reason except that labour was built before inventory, and the order on the screen was a
 * record of the build order rather than of anything a user thinks about. Reading it meant
 * scanning every title every time.</p>
 *
 * <p>The grouping is by <b>act</b>, not by module, and that is the decision worth defending.
 * Attendance, material and expenses are three different modules and one job — the things a
 * supervisor enters as the day happens — while marking attendance and verifying it are the
 * same module and two entirely different jobs, done by different people at different times
 * of day. A module-shaped menu would have put the second pair together and split the first
 * three, which is exactly backwards from how the work is done.</p>
 *
 * <p>The order is fixed rather than per-role, because the permission filter already does the
 * work: a supervisor has no sign-off tiles and no setup tiles, so entry is his first group
 * and nearly his only one, and an admin who sees all six is at a desk and can afford to
 * read. Sorting the groups by role as well would make the screen move under people who
 * change hats, and a menu whose contents shift is a menu nobody learns.</p>
 */
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
    /*
      The two tiles that are about the app rather than about the work, which is why they are
      last and why they are together. "Not sent yet" is not a module — it is the state of this
      particular device, and putting it beside Stock or Workers would suggest it is somewhere
      to look things up rather than somewhere to go when the banner says something is stuck.
    */
    hint: 'Records still waiting here, and your own account',
    tiles: [
      { title: 'Not sent yet', to: '/sync' },
      { title: 'Your account', to: '/profile' },
    ],
  },
];

export function HomePage() {
  const { user, hasPermission } = useAuth();

  // A tile the role cannot use is not shown at all. A greyed-out row of other people's
  // screens tells a supervisor nothing they can act on, and on a phone it pushes the
  // handful of tiles they do use below the fold. Tiles a later phase brings stay, since
  // "not built yet" is a different thing from "not yours".
  //
  // A group with nothing left in it disappears with its heading. A supervisor must not be
  // shown the word "Set up" over an empty space — it reads as something broken rather than
  // as something that was never his.
  const groups = GROUPS.map((group) => ({
    ...group,
    tiles: group.tiles.filter((tile) => !tile.permission || hasPermission(tile.permission)),
  })).filter((group) => group.tiles.length > 0);

  return (
    <Stack spacing={4}>
      <Stack spacing={0.5}>
        <Typography variant="h1">Nirman Constructions</Typography>
        <Typography color="text.secondary">
          Signed in as {user?.fullName} ({user?.roles.join(', ')})
          {user && !user.allSites && ` — ${user.siteIds.length} site(s) assigned`}
        </Typography>
      </Stack>

      {groups.map((group) => (
        <Stack key={group.title} spacing={1.5} component="section">
          {/*
            A heading and one line under it. The line is not decoration: "Check and sign off"
            alone leaves an engineer guessing whether it means his own work or somebody
            else's, and that is the one distinction the group exists to draw.
          */}
          <Stack spacing={0.25}>
            <Typography variant="h2" sx={{ fontSize: '1.05rem', fontWeight: 700 }}>
              {group.title}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {group.hint}
            </Typography>
          </Stack>

          <Grid container spacing={2}>
            {group.tiles.map((tile) => {
              const available = Boolean(tile.to);
              return (
                <Grid item xs={6} sm={4} key={tile.title}>
                  <Paper
                    elevation={0}
                    {...(available ? { component: Link, to: tile.to! } : {})}
                    sx={{
                      p: 2,
                      minHeight: 72,
                      height: '100%',
                      border: 1,
                      borderColor: available ? 'secondary.main' : 'divider',
                      display: 'flex',
                      flexDirection: 'column',
                      justifyContent: 'center',
                      textDecoration: 'none',
                      color: 'inherit',
                      opacity: available ? 1 : 0.6,
                    }}
                  >
                    <Typography fontWeight={600}>{tile.title}</Typography>
                    {/*
                      The word "Open" used to sit under every title. It was there to contrast
                      with "Coming soon" on tiles a later phase would bring, and once every
                      screen existed it was a line of text on eighteen tiles saying nothing —
                      which the group headings could not afford. A tile that says only what it
                      is, is a tile you open.
                    */}
                    {!available && (
                      <Typography variant="body2" color="text.secondary">
                        {tile.phase ?? 'Coming soon'}
                      </Typography>
                    )}
                  </Paper>
                </Grid>
              );
            })}
          </Grid>
        </Stack>
      ))}
    </Stack>
  );
}
