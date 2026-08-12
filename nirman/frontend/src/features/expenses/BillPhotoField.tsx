import PhotoCameraOutlinedIcon from '@mui/icons-material/PhotoCameraOutlined';
import { Alert, Button, Stack, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { compressPhoto, PHOTO_MAX_EDGE_PX } from '../../offline/uploads';
import { PhotoThumb } from '../../shared/PhotoThumb';

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
 *
 * <p>What was picked is shown as a picture, the same as the day's photographs on the report.
 * A file name is not evidence: the thumb over the lens, the challan photographed instead of
 * the bill, the second page where the first was wanted — all of them look identical as
 * {@code IMG_20260812_104533.jpg}, and all of them are obvious as a picture. Caught here it
 * costs another photograph; caught by an approver it costs the expense a round trip.</p>
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

  /**
   * The object URL behind the thumbnail, made once per file and revoked when it changes.
   *
   * <p>In state rather than derived in the render body: a URL minted there is a new URL on
   * every keystroke elsewhere on the form, which restarts the decode each time and leaks
   * each of the ones it replaced. A bill can also be a PDF, which no {@code img} will draw,
   * so only a picture gets a URL and the rest keep the name line they always had.</p>
   */
  const [preview, setPreview] = useState<string | null>(null);
  useEffect(() => {
    if (!file || !file.type.startsWith('image/')) {
      setPreview(null);
      return;
    }
    const url = URL.createObjectURL(file);
    setPreview(url);
    return () => URL.revokeObjectURL(url);
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

      {/*
        Full width on a tap, because the question the thumbnail cannot answer is whether the
        figures on the bill can actually be read — which is the whole reason it is attached.
      */}
      {file && preview && <PhotoThumb src={preview} name={file.name} />}

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
