import { Skeleton, Typography } from '@mui/material';
import { PickFileButtons } from '../../shared/PickFileButtons';
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
 * <p>It was the camera alone. The entry form itself says a machine written down in the rain
 * at the gate and photographed on Thursday is the ordinary case — and by Thursday the
 * photograph is on the phone rather than in front of the lens. Somebody sent it on WhatsApp;
 * the hire company's own picture came by email; the yard was photographed on the way past and
 * the entry typed that evening. See {@link PickFileButtons} for why that is two buttons
 * rather than one picker without a {@code capture} attribute.</p>
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
    <PickFileButtons
      label={busy ? 'Sending…' : label}
      busy={busy}
      onPick={(files) => files[0] && onPick(files[0])}
    />
  );
}
