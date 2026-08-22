import UploadFileIcon from '@mui/icons-material/UploadFile';
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
import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { useCreateProjectFromNit, useParseNit } from '../admin/api';
import type { NitPreview } from '../admin/types';

/*
  Billing a tender the rest of the system is not running.

  Upload the NIT, confirm what was read, and the schedule becomes a project marked
  BILLING_ONLY: no muster, no store, no daily report, and no site picker anywhere in billing.

  It still gets one site, and the person importing it is posted to that site in the same act.
  Authorisation is site-scoped, so a project with no sites has nothing to scope on — and
  without the posting he would create a tender he immediately cannot read, which surfaces as a
  permissions error rather than as the missing assignment it actually is.

  The confirm step here is deliberately thin. The full project form asks about budgets,
  completion dates and guarantee clauses, none of which a bill needs; asking for them would be
  making somebody fill in a form to do a different job.
*/

interface Props {
  open: boolean;
  onClose: () => void;
}

export function ImportTenderDialog({ open, onClose }: Props) {
  const navigate = useNavigate();
  const parse = useParseNit();
  const create = useCreateProjectFromNit();
  const fileInput = useRef<HTMLInputElement>(null);

  const [preview, setPreview] = useState<NitPreview | null>(null);
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);

  const reset = () => {
    setPreview(null);
    setCode('');
    setName('');
    setError(null);
    if (fileInput.current) fileInput.current.value = '';
  };

  const close = () => {
    reset();
    onClose();
  };

  const onFile = async (file: File | undefined) => {
    if (!file) return;
    setError(null);
    try {
      const read = await parse.mutateAsync(file);
      setPreview(read);
      setCode(read.suggestedCode ?? '');
      setName(read.suggestedName ?? '');
    } catch (caught) {
      setError(apiErrorDetail(caught));
    }
  };

  const confirm = async () => {
    if (!preview) return;
    setError(null);
    try {
      const result = await create.mutateAsync({
        attachmentId: preview.attachmentId,
        pageCount: preview.pageCount,
        project: {
          code: code.trim(),
          name: name.trim(),
          nitNumber: preview.nitNumber ?? undefined,
          tenderReference: preview.tenderReference ?? undefined,
          contractValue: preview.contractValue ?? undefined,
        } as never,
        fields: preview.fields,
        scheduleTerms: preview.scheduleTerms,
        // Sent back as read. Anything the reader was unsure of is corrected on the BOQ
        // afterwards — asking somebody to review two hundred lines in a dialog before they can
        // start billing is how a tool goes unused.
        boqLines: preview.boqLines.map((line) => ({
          itemNumber: line.itemNumber,
          description: line.description,
          unitCode: line.unitCode,
          quantity: line.quantity ?? 0,
          rate: line.rate ?? 0,
          workPart: line.workPart ?? null,
          // A reconciliation placeholder the reader emits when the lines do not sum to the
          // stated total. Nothing may ever be charged to one, so the flag has to survive.
          synthetic: line.synthetic,
        })),
        warnings: preview.warnings,
        billingOnly: true,
      });
      close();
      navigate(`/billing/${result.project.id}/sheets`);
    } catch (caught) {
      setError(apiErrorDetail(caught));
    }
  };

  const busy = parse.isPending || create.isPending;

  return (
    <Dialog open={open} onClose={close} fullWidth maxWidth="sm">
      <DialogTitle>Bill a tender</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          {error && <Alert severity="error">{error}</Alert>}

          {!preview && !busy && (
            <>
              <Alert severity="info">
                Upload the tender&apos;s NIT. The schedule of quantities is read out of it, and
                the tender is set up for billing only — no muster, no store, no daily report.
              </Alert>
              <Button
                variant="contained"
                startIcon={<UploadFileIcon />}
                onClick={() => fileInput.current?.click()}
              >
                Choose the NIT PDF
              </Button>
            </>
          )}

          {busy && (
            <Stack alignItems="center" spacing={2} sx={{ py: 4 }}>
              <CircularProgress />
              <Typography variant="body2">
                {parse.isPending ? 'Reading the notice…' : 'Setting up the tender…'}
              </Typography>
            </Stack>
          )}

          {preview && !busy && (
            <>
              <Alert severity="success">
                Read {preview.boqLines.length} schedule line(s) from {preview.fileName}.
              </Alert>
              {preview.warnings.length > 0 && (
                <Alert severity="warning">
                  The reader was unsure about {preview.warnings.length} thing(s). You can correct
                  quantities and rates on the BOQ afterwards.
                </Alert>
              )}
              <TextField
                label="Project code"
                value={code}
                onChange={(event) => setCode(event.target.value)}
                helperText="Short, unique. Letters, digits, dot, underscore and hyphen."
                fullWidth
              />
              <TextField
                label="Name of work"
                value={name}
                onChange={(event) => setName(event.target.value)}
                multiline
                minRows={2}
                fullWidth
              />
              <Typography variant="caption" color="text.secondary">
                The agreement number, contractor and rate adjustments are asked once, when you
                prepare this tender&apos;s first bill.
              </Typography>
            </>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={close}>Cancel</Button>
        {preview && !busy && (
          <Button
            variant="contained"
            disabled={!code.trim() || !name.trim()}
            onClick={() => void confirm()}
          >
            Set up for billing
          </Button>
        )}
      </DialogActions>

      <input
        ref={fileInput}
        type="file"
        accept="application/pdf"
        hidden
        onChange={(event) => void onFile(event.target.files?.[0])}
      />
    </Dialog>
  );
}
