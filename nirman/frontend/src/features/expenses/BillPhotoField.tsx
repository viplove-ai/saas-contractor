import PhotoCameraOutlinedIcon from '@mui/icons-material/PhotoCameraOutlined';
import { Alert, Button, Stack, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { compressPhoto, PHOTO_MAX_EDGE_PX } from '../../offline/uploads';

/**
 * The bill photograph.
 *
 * <p>Compression happens here, when the file is picked, rather than at upload time. Two
 * reasons, and the second is the one that matters. A supervisor who takes a photograph and
 * immediately sees "3.8 MB → 240 KB" knows the app did something sensible with it; the same
 * work done invisibly ten minutes later on a reconnect is indistinguishable from the app
 * losing it. And on the day there is no signal at all, compressing now is what decides
 * whether four bills fit in the browser's storage quota or one does.</p>
 *
 * <p>{@code capture="environment"} opens the rear camera directly on a phone and is ignored
 * on a desk browser, where the same control is a file picker — which is what an accountant
 * scanning a stack of bills wants anyway.</p>
 */
export function BillPhotoField({
  file,
  onPick,
}: {
  file: File | null;
  onPick: (file: File | null) => void;
}) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState<{ from: number; to: number } | null>(null);

  // Clearing the field on save has to clear what the field is saying about it too.
  useEffect(() => {
    if (!file) {
      setSaved(null);
      setError(null);
    }
  }, [file]);

  const pick = async (picked: File | undefined) => {
    if (!picked) return;
    setBusy(true);
    setError(null);
    try {
      const photo = await compressPhoto(picked);
      // The compressed bytes are what gets queued, so the File handed upward is the
      // compressed one. Keeping the original here would mean compressing twice and sending
      // whichever copy the upload path happened to pick up.
      onPick(new File([photo.blob], photo.fileName, { type: photo.contentType }));
      setSaved({ from: photo.originalBytes, to: photo.blob.size });
    } catch {
      setError('That photograph could not be read. Take it again.');
      onPick(null);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack spacing={1}>
      <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
        <Button
          component="label"
          variant="outlined"
          startIcon={<PhotoCameraOutlinedIcon />}
          disabled={busy}
          sx={{ minHeight: 48 }}
        >
          {busy ? 'Shrinking…' : file ? 'Change photograph' : 'Photograph the bill'}
          <input
            type="file"
            accept="image/*,application/pdf"
            capture="environment"
            hidden
            onChange={(e) => void pick(e.target.files?.[0])}
          />
        </Button>
        {file && (
          <Button color="error" onClick={() => onPick(null)} sx={{ minHeight: 48 }}>
            Remove
          </Button>
        )}
      </Stack>

      {file && saved && (
        <Typography variant="body2" color="text.secondary">
          {file.name} · {formatBytes(saved.to)}
          {saved.from > saved.to && ` (from ${formatBytes(saved.from)})`} · long edge{' '}
          {PHOTO_MAX_EDGE_PX}px
        </Typography>
      )}
      {error && <Alert severity="error">{error}</Alert>}
    </Stack>
  );
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
