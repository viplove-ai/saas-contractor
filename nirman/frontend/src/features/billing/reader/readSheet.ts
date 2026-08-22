import { assembleField, classifyDigit, CONFIDENT, SIZE, type DigitBitmap, type Reading } from './digits';
import { markCentres, type GeometryBox, type SheetGeometry } from './geometry';
import { applyHomography, solveHomography, type Matrix3 } from './homography';
import { findCornerMarks, findDarkBlobs, looksUpsideDown, otsuThreshold, toGrey } from './marks';

/**
 * Reading a photographed measurement sheet, entirely on the phone.
 *
 * <p>Find the four printed corner marks, solve the transform that maps the photograph onto the
 * printed page's millimetres, then read every box by arithmetic. No model is downloaded, no
 * image leaves the device, and it works in a valley with no signal.</p>
 *
 * <p><b>Nothing here is trusted.</b> Every field carries a confidence, every row is checked
 * against the quantity the engineer wrote beside it, and the result is a proposal shown next to
 * the photograph for him to confirm. The reader's job is to save typing, not to decide what the
 * bill says.</p>
 */

export interface ReadCell {
  value: number | null;
  confidence: number;
}

export interface ReadRow {
  index: number;
  nos: ReadCell;
  mult: ReadCell;
  length: ReadCell;
  breadth: ReadCell;
  height: ReadCell;
  /** What he wrote in the Qty column — the per-row half of the checksum. */
  qty: ReadCell;
  /** nos × mult × dimensions, as the app would compute it. */
  computed: number | null;
  /** Whether the two agree. False means a person looks at this row. */
  agrees: boolean;
  /** True when the row has no ink at all — most sheets end with several. */
  empty: boolean;
}

export interface ReadResult {
  rows: ReadRow[];
  writtenTotal: ReadCell;
  /** Rows whose own arithmetic disagrees, or which hold a low-confidence digit. */
  needsAttention: number[];
  /** The corrected page, for showing beside the form. */
  rectified: ImageData | null;
}

export type ReadFailure =
  | { ok: false; reason: 'MARKS_NOT_FOUND' }
  | { ok: false; reason: 'TRANSFORM_FAILED' };

export type ReadOutcome = ({ ok: true } & ReadResult) | ReadFailure;

/** Samples the source photograph at a page-millimetre coordinate. */
function sampleAt(
  source: Uint8Array,
  width: number,
  height: number,
  h: Matrix3,
  mmX: number,
  mmY: number,
): number {
  const p = applyHomography(h, { x: mmX, y: mmY });
  const x = Math.round(p.x);
  const y = Math.round(p.y);
  if (x < 0 || y < 0 || x >= width || y >= height) return 255;
  return source[y * width + x] ?? 255;
}

/**
 * Crops one box out of the photograph and normalises it to a fixed bitmap.
 *
 * <p>Insets the box by a fraction of its size before sampling, so the printed rule itself never
 * becomes ink. That inset is why the boxes are printed in a dropout colour and why they are a
 * little larger than the digit needs — the rule has to be avoidable without clipping what the
 * engineer wrote.</p>
 */
function cropBox(
  source: Uint8Array,
  width: number,
  height: number,
  h: Matrix3,
  box: GeometryBox,
  threshold: number,
): DigitBitmap {
  const insetX = box.widthMm * 0.16;
  const insetY = box.heightMm * 0.12;
  const left = box.leftMm + insetX;
  const top = box.topMm + insetY;
  const w = box.widthMm - insetX * 2;
  const hgt = box.heightMm - insetY * 2;

  const pixels = new Uint8Array(SIZE * SIZE);
  let ink = 0;
  for (let sy = 0; sy < SIZE; sy += 1) {
    for (let sx = 0; sx < SIZE; sx += 1) {
      const value = sampleAt(
        source,
        width,
        height,
        h,
        left + ((sx + 0.5) / SIZE) * w,
        top + ((sy + 0.5) / SIZE) * hgt,
      );
      if (value <= threshold) {
        pixels[sy * SIZE + sx] = 1;
        ink += 1;
      }
    }
  }
  return { pixels, inkRatio: ink / (SIZE * SIZE) };
}

function toCell(readings: Reading[], decimals: number): ReadCell {
  const { value, confidence } = assembleField(readings, decimals);
  return { value, confidence };
}

/** Two decimals, half away from zero — the same rounding the server and the grid both use. */
function round2(value: number): number {
  if (!Number.isFinite(value)) return 0;
  const sign = value < 0 ? -1 : 1;
  const magnitude = Math.abs(value);
  return sign * Number(`${Math.round(Number(`${magnitude.toPrecision(12)}e+2`))}e-2`);
}

