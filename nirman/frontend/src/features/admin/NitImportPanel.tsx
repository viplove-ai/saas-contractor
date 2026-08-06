import UploadFileIcon from '@mui/icons-material/UploadFile';
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  AlertTitle,
  Box,
  Button,
  CircularProgress,
  Stack,
  Typography,
} from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import { useRef, useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { NitBoqReviewTable } from './NitBoqReviewTable';
import { useParseNit } from './api';
import type { NitPreview, PreviewBoqLine } from './types';

interface Props {
  preview: NitPreview | null;
  onParsed: (preview: NitPreview) => void;
  onLinesChange: (lines: PreviewBoqLine[]) => void;
  disabled?: boolean;
}

const INR = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 });

/**
 * The upload half of the Add-a-project dialog: drop a tender notice, see what it says.
 *
 * <p>What the panel is really for is doubt. The reader is good on a well-formed CPWD notice
 * and much less good on an unusual one, and it cannot tell the difference. So it says what it
 * found, says plainly what it is unsure about, and puts the schedule in front of the user
 * before anything is saved.</p>
 */
export function NitImportPanel({ preview, onParsed, onLinesChange, disabled = false }: Props) {
  const parse = useParseNit();
  const inputRef = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);

  async function handleFile(file: File | undefined) {
    if (!file) {
      return;
    }
    setError(null);
    try {
      onParsed(await parse.mutateAsync(file));
    } catch (cause) {
      setError(apiErrorDetail(cause));
    } finally {
      // Clear the input so re-picking the same file after a failure still fires a change.
      if (inputRef.current) {
        inputRef.current.value = '';
      }
    }
  }

  function updateLine(index: number, patch: Partial<PreviewBoqLine>) {
    if (!preview) {
      return;
    }
    onLinesChange(
      preview.boqLines.map((line) => (line.index === index ? { ...line, ...patch } : line)),
    );
  }

  const scheduleValue =
    preview?.boqLines.reduce((sum, line) => sum + (line.quantity ?? 0) * (line.rate ?? 0), 0) ?? 0;

  return (
    <Stack spacing={2}>
      <input
        ref={inputRef}
        type="file"
        accept="application/pdf"
        hidden
        data-testid="nit-file-input"
        onChange={(event) => void handleFile(event.target.files?.[0])}
      />

      <Box
        sx={{
          border: 1,
          borderStyle: 'dashed',
          borderColor: 'divider',
          borderRadius: 1,
          p: 3,
          textAlign: 'center',
        }}
      >
        <Stack spacing={1} alignItems="center">
          {parse.isPending ? (
            <>
              <CircularProgress size={28} />
              <Typography variant="body2" color="text.secondary">
                Reading the notice. A 200-page tender takes a few seconds.
              </Typography>
            </>
          ) : (
            <>
              <UploadFileIcon color="action" />
              <Button
                variant="outlined"
                onClick={() => inputRef.current?.click()}
                disabled={disabled}
              >
                {preview ? 'Choose a different PDF' : 'Choose NIT PDF'}
              </Button>
              <Typography variant="caption" color="text.secondary">
                {preview
                  ? `${preview.fileName} — ${preview.pageCount} pages`
                  : 'The tender notice as published, up to 15 MB.'}
              </Typography>
            </>
          )}
        </Stack>
      </Box>

      {error && <Alert severity="error">{error}</Alert>}

      {preview && (
        <>
          <Alert severity="success" variant="outlined">
            Read {preview.boqLines.length} schedule lines worth ₹{INR.format(scheduleValue)}
            {preview.contractValue != null && (
              <>
                {' '}
                against a stated contract value of ₹{INR.format(preview.contractValue)}
              </>
            )}
            . Everything below is editable before you save.
          </Alert>

          {preview.warnings.length > 0 && (
            <Alert severity="warning">
              <AlertTitle>Worth checking</AlertTitle>
              <Stack component="ul" sx={{ pl: 2, m: 0 }} spacing={0.5}>
                {preview.warnings.map((warning) => (
                  <Typography component="li" variant="body2" key={warning}>
                    {warning}
                  </Typography>
                ))}
              </Stack>
            </Alert>
          )}

          {/*
            Collapsed by default. The common case is that the schedule is right, and three
            hundred rows the user did not ask to see is a wall, not information.
          */}
          <Accordion disableGutters>
            <AccordionSummary expandIcon={<ExpandMoreIcon />}>
              <Typography variant="subtitle2">
                {preview.boqLines.length} BOQ lines (₹{INR.format(scheduleValue)}) — review
              </Typography>
            </AccordionSummary>
            <AccordionDetails>
              <NitBoqReviewTable
                lines={preview.boqLines}
                onChange={updateLine}
                disabled={disabled}
              />
            </AccordionDetails>
          </Accordion>
        </>
      )}
    </Stack>
  );
}
