import AddIcon from '@mui/icons-material/Add';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { useRef, useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import {
  uploadDocumentFile,
  useReferenceDocuments,
  useSaveReferenceDocument,
  useSupersedeDocument,
} from './api';
import { DOCUMENT_KIND_LABEL, type DocumentKind, type ReferenceDocument } from './types';

/*
  The shelf: the editions a bill is prepared against.

  Two things this screen has to make obvious, because they are what the drawer and the laptop
  cannot.

  Which edition is current — so a tender let this month cites the right one. And which edition
  a past tender was priced under, which is a different question with a different answer, and
  the reason superseding here changes nothing about any agreement. A bill raised in 2026
  against a 2025 agreement is still a DSR 2023 bill.

  Registering an edition without a file is allowed on purpose. The office knows the notice
  cites DSR 2023 the moment it reads it, and may not have a copy for weeks; a form that
  demanded the PDF would make them wait to record something they already know.
*/

const KINDS: DocumentKind[] = ['DSR', 'DAR', 'COST_INDEX', 'SPECIFICATION', 'CIRCULAR', 'OTHER'];

const EMPTY = {
  kind: 'DSR' as DocumentKind,
  code: '',
  title: '',
  editionYear: '',
  station: '',
  indexPercent: '',
  notes: '',
};

export function VaultPage() {
  const documents = useReferenceDocuments();
  const save = useSaveReferenceDocument();
  const supersede = useSupersedeDocument();

  const [open, setOpen] = useState(false);
  const [form, setForm] = useState(EMPTY);
  const [error, setError] = useState<string | null>(null);
  const [uploadingFor, setUploadingFor] = useState<string | null>(null);
  const [supersedeFor, setSupersedeFor] = useState<ReferenceDocument | null>(null);
  const [replacement, setReplacement] = useState('');
  const fileInput = useRef<HTMLInputElement>(null);
  const pendingUpload = useRef<string | null>(null);

  const isCostIndex = form.kind === 'COST_INDEX';

  const set = (key: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [key]: event.target.value }));

  const submit = async () => {
    setError(null);
    try {
      await save.mutateAsync({
        kind: form.kind,
        code: form.code.trim(),
        title: form.title.trim(),
        editionYear: form.editionYear ? Number(form.editionYear) : null,
        station: form.station.trim() || null,
        // Only a cost index carries one; the server refuses it on anything else.
        indexPercent: isCostIndex && form.indexPercent ? form.indexPercent : null,
        notes: form.notes.trim() || null,
      } as never);
      setForm(EMPTY);
      setOpen(false);
    } catch (caught) {
      setError(apiErrorDetail(caught));
    }
  };

  const pickFile = (documentId: string) => {
    pendingUpload.current = documentId;
    fileInput.current?.click();
  };

  const onFileChosen = async (file: File | undefined) => {
    const documentId = pendingUpload.current;
    if (!file || !documentId) return;
    setUploadingFor(documentId);
    setError(null);
    try {
      await uploadDocumentFile(documentId, file);
      await documents.refetch();
    } catch (caught) {
      setError(apiErrorDetail(caught));
    } finally {
      setUploadingFor(null);
      pendingUpload.current = null;
      if (fileInput.current) fileInput.current.value = '';
    }
  };

  const doSupersede = async () => {
    if (!supersedeFor || !replacement) return;
    setError(null);
    try {
      await supersede.mutateAsync({ id: supersedeFor.id, replacedBy: replacement });
      setSupersedeFor(null);
      setReplacement('');
    } catch (caught) {
      setError(apiErrorDetail(caught));
    }
  };

  if (documents.isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', p: 6 }}>
        <CircularProgress />
      </Box>
    );
  }

  const rows = documents.data ?? [];

  return (
    <Stack spacing={2} sx={{ p: 2, pb: 10 }}>
      <Typography variant="h6">Reference documents</Typography>
      <Typography variant="body2" color="text.secondary">
        The schedules of rates, cost index circulars and specifications your bills are prepared
        against. A tender stays priced under the edition it cites — publishing a newer one says
        what to use next and changes nothing already billed.
      </Typography>

      {error && <Alert severity="error">{error}</Alert>}

      <Button variant="contained" startIcon={<AddIcon />} onClick={() => setOpen(true)}>
        Add an edition
      </Button>

      {rows.length === 0 ? (
        <Alert severity="info">
          Nothing on the shelf yet. Add the DSR and cost index your tenders cite — you can
          register an edition now and attach the PDF when you find it.
        </Alert>
      ) : (
        <Paper>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Code</TableCell>
                <TableCell>Document</TableCell>
                <TableCell align="right">Edition</TableCell>
                <TableCell>File</TableCell>
                <TableCell align="right">Status</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((document) => (
                <TableRow key={document.id}>
                  <TableCell>{document.code}</TableCell>
                  <TableCell>
                    <Typography variant="body2">{document.title}</Typography>
                    <Typography variant="caption" color="text.secondary">
                      {DOCUMENT_KIND_LABEL[document.kind]}
                      {document.indexPercent ? ` · ${document.indexPercent}%` : ''}
                      {document.station ? ` · ${document.station}` : ''}
                    </Typography>
                  </TableCell>
                  <TableCell align="right">{document.editionYear ?? '—'}</TableCell>
                  <TableCell>
                    {document.attachmentId ? (
                      <Chip size="small" label="Held" color="success" variant="outlined" />
                    ) : (
                      <Tooltip title="Upload the PDF">
                        <IconButton
                          size="small"
                          disabled={uploadingFor === document.id}
                          onClick={() => pickFile(document.id)}
                        >
                          <UploadFileIcon fontSize="small" />
                        </IconButton>
                      </Tooltip>
                    )}
                  </TableCell>
                  <TableCell align="right">
                    {document.status === 'CURRENT' ? (
                      <Button size="small" onClick={() => setSupersedeFor(document)}>
                        Supersede
                      </Button>
                    ) : (
                      <Chip size="small" label={document.status} variant="outlined" />
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
      )}

      <input
        ref={fileInput}
        type="file"
        hidden
        onChange={(event) => void onFileChosen(event.target.files?.[0])}
      />

      <Dialog open={open} onClose={() => setOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>Add an edition</DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2} sx={{ pt: 1 }}>
            <TextField select label="Kind" value={form.kind} onChange={set('kind')} fullWidth>
              {KINDS.map((kind) => (
                <MenuItem key={kind} value={kind}>
                  {DOCUMENT_KIND_LABEL[kind]}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="Code"
              value={form.code}
              onChange={set('code')}
              helperText="How people refer to it — DSR-2023, CI-MUSSOORIE-2025"
              fullWidth
            />
            <TextField label="Title" value={form.title} onChange={set('title')} fullWidth />
            <Stack direction="row" spacing={2}>
              <TextField
                label="Edition year"
                type="number"
                value={form.editionYear}
                onChange={set('editionYear')}
                sx={{ flex: 1 }}
              />
              {isCostIndex && (
                <TextField
                  label="Index %"
                  type="number"
                  value={form.indexPercent}
                  onChange={set('indexPercent')}
                  sx={{ flex: 1 }}
                />
              )}
            </Stack>
            {isCostIndex && (
              <TextField label="Station" value={form.station} onChange={set('station')} fullWidth />
            )}
            <TextField
              label="Notes"
              value={form.notes}
              onChange={set('notes')}
              multiline
              minRows={2}
              fullWidth
            />
            <Alert severity="info">
              You can add the file later. Registering the edition now is what lets a tender cite
              it.
            </Alert>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Cancel</Button>
          <Button variant="contained" disabled={save.isPending} onClick={() => void submit()}>
            Add
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={Boolean(supersedeFor)} onClose={() => setSupersedeFor(null)} fullWidth maxWidth="xs">
        <DialogTitle>Supersede {supersedeFor?.code}</DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2} sx={{ pt: 1 }}>
            <Alert severity="info">
              This says what to use next. Tenders already priced under {supersedeFor?.code} keep
              citing it, and their bills do not change.
            </Alert>
            <TextField
              select
              label="Replaced by"
              value={replacement}
              onChange={(event) => setReplacement(event.target.value)}
              fullWidth
            >
              {rows
                .filter((d) => d.id !== supersedeFor?.id && d.kind === supersedeFor?.kind)
                .map((d) => (
                  <MenuItem key={d.id} value={d.id}>
                    {d.code} — {d.title}
                  </MenuItem>
                ))}
            </TextField>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setSupersedeFor(null)}>Cancel</Button>
          <Button variant="contained" disabled={!replacement} onClick={() => void doSupersede()}>
            Supersede
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
