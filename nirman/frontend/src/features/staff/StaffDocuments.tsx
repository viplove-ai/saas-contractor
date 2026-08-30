import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import DescriptionOutlinedIcon from '@mui/icons-material/DescriptionOutlined';
import OpenInNewIcon from '@mui/icons-material/OpenInNew';
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  IconButton,
  MenuItem,
  Paper,
  Skeleton,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { PhotoThumb } from '../../shared/PhotoThumb';
import { PickFileButtons } from '../../shared/PickFileButtons';
import {
  useAddStaffDocument,
  useRemoveStaffDocument,
  useStaffDocumentUrl,
  useStaffDocuments,
} from './api';
import { DOCUMENT_LABEL, type StaffDocument, type StaffDocumentType } from './types';

const TYPES = Object.keys(DOCUMENT_LABEL) as StaffDocumentType[];

/** Images and PDFs. A signed appointment letter is nearly always the second. */
const ACCEPT = 'image/*,application/pdf';

/**
 * The papers behind the record: the Aadhaar card the last four digits were read off, the
 * cheque the account number was copied from, the letter somebody signed.
 *
 * <p>Every figure above this section was typed off one of these and the documents themselves
 * stayed in a folder at head office — so the record could state the account number and never
 * show the passbook, and the man who typed it was the only person who could say whether he
 * read it right.</p>
 *
 * <p><b>It saves as it goes</b>, unlike everything else in this dialog, and says so. A file
 * is not a form field: it is uploaded, stored and pointed at, and holding it in the browser
 * until somebody presses Save would mean four scans lost to a mistyped IFSC. It is also why
 * this works on a member whose record has never been saved — the papers hang off the person,
 * not off a profile row that may not exist yet.</p>
 *
 * <p><b>What it is comes before the file</b>, and the buttons stay disabled until it is
 * answered. A register of eight scans all called "Something else" answers none of the
 * questions it exists for, and nobody goes back to relabel them.</p>
 */
