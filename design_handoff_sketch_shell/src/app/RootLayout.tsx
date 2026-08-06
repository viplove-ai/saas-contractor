import { Box, Button, Stack, Typography } from '@mui/material';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../features/auth/AuthContext';
import { OfflineBanner } from '../shared/OfflineBanner';
import { BottomNav, Initials, SideRail, Wordmark } from './AppNav';
import { graphPaper } from './sketch';
import { tokens } from './theme';

/**
 * Shared frame for every signed-in screen.
 *
 * <p>The AppBar is gone. It spent 56px of a phone screen carrying a wordmark, a role chip and
 * a Sign out button — none of which anybody came to the screen for, and one of which (the home
 * icon) existed only because the tiles were the only way back. Navigation now lives at the
 * bottom of the phone, where a thumb already is, and down the left of a desk browser; identity
 * and sign-out moved onto <b>More</b> and the profile screen, one tap from either.</p>
 *
 * <p>What stays common to both shells is what was always common: the offline banner above
 * everything, and the paper ground under everything.</p>
 *
 * @param signoffCount rows waiting on this user's signature, for the Sign-off badge. Passed
 *   down rather than fetched here so the shell owes nothing to the network on first paint.
 */
export function RootLayout({ signoffCount = 0 }: { signoffCount?: number }) {
  const { user, signOut } = useAuth();
  const navigate = useNavigate();
  const { pathname } = useLocation();

  const handleSignOut = async () => {
    await signOut();
    navigate('/login', { replace: true });
  };

  return (
    <Box sx={{ minHeight: '100dvh', display: 'flex', ...graphPaper }}>
      <SideRail signoffCount={signoffCount} />

      <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', minHeight: '100dvh' }}>
        <OfflineBanner />

        {/*
          Phone only, and deliberately not an AppBar: no elevation, no fixed position, nothing
          that survives a scroll. It is a masthead — you read it once on arrival. The desk
          browser gets the same content in the rail instead.
        */}
        <Stack
          direction="row"
          alignItems="center"
          justifyContent="space-between"
          sx={{ display: { xs: 'flex', md: 'none' }, px: 2, pt: 1.75, pb: 1 }}
        >
          <Box component={Link} to="/today" sx={{ textDecoration: 'none' }}>
            <Wordmark compact />
          </Box>
          {user && (
            <Box component={Link} to="/profile" sx={{ textDecoration: 'none' }} aria-label="Your account">
              <Initials name={user.fullName} />
            </Box>
          )}
        </Stack>

        <Box component="main" sx={{ flex: 1, px: { xs: 2, md: 4 }, py: { xs: 1, md: 3 }, maxWidth: 1180, width: '100%' }}>
          <Outlet />
        </Box>

        {/*
          Sign out belongs at the end of the page a desk user is reading, not on a bar over
          every screen. On a phone it lives on /profile, reached from the avatar or from More.
        */}
        {user && (
          <Stack
            direction="row"
            alignItems="center"
            spacing={2}
            sx={{
              display: { xs: 'none', md: 'flex' },
              px: 4,
              py: 2,
              mt: 2,
              borderTop: '1.4px dashed rgba(20,24,29,0.3)',
            }}
          >
            <Typography variant="body2" color="text.secondary" sx={{ flexGrow: 1 }}>
              Signed in as {user.fullName} · {user.roles.join(', ')}
              {!user.allSites && ` · ${user.siteIds.length} site(s) assigned`}
            </Typography>
            <Button variant="text" onClick={handleSignOut} sx={{ color: tokens.muted }}>
              Sign out
            </Button>
          </Stack>
        )}

        <BottomNav signoffCount={signoffCount} />
      </Box>

      {/* Screen-reader-only note on where you are, since the masthead no longer says it. */}
      <Box component="span" sx={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>
        {pathname}
      </Box>
    </Box>
  );
}
