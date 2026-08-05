import HomeOutlinedIcon from '@mui/icons-material/HomeOutlined';
import {
  AppBar,
  Box,
  Button,
  Chip,
  Container,
  IconButton,
  Stack,
  Toolbar,
  Typography,
} from '@mui/material';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../features/auth/AuthContext';
import { OfflineBanner } from '../shared/OfflineBanner';

/**
 * Shared frame for every signed-in screen. Phase 3 splits this into SupervisorShell and
 * DeskShell when the task screens arrive; the offline banner and the identity strip stay
 * common to both.
 */
export function RootLayout() {
  const { user, signOut } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  // Every task screen is a dead end without this. The icon carries the way out on a phone,
  // where it is the only thing on the bar wide enough to hit reliably; the wordmark carries
  // it on a desk browser, where clicking the brand is the habit. Both are hidden on /home
  // itself rather than pointing at the screen you are already looking at.
  const atHome = location.pathname === '/home';

  const handleSignOut = async () => {
    await signOut();
    navigate('/login', { replace: true });
  };

  return (
    <Box sx={{ minHeight: '100dvh', bgcolor: 'background.default' }}>
      <OfflineBanner />
      <AppBar position="static" elevation={0} sx={{ borderBottom: 1, borderColor: 'divider' }}>
        <Toolbar sx={{ gap: 1 }}>
          {user && !atHome && (
            <IconButton
              component={Link}
              to="/home"
              color="inherit"
              edge="start"
              aria-label="Go to home"
              sx={{ width: 48, height: 48, flexShrink: 0 }}
            >
              <HomeOutlinedIcon />
            </IconButton>
          )}
          {/*
            Smaller on a phone rather than truncated. A 375px bar carrying the home icon, the
            identity strip and Sign out has about 210px left, and at h6 the name clips to
            "Nirman Construc…" — which reads as a layout that broke rather than as a decision.
            Dropping a couple of points fits it whole, and `noWrap` is the backstop: on a
            narrower screen than any we target it clips rather than making the bar two lines
            tall, which is the one thing the screen can least afford.
          */}
          <Typography
            variant="h6"
            component={user && !atHome ? Link : 'span'}
            {...(user && !atHome ? { to: '/home' } : {})}
            noWrap
            sx={{
              flexGrow: 1,
              fontWeight: 700,
              fontSize: { xs: '1rem', sm: '1.25rem' },
              color: 'inherit',
              textDecoration: 'none',
              minWidth: 0,
            }}
          >
            Nirman Constructions
          </Typography>
          {user && (
            <Stack direction="row" alignItems="center" spacing={1}>
              {/*
                A 375px phone has no room for the role, the full name and a button at once,
                and wrapping them makes the bar two lines tall on the screen that can least
                afford it. On a phone the name alone identifies who is signed in; the role
                and the full name return once there is width for them.
              */}
              <Chip
                label={user.roles.join(' · ')}
                size="small"
                variant="outlined"
                sx={{
                  display: { xs: 'none', md: 'flex' },
                  color: 'inherit',
                  borderColor: 'currentColor',
                }}
              />
              {/* The name is the way into the account screen — the habit everywhere else. */}
              <Typography
                variant="body2"
                component={Link}
                to="/profile"
                noWrap
                sx={{
                  display: { xs: 'none', sm: 'block' },
                  color: 'inherit',
                  textDecoration: 'none',
                }}
              >
                {user.fullName}
              </Typography>
              <Button
                color="inherit"
                onClick={handleSignOut}
                sx={{ minHeight: 48, whiteSpace: 'nowrap', flexShrink: 0 }}
              >
                Sign out
              </Button>
            </Stack>
          )}
        </Toolbar>
      </AppBar>
      <Container maxWidth="lg" sx={{ py: 2 }}>
        <Outlet />
      </Container>
    </Box>
  );
}
