import { Alert, Box, Button, Paper, Skeleton, Stack, Typography } from '@mui/material';
import { useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { SignatureCropDialog } from '../../shared/SignatureCropDialog';
import { useAuth } from '../auth/AuthContext';
import { useClearSignature, useSetSignature, useSignatureUrl } from './api';

/** The anchor the sign-in prompt sends somebody to. */
export const SIGNATURE_SECTION_ID = 'signature';

/**
 * The member's signature, on his own account screen and nowhere else.
 *
 * <p>His own, because a signature somebody else uploaded for him is a name on a document he
 * never saw. An administrator issues the offer letter over this; a supervisor's and an
 * engineer's are drawn onto the daily report at "Prepared by" and "Verified by". So the card
 * says what the picture will be used for, because that is what tells somebody whether to
 * bother — and what to sign on the sheet of paper before photographing it.</p>
 *
 * <p>The picture is cropped before it is sent, in {@link SignatureCropDialog}, to the one
 * shape every document prints it in. What this card shows back is what the documents will
 * draw: the same box, the same proportions, on white.</p>
 */
export function SignatureCard() {
  const { user, updateUser } = useAuth();
  const set = useSetSignature();
  const clear = useClearSignature();
  const link = useSignatureUrl(user?.signatureAttachmentId);
  const [picked, setPicked] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<'SET' | 'CLEARED' | null>(null);
  const busy = set.isPending || clear.isPending;
  const has = Boolean(user?.signatureAttachmentId);

  const onCropped = async (signature: Blob) => {
    setPicked(null);
    setError(null);
    setDone(null);
    try {
      updateUser(await set.mutateAsync(signature));
      setDone('SET');
    } catch (failure) {
      setError(apiErrorDetail(failure));
    }
  };

  const remove = async () => {
    setError(null);
    setDone(null);
    try {
      updateUser(await clear.mutateAsync());
      setDone('CLEARED');
    } catch (failure) {
      setError(apiErrorDetail(failure));
    }
  };

  return (
    <Paper
      id={SIGNATURE_SECTION_ID}
      elevation={0}
      sx={{ p: 3, border: 1, borderColor: 'divider', scrollMarginTop: 16 }}
    >
      <Stack spacing={1.5}>
        <Typography variant="h3">Your signature</Typography>
        <Typography variant="body2" color="text.secondary">
          Printed where your name goes on the documents you sign — an offer letter if you are an
          administrator, the daily report if you prepare or verify it. Sign on plain white paper
          with a dark pen, photograph it, and crop to the signature.
        </Typography>

        {!has && (
          <Alert severity="warning">
            No signature on file yet. Documents you sign will print your name over a blank line
            until you upload one.
          </Alert>
        )}
        {done === 'SET' && <Alert severity="success">Your signature has been saved.</Alert>}
        {done === 'CLEARED' && <Alert severity="info">Your signature has been removed.</Alert>}
        {error && <Alert severity="error">{error}</Alert>}

        {has && (
          <Box sx={{ width: 252, height: 84 }}>
            {link.isLoading ? (
              <Skeleton variant="rounded" width={252} height={84} />
            ) : link.data ? (
              <Box
                component="img"
                src={link.data.url}
                alt="Your signature"
                sx={{
                  width: 252,
                  height: 84,
                  display: 'block',
                  border: 1,
                  borderColor: 'divider',
                  borderRadius: 1,
                  bgcolor: '#fff',
                }}
              />
            ) : (
              <Typography variant="caption" color="text.secondary">
                Signature not loading — there may be no connection.
              </Typography>
            )}
          </Box>
        )}

        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
          {/* 48px: the app's floor for anything meant to be hit with a glove on. */}
          <Button
            component="label"
            variant={has ? 'outlined' : 'contained'}
            color="secondary"
            disabled={busy}
            sx={{ minHeight: 48 }}
          >
            {busy ? 'Saving…' : has ? 'Replace signature' : 'Upload signature'}
            <input
              type="file"
              accept="image/*"
              hidden
              disabled={busy}
              data-testid="signature-file"
              onChange={(event) => {
                const file = event.target.files?.[0] ?? null;
                event.target.value = '';
                if (file) setPicked(file);
              }}
            />
          </Button>
          {has && (
            <Button color="error" disabled={busy} onClick={() => void remove()} sx={{ minHeight: 48 }}>
              Remove
            </Button>
          )}
        </Stack>
      </Stack>

      <SignatureCropDialog
        file={picked}
        onCancel={() => setPicked(null)}
        onCropped={(signature) => void onCropped(signature)}
      />
    </Paper>
  );
}
