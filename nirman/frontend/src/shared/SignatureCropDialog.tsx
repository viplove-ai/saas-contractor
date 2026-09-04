import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Slider,
  Stack,
  Typography,
} from '@mui/material';
import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import {
  clampCrop,
  initialCrop,
  loadImage,
  releaseImage,
  renderSignature,
  SIGNATURE_ASPECT,
  type CropRect,
} from './signatureImage';

interface Props {
  /** The picture just picked. Null closes the dialog. */
  file: File | null;
  onCancel: () => void;
  /** The cropped signature, rendered at the standard size on white. */
  onCropped: (signature: Blob) => void;
}

/**
 * Cutting a signature out of a photograph, to the one shape every document draws it in.
 *
 * <p>The box keeps its shape and the person moves it. A free-hand crop would let a signature be
 * uploaded tall and narrow and then be squashed flat on the letter, and the whole point of
 * cropping here rather than on the server is that the shape is settled by the person looking
 * at the pen stroke. So the box is always three to one: drag it over the signature, and the
 * slider makes it larger or smaller around its centre.</p>
 *
 * <p>Pointer events rather than mouse events, because the person doing this is usually holding
 * a phone — and the pointer is captured on the box for the length of the drag, so a finger
 * that strays off the picture does not drop the box where it was.</p>
 */
export function SignatureCropDialog({ file, onCancel, onCropped }: Props) {
  const [image, setImage] = useState<HTMLImageElement | null>(null);
  const [crop, setCrop] = useState<CropRect | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const frame = useRef<HTMLDivElement>(null);
  const drag = useRef<{ startX: number; startY: number; origin: CropRect } | null>(null);

  useEffect(() => {
    if (!file) {
      setImage(null);
      setCrop(null);
      setError(null);
      return;
    }
    let cancelled = false;
    let shown: HTMLImageElement | null = null;
    loadImage(file)
      .then((loaded) => {
        if (cancelled) {
          releaseImage(loaded);
          return;
        }
        shown = loaded;
        setImage(loaded);
        setCrop(initialCrop(loaded.naturalWidth, loaded.naturalHeight));
      })
      .catch(() => {
        if (!cancelled) setError('That picture could not be read. Take it again.');
      });
    // The URL lives exactly as long as this file is the one on screen.
    return () => {
      cancelled = true;
      if (shown) releaseImage(shown);
    };
  }, [file]);

  /** Displayed pixels per source pixel, which is what turns a finger's movement into a crop. */
  const scale = () => {
    const box = frame.current;
    if (!box || !image) return 1;
    return box.clientWidth / image.naturalWidth;
  };

  const onPointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!crop) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    drag.current = { startX: event.clientX, startY: event.clientY, origin: crop };
  };

  const onPointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!drag.current || !image) return;
    const s = scale();
    const moved = {
      ...drag.current.origin,
      x: drag.current.origin.x + (event.clientX - drag.current.startX) / s,
      y: drag.current.origin.y + (event.clientY - drag.current.startY) / s,
    };
    setCrop(clampCrop(moved, image.naturalWidth, image.naturalHeight));
  };

  const onPointerUp = () => {
    drag.current = null;
  };

  /** Resizes about the centre, so the slider does not walk the box across the picture. */
  const resize = (fraction: number) => {
    if (!image || !crop) return;
    const largest = Math.min(image.naturalWidth, image.naturalHeight * SIGNATURE_ASPECT);
    const width = largest * fraction;
    const centreX = crop.x + crop.width / 2;
    const centreY = crop.y + crop.height / 2;
    setCrop(
      clampCrop(
        { x: centreX - width / 2, y: centreY - width / SIGNATURE_ASPECT / 2, width, height: 0 },
        image.naturalWidth,
        image.naturalHeight,
      ),
    );
  };

  const finish = async () => {
    if (!image || !crop) return;
    setBusy(true);
    try {
      onCropped(await renderSignature(image, crop));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'The picture could not be prepared.');
    } finally {
      setBusy(false);
    }
  };

  const largest = image
    ? Math.min(image.naturalWidth, image.naturalHeight * SIGNATURE_ASPECT)
    : 1;
  const s = scale();

  return (
    <Dialog open={file !== null} onClose={busy ? undefined : onCancel} fullWidth maxWidth="sm">
      <DialogTitle>Crop your signature</DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          <Typography variant="body2" color="text.secondary">
            Drag the box over your signature. It keeps the shape every document prints it in;
            use the slider to make it larger or smaller.
          </Typography>
          {error && <Alert severity="error">{error}</Alert>}
          {image && crop && (
            <Box
              ref={frame}
              sx={{
                position: 'relative',
                width: '100%',
                lineHeight: 0,
                userSelect: 'none',
                touchAction: 'none',
                overflow: 'hidden',
                borderRadius: 1,
                border: 1,
                borderColor: 'divider',
              }}
            >
              <Box
                component="img"
                src={image.src}
                alt="The picture you picked"
                draggable={false}
                sx={{ width: '100%', display: 'block' }}
              />
              <Box
                role="slider"
                aria-label="Crop box"
                aria-valuetext={`${Math.round(crop.width)} by ${Math.round(crop.height)} pixels`}
                onPointerDown={onPointerDown}
                onPointerMove={onPointerMove}
                onPointerUp={onPointerUp}
                onPointerCancel={onPointerUp}
                sx={{
                  position: 'absolute',
                  left: crop.x * s,
                  top: crop.y * s,
                  width: crop.width * s,
                  height: crop.height * s,
                  boxSizing: 'border-box',
                  border: '2px solid',
                  borderColor: 'secondary.main',
                  boxShadow: '0 0 0 9999px rgba(0,0,0,0.45)',
                  cursor: 'move',
                }}
              />
            </Box>
          )}
          {image && crop && (
            <Slider
              aria-label="Crop size"
              min={0.2}
              max={1}
              step={0.01}
              value={crop.width / largest}
              onChange={(_event, value) => resize(Array.isArray(value) ? (value[0] ?? 1) : value)}
            />
          )}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onCancel} disabled={busy}>
          Cancel
        </Button>
        <Button
          variant="contained"
          onClick={() => void finish()}
          disabled={busy || !crop || !!error}
          sx={{ minHeight: 48 }}
        >
          {busy ? 'Preparing…' : 'Use this signature'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
