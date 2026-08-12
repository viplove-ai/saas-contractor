import {
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  type SxProps,
  type Theme,
} from '@mui/material';
import { useState } from 'react';

interface Props {
  /** An object URL for a file on the device, or a signed URL for one already uploaded. */
  src: string;
  /** What it is a picture of. Becomes the alt text and the title over the full view. */
  name: string;
  /** Edge of the square thumbnail. 108 suits a form; a table row wants less. */
  size?: number;
  sx?: SxProps<Theme>;
}

/**
 * A picture small enough to sit in a row, and one tap from being large enough to read.
 *
 * <p>Both halves matter and they are answering different questions. The thumbnail answers
 * "which machine is this" or "is that the right bill" at a glance, which a file name never
 * does — {@code IMG_20260812_104533.jpg} is the same nine characters whether it shows a mixer
 * or a thumb over the lens. The full view answers the one the thumbnail cannot: whether the
 * figures on the bill or the number painted on the machine can actually be read.</p>
 *
 * <p>The URL is the caller's to produce and to revoke. An object URL made in here would be
 * minted afresh on every render of whatever holds it, restarting the decode each time and
 * leaking the one it replaced.</p>
 */
export function PhotoThumb({ src, name, size = 108, sx }: Props) {
  const [open, setOpen] = useState(false);

  return (
    <>
      <Box
        component="img"
        src={src}
        alt={name}
        onClick={() => setOpen(true)}
        sx={{
          width: size,
          height: size,
          objectFit: 'cover',
          borderRadius: 1,
          border: 1,
          borderColor: 'divider',
          cursor: 'pointer',
          display: 'block',
          ...sx,
        }}
      />
      <Dialog open={open} onClose={() => setOpen(false)} fullWidth maxWidth="md">
        <DialogTitle sx={{ fontSize: '1rem' }}>{name}</DialogTitle>
        <DialogContent>
          <Box
            component="img"
            src={src}
            alt={name}
            sx={{ width: '100%', height: 'auto', display: 'block' }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Close</Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
