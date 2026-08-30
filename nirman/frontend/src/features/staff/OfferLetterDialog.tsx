import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
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
 * alone: where he is posted, whom he reports to, by when he must answer, and who signs.</p>
 *
 * <p><b>Two buttons, and the difference matters.</b> Previewing renders the letter and keeps
 * nothing, because a letter is read before it is sent. Issuing renders it again on the server
 * and files it on the record as a paper — which is the whole point: a letter that lived only
 * in a download folder would be the one term of employment nobody could produce when it was
 * disputed.</p>
 */
export function OfferLetterDialog({ open, member, onClose }: Props) {
  const preview = usePreviewOfferLetter();
  const issue = useIssueOfferLetter();
  const [serverError, setServerError] = useState<string | null>(null);
  const [issued, setIssued] = useState(false);

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
              label="Reporting to"
              fullWidth
              error={!!errors.reportingTo}
              helperText={errors.reportingTo?.message}
              {...register('reportingTo')}
            />
          </Stack>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Reply by"
              type="date"
              fullWidth
              InputLabelProps={{ shrink: true }}
              error={!!errors.respondBy}
              helperText={errors.respondBy?.message ?? 'How long the offer stands'}
              {...register('respondBy')}
            />
            <TextField
              label="Signed by"
              fullWidth
              error={!!errors.signatoryName}
              helperText={errors.signatoryName?.message}
              {...register('signatoryName')}
            />
            <TextField
              label="Their post"
              fullWidth
              error={!!errors.signatoryDesignation}
              helperText={errors.signatoryDesignation?.message}
              {...register('signatoryDesignation')}
            />
          </Stack>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>Close</Button>
        <Button onClick={doPreview} disabled={preview.isPending}>
          Preview
        </Button>
        <Button variant="contained" onClick={doIssue} disabled={issue.isPending}>
          Issue and file on the record
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
    reportingTo: '',
    respondBy: '',
    signatoryName: '',
    signatoryDesignation: '',
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
