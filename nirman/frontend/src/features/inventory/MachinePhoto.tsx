import PhotoCameraOutlinedIcon from '@mui/icons-material/PhotoCameraOutlined';
import PhotoLibraryOutlinedIcon from '@mui/icons-material/PhotoLibraryOutlined';
import { Button, Skeleton, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';
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
 * Putting a picture on a machine's entry: the camera, and the pictures already on the phone.
 *
 * <p>It was the camera alone, and {@code capture="environment"} is what made that so — it
 * goes straight to the rear lens, which is right when the man is standing in front of the
 * machine and wrong every other time. A machine written down in the rain at the gate and
 * photographed on Thursday is the ordinary case the entry form already says it expects, and
 * a Thursday photograph is on the phone by then, not in front of the lens. Somebody sent the
 * picture on WhatsApp; the hire company's own photograph came by email; the yard was
 * photographed on the way past and the entry typed that evening. All of those were reachable
 * on a daily report — its picker carries no {@code capture} and so offers the gallery — and
 * none of them was reachable here.</p>
 *
 * <p>So there are two controls rather than one cleverer one. A single picker without
 * {@code capture} would offer the phone's chooser and reach both, but it costs the man in
 * front of the machine the tap that mattered: he now picks "Camera" off a sheet every time,
 * to do the commonest thing on the screen. Two buttons keep that tap and add the other
 * route beside it. On a desk browser {@code capture} is ignored and both open the same file
 * picker, which is what an office attaching the hire company's photograph wanted anyway.</p>
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
    <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
      <PhotoInput
        label={busy ? 'Sending…' : label}
        icon={<PhotoCameraOutlinedIcon />}
        camera
        busy={busy}
        onPick={onPick}
      />
      <PhotoInput
        label="From device"
        icon={<PhotoLibraryOutlinedIcon />}
        busy={busy}
        onPick={onPick}
      />
    </Stack>
  );
}

/** One of the two routes to a file. The camera one asks for the rear lens; the other does not. */
function PhotoInput({
  label,
  icon,
  camera = false,
  busy,
  onPick,
}: {
  label: string;
  icon: ReactNode;
  camera?: boolean;
  busy: boolean;
  onPick: (file: File) => void;
}) {
  return (
    <Button
      component="label"
      size="small"
      startIcon={icon}
      disabled={busy}
      sx={{ minHeight: 40 }}
    >
      {label}
      <input
        type="file"
        accept="image/*"
        {...(camera ? { capture: 'environment' as const } : {})}
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
