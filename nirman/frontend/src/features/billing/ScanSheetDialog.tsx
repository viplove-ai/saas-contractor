import CameraAltIcon from '@mui/icons-material/CameraAlt';
import {
  Alert,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { useRef, useState } from 'react';
import { useSheetGeometry } from './api';
import { readSheet, type ReadRow } from './reader/readSheet';
import { CONFIDENT } from './reader/digits';
import type { MeasurementLineInput } from './types';

/*
  Photograph the filled sheet, read it here on the phone, and hand the engineer a form to check.

  Everything runs locally: no model is downloaded, no image leaves the device, nothing costs
  anything per page, and it works with no signal — which matters, because the photograph is
  taken where the work is.

  THE READING IS A PROPOSAL, NEVER A DECISION. Every row it is unsure of is marked, and the
  engineer confirms before any of it reaches the grid. The safety net is not the recogniser's
  accuracy; it is that he wrote the quantity on each row himself, so sixteen dimension digits
  and six quantity digits have to agree. A single misread breaks that arithmetic and says which
  row to look at.
*/

interface Props {
  open: boolean;
  onClose: () => void;
  onAccept: (lines: MeasurementLineInput[], writtenTotal: number | null) => void;
}

type State =
  | { phase: 'idle' }
  | { phase: 'reading' }
  | { phase: 'failed'; message: string }
  | { phase: 'read'; rows: ReadRow[]; writtenTotal: number | null; attention: number[] };

export function ScanSheetDialog({ open, onClose, onAccept }: Props) {
  const geometry = useSheetGeometry(open);
  const [state, setState] = useState<State>({ phase: 'idle' });
  const fileInput = useRef<HTMLInputElement>(null);

  const decode = async (file: File): Promise<ImageData> => {
    const bitmap = await createImageBitmap(file);
    // Downscale long edges: a 12-megapixel photo is far more than the reader needs and makes
    // the flood fill slow enough to feel broken on a phone.
    const maxEdge = 1600;
    const scale = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height));
    const width = Math.round(bitmap.width * scale);
    const height = Math.round(bitmap.height * scale);
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('canvas unavailable');
    context.drawImage(bitmap, 0, 0, width, height);
    return context.getImageData(0, 0, width, height);
  };

  const onFile = async (file: File | undefined) => {
    if (!file || !geometry.data) return;
    setState({ phase: 'reading' });
    try {
      const image = await decode(file);
      const result = readSheet(image, geometry.data);
      if (!result.ok) {
        setState({
          phase: 'failed',
          message:
            result.reason === 'MARKS_NOT_FOUND'
              ? 'The four corner marks could not be found. Lay the sheet flat, get all four '
                + 'corners in frame, and try again — or type the rows instead.'
              : 'The page could not be squared up. Photograph it straight on rather than at an '
                + 'angle, or type the rows instead.',
        });
        return;
      }
      setState({
        phase: 'read',
        rows: result.rows.filter((row) => !row.empty),
        writtenTotal: result.writtenTotal.value,
        attention: result.needsAttention,
      });
    } catch {
      setState({
        phase: 'failed',
        message: 'That image could not be read. Try another photo, or type the rows instead.',
      });
    } finally {
      if (fileInput.current) fileInput.current.value = '';
    }
  };

  const accept = () => {
    if (state.phase !== 'read') return;
    onAccept(
      state.rows.map((row) => ({
        location: '',
        nos: row.nos.value ?? 1,
        mult: row.mult.value ?? 1,
        length: row.length.value,
        breadth: row.breadth.value,
        height: row.height.value,
        deduction: false,
      })),
      state.writtenTotal,
    );
    setState({ phase: 'idle' });
    onClose();
  };

  const close = () => {
    setState({ phase: 'idle' });
    onClose();
  };

  const cellText = (cell: { value: number | null; confidence: number }) =>
    cell.value === null ? '—' : String(cell.value);

  const uncertain = (cell: { value: number | null; confidence: number }) =>
    cell.value !== null && cell.confidence < CONFIDENT;

  return (
    <Dialog open={open} onClose={close} fullWidth maxWidth="md">
      <DialogTitle>Read the sheet from a photo</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          {state.phase === 'idle' && (
            <>
              <Alert severity="info">
                Lay the filled sheet flat with all four corner marks in frame. Everything is read
                on this phone — nothing is uploaded, and it works with no signal.
              </Alert>
              <Button
                variant="contained"
                startIcon={<CameraAltIcon />}
                disabled={!geometry.data}
                onClick={() => fileInput.current?.click()}
              >
                {geometry.data ? 'Take or choose a photo' : 'Loading sheet layout…'}
              </Button>
              <Typography variant="caption" color="text.secondary">
                You can always type the rows instead — this only saves the typing.
              </Typography>
            </>
          )}

          {state.phase === 'reading' && (
            <Stack alignItems="center" spacing={2} sx={{ py: 4 }}>
              <CircularProgress />
              <Typography variant="body2">Reading the sheet…</Typography>
            </Stack>
          )}

          {state.phase === 'failed' && (
            <>
              <Alert severity="warning">{state.message}</Alert>
              <Button onClick={() => setState({ phase: 'idle' })}>Try another photo</Button>
            </>
          )}

          {state.phase === 'read' && (
            <>
              {state.attention.length > 0 ? (
                <Alert severity="warning">
                  {state.attention.length} row(s) need checking — either a digit was unclear, or
                  the dimensions do not multiply to the quantity you wrote beside them. Check
                  those before accepting.
                </Alert>
              ) : (
                <Alert severity="success">
                  Every row&apos;s dimensions multiply to the quantity written beside it.
                </Alert>
              )}

              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Row</TableCell>
                    <TableCell align="right">Nos</TableCell>
                    <TableCell align="right">×</TableCell>
                    <TableCell align="right">L</TableCell>
                    <TableCell align="right">B</TableCell>
                    <TableCell align="right">H</TableCell>
                    <TableCell align="right">Qty written</TableCell>
                    <TableCell align="right">Computes to</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {state.rows.map((row) => (
                    <TableRow
                      key={row.index}
                      sx={{ bgcolor: row.agrees ? undefined : 'warning.light' }}
                    >
                      <TableCell>{row.index + 1}</TableCell>
                      {([row.nos, row.mult, row.length, row.breadth, row.height] as const).map(
                        (cell, i) => (
                          <TableCell
                            key={i}
                            align="right"
                            sx={{ fontWeight: uncertain(cell) ? 700 : 400 }}
                          >
                            {cellText(cell)}
                            {uncertain(cell) && ' ?'}
                          </TableCell>
                        ),
                      )}
                      <TableCell align="right">{cellText(row.qty)}</TableCell>
                      <TableCell align="right">
                        {row.computed === null ? '—' : row.computed.toFixed(2)}
                        {!row.agrees && (
                          <Chip size="small" color="warning" label="differs" sx={{ ml: 1 }} />
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>

              <Typography variant="body2" color="text.secondary">
                Total written on the sheet:{' '}
                {state.writtenTotal === null ? '—' : state.writtenTotal.toFixed(2)}
              </Typography>
              <Alert severity="info">
                Accepting fills the form. Nothing is saved until you check the rows and sign.
              </Alert>
            </>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={close}>Cancel</Button>
        {state.phase === 'read' && (
          <Button variant="contained" onClick={accept}>
            Fill the form
          </Button>
        )}
      </DialogActions>

      <input
        ref={fileInput}
        type="file"
        accept="image/*"
        capture="environment"
        hidden
        onChange={(event) => void onFile(event.target.files?.[0])}
      />
    </Dialog>
  );
}
