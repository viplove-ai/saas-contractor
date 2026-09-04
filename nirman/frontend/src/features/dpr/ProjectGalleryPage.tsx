import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  IconButton,
  MenuItem,
  Paper,
  Skeleton,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { PhotoThumb } from '../../shared/PhotoThumb';
import { StatusChip, type RecordStatus } from '../../shared/StatusChip';
import { useAdminSites, useProject } from '../admin/api';
import { useDprPhotoUrl, useProjectGallery } from './api';
import type { DprWorkflow, GalleryPhoto } from './types';

const STATUS_CHIP: Record<DprWorkflow, RecordStatus> = {
  DRAFT: 'DRAFT',
  SUBMITTED: 'SUBMITTED',
  VERIFIED: 'VERIFIED',
  REJECTED: 'REJECTED',
  APPROVED: 'APPROVED',
};

/**
 * Every photograph of a project, read off its daily reports.
 *
 * <p>The report is where the site is photographed — it cannot be handed over without a
 * picture — so the pictures were always on file, one day at a time, and finding the wall as it
 * stood in March meant knowing which report to open. This is the same pictures the other way
 * round: the whole project, newest day first, grouped by the day they were taken for, each one
 * naming its report and its site. Nothing is stored for it; a gallery with rows of its own would
 * be a second list of the same files.</p>
 *
 * <p>Grouped by day rather than laid out as one grid because the day is what the reader is
 * looking for — "what did it look like before the monsoon" is a date, and a caption is only
 * ever as good as the man who typed it at seven in the evening.</p>
 */
export function ProjectGalleryPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const project = useProject(projectId);
  const sites = useAdminSites(projectId ?? '');
  const [siteId, setSiteId] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const gallery = useProjectGallery(projectId, siteId, from, to);

  const photos = useMemo(
    () => gallery.data?.pages.flatMap((page) => page.content) ?? [],
    [gallery.data],
  );
  const total = gallery.data?.pages[0]?.totalElements ?? 0;

  // The days, in the order the server sent them, each with its pictures.
  const days = useMemo(() => {
    const byDay = new Map<string, GalleryPhoto[]>();
    for (const photo of photos) {
      const list = byDay.get(photo.reportDate);
      if (list) list.push(photo);
      else byDay.set(photo.reportDate, [photo]);
    }
    return [...byDay.entries()];
  }, [photos]);

  // A project with one site has no choice to offer; the filter appears at two.
  const siteChoices = sites.data ?? [];

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={1} alignItems="flex-start">
        <Tooltip title="Back to project">
          <IconButton
            component={Link}
            to={`/projects/${projectId}`}
            aria-label="Back to project"
            edge="start"
          >
            <ArrowBackIcon />
          </IconButton>
        </Tooltip>
        <Box sx={{ minWidth: 0, flexGrow: 1 }}>
          <Typography variant="h5" component="h1">
            Photographs
          </Typography>
          <Typography color="text.secondary">
            {project.data ? `${project.data.code} — ${project.data.name}` : ' '}
          </Typography>
        </Box>
      </Stack>

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        {siteChoices.length > 1 && (
          <TextField
            select
            label="Site"
            value={siteId}
            onChange={(e) => setSiteId(e.target.value)}
            sx={{ minWidth: 220 }}
          >
            <MenuItem value="">Every site</MenuItem>
            {siteChoices.map((site) => (
              <MenuItem key={site.id} value={site.id}>
                {site.code} — {site.name}
              </MenuItem>
            ))}
          </TextField>
        )}
        <TextField
          label="From"
          type="date"
          value={from}
          onChange={(e) => setFrom(e.target.value)}
          InputLabelProps={{ shrink: true }}
        />
        <TextField
          label="To"
          type="date"
          value={to}
          onChange={(e) => setTo(e.target.value)}
          InputLabelProps={{ shrink: true }}
        />
      </Stack>

      {gallery.isLoading && <CircularProgress />}
      {gallery.isError && <Alert severity="error">{apiErrorDetail(gallery.error)}</Alert>}

      {gallery.data && photos.length === 0 && (
        <Paper variant="outlined" sx={{ p: 2.5 }}>
          <Typography variant="h6">No photographs yet</Typography>
          <Typography color="text.secondary">
            Every daily report carries at least one picture of the site when it is handed over.
            Photographs added to a report appear here the same day.
          </Typography>
        </Paper>
      )}

      {photos.length > 0 && (
        <>
          <Typography variant="body2" color="text.secondary">
            {total} photograph{total === 1 ? '' : 's'}
            {photos.length < total && `, showing ${photos.length}`}
          </Typography>

          <Stack spacing={3}>
            {days.map(([day, pictures]) => (
              <Box key={day}>
                <Typography variant="subtitle2" gutterBottom>
                  {day}
                </Typography>
                <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap>
                  {pictures.map((photo) => (
                    <GalleryCard key={photo.id} photo={photo} showSite={!siteId} />
                  ))}
                </Stack>
              </Box>
            ))}
          </Stack>

          {gallery.hasNextPage && (
            <Button
              onClick={() => gallery.fetchNextPage()}
              disabled={gallery.isFetchingNextPage}
              sx={{ alignSelf: 'flex-start', minHeight: 48 }}
            >
              {gallery.isFetchingNextPage ? 'Loading…' : 'Show earlier days'}
            </Button>
          )}
        </>
      )}
    </Stack>
  );
}

const SIZE = 160;

/**
 * One picture, and under it where it belongs: the site when more than one is being shown, the
 * report it was taken for as a link, and the report's standing. The status is not decoration —
 * a photograph on a draft is a picture somebody took, and one on an approved report is a
 * picture the office accepted as the day's evidence.
 */
function GalleryCard({ photo, showSite }: { photo: GalleryPhoto; showSite: boolean }) {
  const link = useDprPhotoUrl(photo.attachmentId);
  const name = photo.caption ?? photo.fileName ?? 'Photograph';

  return (
    <Stack spacing={0.5} sx={{ width: SIZE }}>
      {link.isLoading && <Skeleton variant="rounded" width={SIZE} height={SIZE} />}
      {(link.isError || (!link.isLoading && !link.data)) && (
        <Box
          sx={{
            width: SIZE,
            height: SIZE,
            borderRadius: 1,
            border: 1,
            borderColor: 'divider',
            display: 'grid',
            placeItems: 'center',
            p: 1,
          }}
        >
          <Typography variant="caption" color="text.secondary" textAlign="center">
            Photo not loading
          </Typography>
        </Box>
      )}
      {link.data && <PhotoThumb src={link.data.url} name={name} size={SIZE} />}
      <Typography variant="body2" noWrap title={name}>
        {name}
      </Typography>
      <Stack direction="row" spacing={0.5} alignItems="center" flexWrap="wrap" useFlexGap>
        {showSite && photo.siteCode && (
          <Chip size="small" variant="outlined" label={photo.siteCode} sx={{ height: 20 }} />
        )}
        <StatusChip status={STATUS_CHIP[photo.workflowStatus]} />
      </Stack>
      <Typography
        variant="caption"
        color="text.secondary"
        component={Link}
        to={`/dpr/${photo.dprId}`}
        sx={{ textDecoration: 'none', '&:hover': { textDecoration: 'underline' } }}
      >
        {photo.dprNumber}
      </Typography>
    </Stack>
  );
}
