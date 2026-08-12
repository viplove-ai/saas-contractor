import { Badge, Box, Stack, Typography } from '@mui/material';
import { useLiveQuery } from 'dexie-react-hooks';
import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '../features/auth/AuthContext';
import { offlineDb, UNSENT_STATUSES } from '../offline/db';
import { tokens } from './theme';

/**
 * The five places a signed-in person goes, in the order a day uses them.
 *
 * <p>Five is the ceiling, not a target: a sixth item on a 375px bar puts every label under
 * 60px of width and turns the bar into a row of truncations. Everything else stays reachable
 * from <b>More</b>, which is the old tile grid — nothing became unreachable, it stopped being
 * the first thing anybody sees.</p>
 *
 * <p>Labels are words, not glyphs. A drawn icon set would have to carry "sign-off" and
 * "registers", and there is no icon for either that a supervisor reads faster than the word.</p>
 */
interface NavItem {
  label: string;
  to: string;
  /** Which paths light this item up, beyond an exact match. */
  match: string[];
  permission?: string;
  /**
   * Shown when the account holds <em>any</em> of these. Sign-off is one door onto four
   * different signatures, and an account holding none of them has no business there — a
   * supervisor's Sign-off tab was a permanent empty list before this.
   */
  anyPermission?: string[];
  /** Where the item's own count comes from, if it has one. */
  badge?: 'signoff';
}

const ITEMS: NavItem[] = [
  { label: 'Today', to: '/today', match: ['/today', '/home'] },
  { label: 'Registers', to: '/inventory/stock', match: ['/inventory', '/workers'], permission: 'inventory:read' },
  {
    label: 'Sign-off',
    to: '/approvals',
    match: ['/approvals', '/attendance/verify', '/dprs'],
    anyPermission: ['attendance:verify', 'dpr:verify', 'expense:approve:l1', 'payment:record'],
    badge: 'signoff',
  },
  { label: 'Reports', to: '/dashboard/site', match: ['/dashboard'], permission: 'dashboard:site' },
  {
    label: 'More',
    to: '/home',
    match: ['/profile', '/sync', '/users', '/projects', '/sites', '/stores', '/vendors', '/staff'],
  },
];

function useNavItems(): NavItem[] {
  const { hasPermission } = useAuth();
  return ITEMS.filter(
    (item) =>
      (!item.permission || hasPermission(item.permission)) &&
      (!item.anyPermission || item.anyPermission.some(hasPermission)),
  );
}

function isActive(item: NavItem, pathname: string): boolean {
  return item.match.some((prefix) => pathname.startsWith(prefix));
}

/** Records this device still owes the server. Read here so both shells show the same number. */
export function useUnsentCount(): number {
  return (
    useLiveQuery(() => offlineDb.drafts.where('status').anyOf(UNSENT_STATUSES).count(), [], 0) ?? 0
  );
}

/**
 * Phone: a bottom bar, above the home indicator.
 *
 * <p>`env(safe-area-inset-bottom)` rather than a fixed pad — on a notched phone the bar
 * would otherwise sit under the swipe area, where every tap is a gamble.</p>
 */
export function BottomNav({ signoffCount = 0 }: { signoffCount?: number }) {
  const items = useNavItems();
  const { pathname } = useLocation();

  return (
    <Box
      component="nav"
      aria-label="Main"
      sx={{
        display: { xs: 'grid', md: 'none' },
        gridTemplateColumns: `repeat(${items.length}, 1fr)`,
        position: 'sticky',
        bottom: 0,
        zIndex: 2,
        bgcolor: 'background.paper',
        borderTop: `1.8px solid ${tokens.ink}`,
        pb: 'env(safe-area-inset-bottom)',
      }}
    >
      {items.map((item) => {
        const active = isActive(item, pathname);
        return (
          <Box
            key={item.label}
            component={Link}
            to={item.to}
            aria-current={active ? 'page' : undefined}
            sx={{
              minHeight: 56,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              textDecoration: 'none',
              // The active mark is a drawn stroke across the top of the cell, pulled up over
              // the bar's own border so the two read as one line, not as two.
              borderTop: active ? `4px solid ${tokens.signal}` : '4px solid transparent',
              mt: '-1.8px',
            }}
          >
            <Badge
              badgeContent={item.badge === 'signoff' ? signoffCount : 0}
              color="secondary"
              max={99}
              sx={{ '& .MuiBadge-badge': { fontFamily: '"IBM Plex Mono", monospace', fontWeight: 700 } }}
            >
              <Typography
                sx={{
                  fontSize: '0.78rem',
                  fontWeight: active ? 700 : 500,
                  color: active ? 'secondary.main' : 'text.secondary',
                }}
              >
                {item.label}
              </Typography>
            </Badge>
          </Box>
        );
      })}
    </Box>
  );
}

