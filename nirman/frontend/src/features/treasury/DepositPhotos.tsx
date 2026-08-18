import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import PhotoCameraIcon from '@mui/icons-material/PhotoCamera';
import { Box, Button, IconButton, Stack, Tooltip, Typography } from '@mui/material';
import { PhotoThumb } from '../../shared/PhotoThumb';
import { useAttachmentUrl } from './api';
import type { DepositPhoto } from './types';

/**
 * One stored photograph, fetched as a signed link and shown small.
 *
 * <p>Its own component because the link is per attachment: a hook cannot be called in a loop
 * from the parent, and one query per picture is what lets each thumbnail appear as its own
 * link arrives rather than the strip waiting on the slowest.</p>
 */
function StoredPhoto({
  photo,
  name,
  onRemove,
}: {
  photo: DepositPhoto;
  name: string;
  onRemove?: () => void;
}) {
  const link = useAttachmentUrl(photo.attachmentId);
  if (!link.data) {
    return (
      <Box
        sx={{
          width: 72,
          height: 72,
          borderRadius: 1,
          border: 1,
          borderColor: 'divider',
          bgcolor: 'action.hover',
        }}
      />
    );
  }
  return (
    <Box sx={{ position: 'relative' }}>
      <PhotoThumb src={link.data.url} name={photo.caption || name} size={72} />
      {onRemove && (
        <Tooltip title="Remove this photograph">
          <IconButton
            size="small"
            onClick={onRemove}
            sx={{
              position: 'absolute',
              top: -8,
              right: -8,
              bgcolor: 'background.paper',
              border: 1,
              borderColor: 'divider',
              '&:hover': { bgcolor: 'background.paper' },
            }}
          >
            <DeleteOutlineIcon fontSize="inherit" />
          </IconButton>
        </Tooltip>
      )}
    </Box>
  );
}

/**
 * The certificate's own photographs, and the button that adds one.
 *
 * <p>Each thumbnail is one tap from being large enough to read, which is the whole point of
 * photographing an FDR: the number, the amount and the maturity date are on the paper, and a
 * picture nobody can read them in is a picture of nothing. {@link PhotoThumb} carries both
 * halves — the strip answers "is the certificate on file", the full view answers "does it say
 * what the register says".</p>
 *
 * <p>The pictures belong to the certificate and not to any pledge of it, so they stay put when
 * the deposit is released and lodged against the next contract.</p>
 */
export function DepositPhotos({
  photos,
  name,
  onPick,
  onRemove,
  busy,
}: {
  photos: DepositPhoto[];
  name: string;
  onPick?: (file: File) => void;
  onRemove?: (attachmentId: string) => void;
  busy?: boolean;
}) {
  return (
    <Stack spacing={1}>
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
        {photos.map((photo) => (
          <StoredPhoto
            key={photo.attachmentId}
            photo={photo}
            name={name}
            {...(onRemove ? { onRemove: () => onRemove(photo.attachmentId) } : {})}
          />
        ))}
        {photos.length === 0 && (
          <Typography variant="body2" color="text.secondary">
            No photograph of this certificate on file.
          </Typography>
        )}
      </Stack>
      {onPick && (
        <Box>
          <Button
            component="label"
            size="small"
            startIcon={<PhotoCameraIcon />}
            disabled={busy ?? false}
          >
            Add a photograph
            <input
              hidden
              type="file"
              accept="image/*"
              onChange={(event) => {
                const file = event.target.files?.[0];
                // The value is cleared so choosing the same file twice fires again — a retaken
                // photograph of the same certificate has the same name as often as not.
                event.target.value = '';
                if (file) {
                  onPick(file);
                }
              }}
            />
          </Button>
        </Box>
      )}
    </Stack>
  );
}