function computeRow(row: ReadRow): number | null {
  if (row.nos.value === null && row.mult.value === null) return null;
  let value = (row.nos.value ?? 1) * (row.mult.value ?? 1);
  for (const dimension of [row.length.value, row.breadth.value, row.height.value]) {
    if (dimension !== null) value *= dimension;
  }
  return round2(value);
}

export function readSheet(
  image: ImageData,
  geometry: SheetGeometry,
  options: { allowRotate?: boolean } = {},
): ReadOutcome {
  const { width, height } = image;
  const grey = toGrey(image.data, width, height);
  const threshold = otsuThreshold(grey);

  // Marks are solid black; a generous minimum area throws away specks and text.
  const minPixels = Math.max(20, Math.floor((width * height) / 40000));
  const blobs = findDarkBlobs(grey, width, height, Math.min(threshold, 110), minPixels);

  if (options.allowRotate !== false && looksUpsideDown(blobs, width, height)) {
    // The wide mark is printed bottom-left. Finding it at the top means the page is upside
    // down, and reading it as it stands would reverse every row on the sheet.
    return readSheet(rotate180(image), geometry, { allowRotate: false });
  }

  const found = findCornerMarks(blobs, width, height);
  if (!found) return { ok: false, reason: 'MARKS_NOT_FOUND' };

  const expected = markCentres(geometry);
  const h = solveHomography(
    [expected.topLeft, expected.topRight, expected.bottomLeft, expected.bottomRight],
    [found.topLeft, found.topRight, found.bottomLeft, found.bottomRight],
  );
  if (!h) return { ok: false, reason: 'TRANSFORM_FAILED' };

  const byRow = new Map<number, Map<string, Reading[]>>();
  for (const box of geometry.boxes) {
    const bitmap = cropBox(grey, width, height, h, box, threshold);
    const reading = classifyDigit(bitmap);
    if (!byRow.has(box.row)) byRow.set(box.row, new Map());
    const fields = byRow.get(box.row)!;
    if (!fields.has(box.field)) fields.set(box.field, []);
    fields.get(box.field)!.push(reading);
  }

  const decimalsOf = (name: string) =>
    geometry.fields.find((field) => field.name === name)?.decimals ?? 0;

  const rows: ReadRow[] = [];
  const needsAttention: number[] = [];

  for (let index = 0; index < geometry.rows; index += 1) {
    const fields = byRow.get(index) ?? new Map<string, Reading[]>();
    const cell = (name: string) => toCell(fields.get(name) ?? [], decimalsOf(name));

    const row: ReadRow = {
      index,
      nos: cell('nos'),
      mult: cell('mult'),
      length: cell('length'),
      breadth: cell('breadth'),
      height: cell('height'),
      qty: cell('qty'),
      computed: null,
      agrees: true,
      empty: false,
    };
    row.empty =
      row.nos.value === null &&
      row.mult.value === null &&
      row.length.value === null &&
      row.qty.value === null;
    row.computed = row.empty ? null : computeRow(row);

    // The row's own proof: he wrote the quantity, and the dimensions multiply to something.
    // Sixteen digits and six have to agree, so a single misread breaks it and says which row.
    row.agrees =
      row.empty ||
      row.qty.value === null ||
      row.computed === null ||
      Math.abs(row.computed - row.qty.value) <= 0.01;

    const lowConfidence = [row.nos, row.mult, row.length, row.breadth, row.height, row.qty].some(
      (c) => c.value !== null && c.confidence < CONFIDENT,
    );
    if (!row.empty && (!row.agrees || lowConfidence)) needsAttention.push(index);

    rows.push(row);
  }

  const totalFields = byRow.get(-1)?.get('total') ?? [];
  return {
    ok: true,
    rows,
    writtenTotal: toCell(totalFields, 2),
    needsAttention,
    rectified: null,
  };
}

/** Rotating the whole frame is cheaper and far less error-prone than reading it backwards. */
function rotate180(image: ImageData): ImageData {
  const { width, height, data } = image;
  const out = new Uint8ClampedArray(data.length);
  for (let i = 0, j = data.length - 4; i < data.length; i += 4, j -= 4) {
    out[i] = data[j]!;
    out[i + 1] = data[j + 1]!;
    out[i + 2] = data[j + 2]!;
    out[i + 3] = data[j + 3]!;
  }
  return new ImageData(out, width, height);
}
