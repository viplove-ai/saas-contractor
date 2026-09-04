import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { apiErrorDetail } from '../../shared/apiClient';
import { useAuth } from '../auth/AuthContext';
import { downloadBlob, useIssueOfferLetter, usePreviewOfferLetter } from './api';
import { offerLetterSchema, type OfferLetterForm } from './schema';
import { EMPLOYMENT_LABEL, type StaffProfile } from './types';

interface Props {
  open: boolean;
  member: StaffProfile;
  onClose: () => void;
}

/**
 * The offer of employment, written off the record.
 *
 * <p><b>The form is short on purpose.</b> The designation, the joining date, the probation and
 * its length, the notice period and the whole salary structure are already on the record — so
 * the letter reads them and this asks for none of them. A screen that collected the terms
 * again would be a second place to state them, and the letter and the payroll would disagree
 * about the man they both describe inside a year. What is left is what belongs to the letter
 * alone: where he is posted and by when he must answer.</p>
 *
 * <p><b>Who signs is not asked either.</b> It used to be two boxes, a name and a post, which
 * is a letter that can go out over any name somebody types. It is now the administrator
 * issuing it — his name off the session and his signature off his account — and the dialog
 * says so, and says when the signature is missing, because the server refuses to issue an
 * unsigned letter and the sentence that explains why belongs next to the button.</p>
 *
 * <p><b>Two buttons, and the difference matters.</b> Previewing renders the letter and keeps
 * nothing, because a letter is read before it is sent. Issuing renders it again on the server
 * and files it on the record as a paper — which is the whole point: a letter that lived only
 * in a download folder would be the one term of employment nobody could produce when it was
 * disputed.</p>
 */
export function OfferLetterDialog({ open, member, onClose }: Props) {
  const { user } = useAuth();
  const signed = Boolean(user?.signatureAttachmentId);
  const preview = usePreviewOfferLetter();
  const issue = useIssueOfferLetter();
  const [serverError, setServerError] = useState<string | null>(null);
  const [issued, setIssued] = useState(false);
  /*
    Either job locks both buttons and the Close beside them. They are two ways of rendering the
    same letter, and a preview started while the issue is still in flight would hand him a
    second PDF while the first was being filed — and closing the dialog under an issue in
    flight loses the only place its refusal could have been shown.
  */
  const busy = preview.isPending || issue.isPending;

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<OfferLetterForm>({
    resolver: zodResolver(offerLetterSchema),
    defaultValues: empty(member),
  });

  useEffect(() => {
    if (open) {
      setServerError(null);
      setIssued(false);
      reset(empty(member));
    }
  }, [open, member, reset]);

  const doPreview = handleSubmit(async (values) => {
    setServerError(null);
    try {
      const pdf = await preview.mutateAsync({ userId: member.userId, ...values });
      downloadBlob(pdf, `offer-letter-${member.username}.pdf`);
    } catch (error) {
      setServerError(await blobErrorDetail(error));
    }
  });

  const doIssue = handleSubmit(async (values) => {
    setServerError(null);
    try {
      await issue.mutateAsync({ userId: member.userId, ...values });
      setIssued(true);
    } catch (error) {
      setServerError(apiErrorDetail(error));
    }
  });

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Offer letter — {member.fullName}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {serverError && <Alert severity="error">{serverError}</Alert>}
          {issued && (
            <Alert severity="success">
              Issued and filed on {member.fullName}&apos;s record, under Papers.
            </Alert>
          )}

          <Alert severity="info">
            <Typography variant="body2">
              The letter states what the record already says:{' '}
              <strong>{member.designation ?? 'no designation recorded'}</strong>,{' '}
              {EMPLOYMENT_LABEL[member.employmentType].toLowerCase()}, joining{' '}
              <strong>{member.joinedOn ?? 'no date recorded'}</strong>, and the salary
              structure in force on that day. Correct any of those on the record rather than
              here.
            </Typography>
          </Alert>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Joining date"
              type="date"
              fullWidth
              InputLabelProps={{ shrink: true }}
              error={!!errors.joiningOn}
              helperText={errors.joiningOn?.message ?? 'Blank uses the date on the record'}
              {...register('joiningOn')}
            />
            <TextField
              label="Letter dated"
              type="date"
              fullWidth
              InputLabelProps={{ shrink: true }}
              error={!!errors.letterDate}
              helperText={errors.letterDate?.message ?? 'Blank uses today'}
              {...register('letterDate')}
            />
          </Stack>

          <TextField
            label="Reference"
            error={!!errors.reference}
            helperText={
              errors.reference?.message ??
              'Blank builds one from the firm code, the year and the employee number'
            }
            {...register('reference')}
          />

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Place of posting"
              fullWidth
              error={!!errors.placeOfPosting}
              helperText={errors.placeOfPosting?.message ?? 'The site or office he starts at'}
              {...register('placeOfPosting')}
            />
            <TextField
              label="Reply by"
              type="date"
              fullWidth
              InputLabelProps={{ shrink: true }}
              error={!!errors.respondBy}
              helperText={errors.respondBy?.message ?? 'How long the offer stands'}
              {...register('respondBy')}
            />
          </Stack>

          <Alert severity={signed ? 'info' : 'warning'}>
            <Typography variant="body2">
              Signed by <strong>{user?.fullName ?? 'you'}</strong>, for the firm.{' '}
              {signed
                ? 'Your signature on file is printed over the line.'
                : 'There is no signature on your account yet — upload it on your account screen before issuing. A preview prints the line blank.'}
            </Typography>
          </Alert>
        </Stack>
      </DialogContent>
      {/*
        Both buttons say so while they work. Rendering the letter is a round trip that ends in
        a PDF built on the server, and on a site connection it is several seconds — long enough
        that a button which simply goes grey reads as a button that did nothing, and the next
        thing that happens is a second tap. Disabled is what stops the second request; the
        spinner and the changed word are what stop him wanting to make it.
      */}
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={busy}>
          Close
        </Button>
        <Button
          onClick={doPreview}
          disabled={busy}
          startIcon={
            preview.isPending ? <CircularProgress size={16} color="inherit" /> : undefined
          }
        >
          {preview.isPending ? 'Preparing the letter…' : 'Preview'}
        </Button>
        <Button
          variant="contained"
          onClick={doIssue}
          disabled={busy}
          startIcon={
            issue.isPending ? <CircularProgress size={16} color="inherit" /> : undefined
          }
        >
          {issue.isPending ? 'Issuing and filing…' : 'Issue and file on the record'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function empty(member: StaffProfile): OfferLetterForm {
  return {
    joiningOn: member.joinedOn ?? '',
    letterDate: new Date().toISOString().slice(0, 10),
    reference: '',
    placeOfPosting: '',
    respondBy: '',
  };
}

/**
 * A refusal on a request that asked for a PDF arrives as a Blob, not as JSON.
 *
 * <p>Without this the office sees "Something went wrong" for every one of the sentences the
 * server took trouble to write — that there is no salary on the record, that the salary has
 * no breakdown — which are exactly the messages that say what to do next.</p>
 */
async function blobErrorDetail(error: unknown): Promise<string> {
  const body = (error as { response?: { data?: unknown } })?.response?.data;
  if (body instanceof Blob) {
    try {
      const parsed = JSON.parse(await body.text()) as { detail?: string };
      if (parsed.detail) {
        return parsed.detail;
      }
    } catch {
      // Not JSON after all; fall through to the ordinary message.
    }
  }
  return apiErrorDetail(error);
}
