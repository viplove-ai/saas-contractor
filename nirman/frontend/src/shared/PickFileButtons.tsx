import PhotoCameraOutlinedIcon from '@mui/icons-material/PhotoCameraOutlined';
import PhotoLibraryOutlinedIcon from '@mui/icons-material/PhotoLibraryOutlined';
import { Button, Stack } from '@mui/material';
import type { ReactNode } from 'react';

/**
 * Two ways to the same file: the camera, and what is already on the device.
 *
 * <p>{@code capture="environment"} goes straight to the rear lens, which is right when the
 * man is standing in front of the thing he is photographing and wrong every other time. The
 * machine written down at the gate in the rain and photographed on Thursday is the ordinary
 * case, and by Thursday the picture is in the gallery; the Aadhaar card was scanned in the
 * office and mailed over; the appointment letter is a PDF that no camera will ever produce.
 * A picker carrying {@code capture} reaches none of them.</p>
 *
 * <p>So two controls rather than one cleverer one. Dropping {@code capture} would offer the
 * phone's own chooser and reach both, at the cost of the tap that mattered: the man in front
 * of the machine would pick "Camera" off a sheet every time, to do the commonest thing on the
 * screen. Two buttons keep that tap and put the other route beside it. On a desk browser
 * {@code capture} is ignored and the two open the same file picker, which is what somebody
 * attaching a scan wanted anyway.</p>
 */
export function PickFileButtons({
  label,
  deviceLabel = 'From device',
  busy = false,
  /** What the device button will take. The camera button is always image-only. */
  accept = 'image/*',
  multiple = false,
  onPick,
}: {
  label: string;
  deviceLabel?: string;
  busy?: boolean;
  accept?: string;
  multiple?: boolean;
  onPick: (files: File[]) => void;
}) {
  return (
    <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
      <FileInput
        label={label}
        icon={<PhotoCameraOutlinedIcon />}
        accept="image/*"
        camera
        busy={busy}
        multiple={multiple}
        onPick={onPick}
      />
      <FileInput
        label={deviceLabel}
        icon={<PhotoLibraryOutlinedIcon />}
        accept={accept}
        busy={busy}
        multiple={multiple}
        onPick={onPick}
      />
    </Stack>
  );
}

/** One of the two routes to a file. The camera one asks for the rear lens; the other does not. */
function FileInput({
  label,
  icon,
  accept,
  camera = false,
  busy,
  multiple,
  onPick,
}: {
  label: string;
  icon: ReactNode;
  accept: string;
  camera?: boolean;
  busy: boolean;
  multiple: boolean;
  onPick: (files: File[]) => void;
}) {
  return (
    <Button component="label" size="small" startIcon={icon} disabled={busy} sx={{ minHeight: 40 }}>
      {label}
      {/*
        Disabled on the input as well as on the button. A MUI Button rendered as a label goes
        grey and stops taking pointer events, and the file input inside it happily keeps
        accepting one — so the control that looks shut is only shut to a mouse.
      */}
      <input
        type="file"
        disabled={busy}
        accept={accept}
        {...(camera ? { capture: 'environment' as const } : {})}
        {...(multiple ? { multiple: true } : {})}
        hidden
        onChange={(event) => {
          const picked = Array.from(event.target.files ?? []);
          if (picked.length > 0) {
            onPick(picked);
          }
          // Cleared, so that picking the same file twice after a failure fires again.
          event.target.value = '';
        }}
      />
    </Button>
  );
}