export function StaffDocuments({ userId, memberName }: { userId: string; memberName: string }) {
  const documents = useStaffDocuments(userId);
  const add = useAddStaffDocument();
  const [docType, setDocType] = useState<StaffDocumentType | ''>('');
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [removing, setRemoving] = useState<StaffDocument | null>(null);

  const pick = async (files: File[]) => {
    const file = files[0];
    if (!file || !docType) {
      return;
    }
    setError(null);
    try {
      await add.mutateAsync({ userId, file, docType, note });
      setDocType('');
      setNote('');
    } catch (problem) {
      setError(apiErrorDetail(problem));
    }
  };

  return (
    <Stack spacing={2}>
      <Typography variant="body2" color="text.secondary">
        Scans and photographs of what is on file for {memberName}. These are saved as they are
        added — the button at the foot of this dialog is for the fields above.
      </Typography>

      {error && <Alert severity="error">{error}</Alert>}

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField
          select
          label="What it is"
          value={docType}
          onChange={(event) => setDocType(event.target.value as StaffDocumentType)}
          sx={{ flex: { sm: '0 0 240px' } }}
          helperText={docType ? ' ' : 'Say what the paper is first'}
        >
          {TYPES.map((type) => (
            <MenuItem key={type} value={type}>
              {DOCUMENT_LABEL[type]}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          label="Note (optional)"
          value={note}
          onChange={(event) => setNote(event.target.value)}
          fullWidth
          inputProps={{ maxLength: 200 }}
          helperText="Anything the list above cannot say — “front and back”, “the 2019 one”"
        />
      </Stack>

      <PickFileButtons
        label={add.isPending ? 'Sending…' : 'Photograph it'}
        deviceLabel="From device"
        accept={ACCEPT}
        busy={add.isPending || !docType}
        onPick={(files) => void pick(files)}
      />

      {documents.isLoading && <Skeleton variant="rounded" height={72} />}
      {documents.isError && <Alert severity="error">{apiErrorDetail(documents.error)}</Alert>}

      {documents.data?.length === 0 && (
        <Typography variant="body2" color="text.secondary">
          Nothing on file yet.
        </Typography>
      )}

      <Stack spacing={1.5}>
        {documents.data?.map((document) => (
          <DocumentRow
            key={document.id}
            document={document}
            onRemove={() => setRemoving(document)}
          />
        ))}
      </Stack>

      <ConfirmRemoval
        document={removing}
        userId={userId}
        memberName={memberName}
        onClose={() => setRemoving(null)}
      />
    </Stack>
  );
}

/**
 * One paper, shown rather than named.
 *
 * <p>A picture is drawn, because {@code IMG_20260812_104533.jpg} is the same nine characters
 * whether it shows an Aadhaar card, the wrong man's Aadhaar card or a thumb over the lens,
 * and all three are obvious as a thumbnail. A PDF cannot be drawn, so it is offered to be
 * opened instead — a broken image icon would say the file was lost, which it is not.</p>
 */
function DocumentRow({
  document,
  onRemove,
}: {
  document: StaffDocument;
  onRemove: () => void;
}) {
  const link = useStaffDocumentUrl(document.attachmentId);
  const name = DOCUMENT_LABEL[document.docType];

  return (
    <Paper elevation={0} sx={{ p: 1.5, border: 1, borderColor: 'divider' }}>
      <Stack direction="row" spacing={2} alignItems="center">
        <Box sx={{ width: 64, flex: '0 0 auto' }}>
          {link.isLoading && <Skeleton variant="rounded" width={64} height={64} />}
          {document.image && link.data && (
            <PhotoThumb src={link.data.url} name={name} size={64} />
          )}
          {!document.image && (
            <Box
              sx={{
                width: 64,
                height: 64,
                borderRadius: 1,
                border: 1,
                borderColor: 'divider',
                display: 'grid',
                placeItems: 'center',
                color: 'text.secondary',
              }}
            >
              <DescriptionOutlinedIcon />
            </Box>
          )}
        </Box>

        <Stack spacing={0.25} sx={{ minWidth: 0, flex: 1 }}>
          <Typography fontWeight={600}>{name}</Typography>
          {document.note && (
            <Typography variant="body2" color="text.secondary">
              {document.note}
            </Typography>
          )}
          <Typography variant="caption" color="text.secondary" noWrap>
            {document.fileName ?? 'The file behind this row is missing'}
          </Typography>
        </Stack>

        <Stack direction="row" spacing={0.5} alignItems="center">
          {/*
            Opening is the whole point for a PDF and a second way in for a scan: a thumbnail
            answers which card it is, and only the full page answers whether the number on it
            can be read.
          */}
          {link.data && (
            <Button
              size="small"
              startIcon={<OpenInNewIcon />}
              href={link.data.url}
              target="_blank"
              rel="noreferrer"
            >
              Open
            </Button>
          )}
          <IconButton aria-label={`Remove ${name}`} onClick={onRemove} size="small">
            <DeleteOutlineIcon fontSize="small" />
          </IconButton>
        </Stack>
      </Stack>
    </Paper>
  );
}

/**
 * Asked before it goes, and no reason box.
 *
 * <p>A deletion reason is demanded where a vanished row is indistinguishable from data loss —
 * a project, a site, a daily report. A wrong scan is not that: what was read off it is typed
 * into the record above and stays there, and the ordinary reason is standing in front of the
 * person doing it. What the question is really for is the tap: the file goes with the row.</p>
 */
function ConfirmRemoval({
  document,
  userId,
  memberName,
  onClose,
}: {
  document: StaffDocument | null;
  userId: string;
  memberName: string;
  onClose: () => void;
}) {
  const remove = useRemoveStaffDocument();
  const [error, setError] = useState<string | null>(null);

  const confirm = async () => {
    if (!document) {
      return;
    }
    setError(null);
    try {
      await remove.mutateAsync({ userId, documentId: document.id });
      onClose();
    } catch (problem) {
      setError(apiErrorDetail(problem));
    }
  };

  return (
    <Dialog open={Boolean(document)} onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Remove this paper?</DialogTitle>
      <DialogContent>
        {error && <Alert severity="error">{error}</Alert>}
        <DialogContentText>
          {document && DOCUMENT_LABEL[document.docType]} comes off {memberName}’s record, and
          the file goes with it. What was typed off it stays where it is.
        </DialogContentText>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Keep it</Button>
        <Button color="error" onClick={() => void confirm()} disabled={remove.isPending}>
          Remove
        </Button>
      </DialogActions>
    </Dialog>
  );
}
