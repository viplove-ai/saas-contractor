import { Box, Skeleton, Stack, Typography } from '@mui/material';
import { PhotoThumb } from '../../shared/PhotoThumb';
import { useDprPhotoUrl } from './api';
import type { PhotoResponse } from './types';

/**
 * The photographs already on a report, shown to anybody who may read the report.
 *
 * <p>Until now they were counted and never shown. The count is the one thing a photograph
 * cannot say: "3 on the report" is true of three pictures of the same wall, of three taken on
 * the wrong day, and of three that are a thumb over the lens. A report is refused at handover
 * without one precisely because the picture is the only part of the document that is evidence
 * rather than assertion — and evidence nobody can look at is not evidence, it is a row in a
 * table saying evidence exists.</p>
 *
 * <p>So they appear on the register panel, where the engineer and the office read the report,
 * and on the wizard beside the ones still being picked. Not behind a permission of their own:
 * whoever may open the report may see what it is a report of, which is the same rule the
 * muster roll and the day's cost on the same panel already follow.</p>
 *
 * <p>The links are signed one at a time as each thumbnail draws — see {@link useDprPhotoUrl}
 * for why they are not carried on the report's own rows.</p>
 */
export function DprPhotos({ photos, size = 108 }: { photos: PhotoResponse[]; size?: number }) {
  if (photos.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary">
        No photographs on this report.
      </Typography>
    );
  }

  return (
    <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap>
      {photos.map((photo, index) => (
        <DprPhoto key={photo.id} photo={photo} index={index} size={size} />
      ))}
    </Stack>
  );
}

/**
 * One picture, its caption under it.
 *
 * <p>A link that will not load is said out loud rather than left as a broken image icon. On a
 * site phone the usual cause is the signal going while the panel was open, and "not loading"
 * is the difference between trying again and believing the photograph was lost — which, on a
 * report that cannot be handed over without one, is a difference worth the two words.</p>
 */
function DprPhoto({
  photo,
  index,
  size,
}: {
  photo: PhotoResponse;
  index: number;
  size: number;
}) {
  const link = useDprPhotoUrl(photo.attachmentId);
  // The caption if there is one, else what it was called — never a bare "photo 3", which is
  // the one label that tells a reader nothing he could not count for himself.
  const name = photo.caption ?? photo.fileName ?? `Photograph ${index + 1}`;

  return (
    <Stack spacing={0.5} sx={{ width: size }}>
      {link.isLoading && <Skeleton variant="rounded" width={size} height={size} />}
      {(link.isError || (!link.isLoading && !link.data)) && (
        <Box
          sx={{
            width: size,
            height: size,
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
      {link.data && <PhotoThumb src={link.data.url} name={name} size={size} />}
      <Typography variant="caption" color="text.secondary" noWrap title={name}>
        {name}
      </Typography>
    </Stack>
  );
}
