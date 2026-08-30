import CloseIcon from '@mui/icons-material/Close';
import PhotoCameraOutlinedIcon from '@mui/icons-material/PhotoCameraOutlined';
import { Box, Button, IconButton, Skeleton, Stack, Typography } from '@mui/material';
import { PhotoThumb } from '../../shared/PhotoThumb';
import { useAttachmentUrl } from './api';

/**
 * The picture already on a machine's entry.
 *
 * <p>The link is asked for here rather than carried on the register's rows: the server signs
 * it fresh, re-checking the caller's sites, and it dies in ten minutes — so a register of
 * forty machines would otherwise mint forty links for the one picture somebody looks at.</p>
 *
 * <p>A link that will not load is said out loud rather than left as a broken image icon. On a
 * site phone the usual cause is the signal going while the row was on screen, and "not
 * loading" is the difference between trying again and assuming the photograph was lost.</p>
 */
export function MachinePhoto({
  attachmentId,
  name,
  size = 56,
}: {
  attachmentId: string;
  name: string;
  size?: number;
}) {
  const link = useAttachmentUrl(attachmentId);

  if (link.isLoading) {
    return <Skeleton variant="rounded" width={size} height={size} />;
  }
  if (link.isError || !link.data) {
    return (
      <Typography variant="caption" color="text.secondary">
        Photo not loading
      </Typography>
    );
  }
  return <PhotoThumb src={link.data.url} name={name} size={size} />;
}

/**
 * Putting pictures on a machine's entry.
 *
 * <p><b>One control, not two.</b> The shared {@code PickFileButtons} offers the camera and the
 * gallery as separate buttons, and its reasoning is sound where it is used: the man standing
 * in front of a delivery gets one tap to the rear lens instead of picking "Camera" off the
 * phone's own sheet. Here it read as two different ways to do the same thing, sitting side by
 * side on a form that already has a picture on it — and the cost of the extra tap is smaller
 * than the cost of a supervisor wondering which of the two buttons is the right one. The
 * capture hint stays off, so the phone offers its camera and its gallery in one sheet and both
 * routes are still one tap away.</p>
 *
 * <p>Several at once, because the plate and the damage are photographed in the same minute.</p>
 */
export function PickPhotosButton({
  label,
  busy,
  onPick,
}: {
  label: string;
  busy: boolean;
  onPick: (files: File[]) => void;
}) {
  return (
    <Button
      component="label"
      variant="outlined"
      size="small"
      startIcon={<PhotoCameraOutlinedIcon />}
      disabled={busy}
      sx={{ minHeight: 40 }}
    >
      {busy ? 'Sending…' : label}
      {/*
        Disabled on the input as well as on the button: a MUI Button rendered as a label goes
        grey and stops taking pointer events, and the file input inside it happily keeps
        accepting one — so the control that looks shut is only shut to a mouse.

        No capture attribute. That is what makes this one control rather than two: the phone
        offers its camera and its gallery in a single sheet, and the man photographing a
        machine on Thursday from a picture already on his phone is not sent to a second button
        to do it.
      */}
      <input
        type="file"
        accept="image/*"
        multiple
        disabled={busy}
        hidden
        onChange={(event) => {
          const picked = Array.from(event.target.files ?? []);
          if (picked.length > 0) {
            onPick(picked);
          }
          // Cleared, so picking the same file again after a failure still fires.
          event.target.value = '';
        }}
      />
    </Button>
  );
}

/**
 * The pictures on a machine, each with a cross to take it off.
 *
 * <p>The cross sits on the photograph rather than beside the strip, because a row of
 * thumbnails and one Remove button underneath cannot say which one it removes.</p>
 */
export function MachinePhotos({
  photos,
  name,
  size = 72,
  onRemove,
  removing = false,
}: {
  photos: { id: string; attachmentId: string }[];
  name: string;
  size?: number;
  onRemove?: ((photoId: string) => void) | undefined;
  removing?: boolean;
}) {
  if (photos.length === 0) {
    return null;
  }
  return (
    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
      {photos.map((photo) => (
        <Box key={photo.id} sx={{ position: 'relative', lineHeight: 0 }}>
          <MachinePhoto attachmentId={photo.attachmentId} name={name} size={size} />
          {onRemove && (
            <IconButton
              size="small"
              aria-label={`Remove photo of ${name}`}
              disabled={removing}
              onClick={() => onRemove(photo.id)}
              sx={{
                position: 'absolute',
                top: -8,
                right: -8,
                bgcolor: 'background.paper',
                border: 1,
                borderColor: 'divider',
                p: 0.25,
                '&:hover': { bgcolor: 'error.light' },
              }}
            >
              <CloseIcon sx={{ fontSize: 14 }} />
            </IconButton>
          )}
        </Box>
      ))}
    </Stack>
  );
}