/** Desk: the same five items down the left, plus the identity strip the phone puts on top. */
export function SideRail({ signoffCount = 0 }: { signoffCount?: number }) {
  const items = useNavItems();
  const { pathname } = useLocation();
  const { user } = useAuth();
  const unsent = useUnsentCount();

  return (
    <Box
      component="nav"
      aria-label="Main"
      sx={{
        display: { xs: 'none', md: 'flex' },
        flexDirection: 'column',
        gap: 3,
        width: 250,
        flexShrink: 0,
        minHeight: '100dvh',
        p: 2.25,
        bgcolor: tokens.paperDeep,
        borderRight: `1.6px solid ${tokens.ink}`,
      }}
    >
      <Wordmark />

      <Stack spacing={0.25}>
        {items.map((item) => {
          const active = isActive(item, pathname);
          return (
            <Stack
              key={item.label}
              component={Link}
              to={item.to}
              direction="row"
              alignItems="center"
              justifyContent="space-between"
              aria-current={active ? 'page' : undefined}
              sx={{
                minHeight: 44,
                px: 1.6,
                textDecoration: 'none',
                color: active ? 'secondary.main' : 'text.primary',
                fontWeight: active ? 700 : 500,
                fontSize: '0.875rem',
                ...(active && {
                  bgcolor: 'background.paper',
                  border: `1.6px solid ${tokens.ink}`,
                  borderRadius: '11px 6px 12px 7px / 7px 12px 6px 11px',
                  boxShadow: '2px 3px 0 rgba(20,24,29,0.12)',
                }),
              }}
            >
              <span>{item.label}</span>
              {item.badge === 'signoff' && signoffCount > 0 && (
                <Box
                  component="span"
                  sx={{
                    minWidth: 20,
                    height: 20,
                    px: 0.5,
                    borderRadius: 10,
                    bgcolor: 'secondary.main',
                    color: 'secondary.contrastText',
                    fontFamily: '"IBM Plex Mono", monospace',
                    fontWeight: 700,
                    fontSize: '0.7rem',
                    lineHeight: '20px',
                    textAlign: 'center',
                  }}
                >
                  {signoffCount}
                </Box>
              )}
            </Stack>
          );
        })}
      </Stack>

      {/*
        The device's own state, kept apart from the work by a drawn rule. It is not a module
        and it must not sit in the list as though it were one — but it is also the first thing
        somebody looks for when a record has not appeared at the office.
      */}
      <Box sx={{ borderTop: '1.4px dashed rgba(20,24,29,0.35)', pt: 2 }}>
        <Typography variant="overline" sx={{ color: tokens.annotation, display: 'block', mb: 1 }}>
          THIS DEVICE
        </Typography>
        {unsent > 0 ? (
          <Stack
            component={Link}
            to="/sync"
            direction="row"
            alignItems="center"
            spacing={1}
            sx={{ textDecoration: 'none', color: 'warning.main', fontSize: '0.8125rem', fontWeight: 500 }}
          >
            <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'warning.main' }} />
            <span>{unsent} not sent yet</span>
          </Stack>
        ) : (
          <Typography variant="body2" color="text.secondary">
            Everything is sent.
          </Typography>
        )}
      </Box>

      {user && (
        <Stack
          component={Link}
          to="/profile"
          direction="row"
          alignItems="center"
          spacing={1.25}
          sx={{ mt: 'auto', pt: 2, borderTop: '1.4px dashed rgba(20,24,29,0.35)', textDecoration: 'none', color: 'inherit' }}
        >
          <Initials name={user.fullName} />
          <Box sx={{ minWidth: 0 }}>
            <Typography sx={{ fontSize: '0.8125rem', fontWeight: 600 }} noWrap>
              {user.fullName}
            </Typography>
            <Typography variant="body2" color="text.secondary" noWrap>
              {user.roles.join(' · ')}
            </Typography>
          </Box>
        </Stack>
      )}
    </Box>
  );
}

/** The mark: an inked tile carrying the Devanagari initial of the company's own name. */
export function Wordmark({ compact = false }: { compact?: boolean }) {
  return (
    <Stack direction="row" alignItems="center" spacing={1.25} sx={{ textDecoration: 'none' }}>
      <Box
        sx={{
          width: compact ? 34 : 38,
          height: compact ? 34 : 38,
          flexShrink: 0,
          display: 'grid',
          placeItems: 'center',
          bgcolor: 'secondary.main',
          border: `1.8px solid ${tokens.ink}`,
          borderRadius: '13px 6px 14px 7px / 7px 14px 6px 13px',
          fontFamily: '"Kalam", cursive',
          fontWeight: 700,
          fontSize: compact ? '1.05rem' : '1.2rem',
          color: tokens.surface,
        }}
      >
        न
      </Box>
      {!compact && (
        <Box>
          <Typography sx={{ fontFamily: '"Kalam", cursive', fontWeight: 700, fontSize: '0.95rem', lineHeight: 1.1 }}>
            Nirman
          </Typography>
          <Typography variant="overline" sx={{ color: tokens.annotation, fontSize: '0.6rem' }}>
            SITE LEDGER
          </Typography>
        </Box>
      )}
    </Stack>
  );
}

export function Initials({ name }: { name: string }) {
  const initials = name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0] ?? '')
    .join('')
    .toUpperCase();
  return (
    <Box
      sx={{
        width: 36,
        height: 36,
        flexShrink: 0,
        display: 'grid',
        placeItems: 'center',
        borderRadius: '50%',
        border: `1.6px solid ${tokens.ink}`,
        bgcolor: tokens.paperDeep,
        fontSize: '0.78rem',
        fontWeight: 700,
      }}
    >
      {initials}
    </Box>
  );
}
