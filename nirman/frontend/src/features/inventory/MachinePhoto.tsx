import PhotoCameraOutlinedIcon from '@mui/icons-material/PhotoCameraOutlined';
import { Button, Skeleton, Typography } from '@mui/material';
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
 * Taking the photograph, as a button that opens the camera.
 *
 * <p>{@code capture="environment"} goes straight to the rear camera on a phone, which is the
 * whole point — the man is standing in front of the machine — and is ignored on a desk
 * browser, where the same control picks a file.</p>
 */
export function PickPhotoButton({
  label,
  busy,
  onPick,
}: {
  label: string;
  busy: boolean;
  onPick: (file: File) => void;
}) {
  return (
    <Button
      component="label"
      size="small"
      startIcon={<PhotoCameraOutlinedIcon />}
      disabled={busy}
      sx={{ minHeight: 40 }}
    >
      {busy ? 'Sending…' : label}
      <input
        type="file"
        accept="image/*"
        capture="environment"
        hidden
        onChange={(event) => {
          const picked = event.target.files?.[0];
          if (picked) {
            onPick(picked);
          }
          // Cleared, so that picking the same file twice after a failure fires again.
          event.target.value = '';
        }}
      />
    </Button>
  );
}
