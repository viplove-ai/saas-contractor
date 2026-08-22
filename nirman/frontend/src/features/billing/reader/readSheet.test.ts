import { describe, expect, it } from 'vitest';
import { applyHomography, solveHomography } from './homography';
import { findCornerMarks, findDarkBlobs, looksUpsideDown, otsuThreshold, toGrey } from './marks';
import { markCentres, type SheetGeometry } from './geometry';
import { readSheet } from './readSheet';

/*
  These tests render a synthetic sheet — corner marks, boxes, and strokes inside them — and read
  it back. That round trip is the thing worth proving: the printer and the reader work from one
  geometry, so a page drawn at these millimetres has to be findable at the same millimetres
  after a perspective transform.

  What they deliberately do not prove is recognition accuracy. That needs photographs of a real
  engineer's handwriting, and asserting it against synthetic strokes would only measure how well
  the classifier reads shapes this file drew. The safety net is elsewhere and is tested here:
  the row checksum, and the fact that nothing is saved without a person confirming it.
*/

const geometry: SheetGeometry = {
  pageWidthMm: 210,
  pageHeightMm: 297,
  markInsetMm: 12,
  markSizeMm: 6,
  markWideSizeMm: 12,
  rows: 2,
  fields: [
    { name: 'nos', digits: 2, decimals: 0 },
    { name: 'qty', digits: 4, decimals: 2 },
  ],
  boxes: [
    { row: 0, field: 'nos', digit: 0, leftMm: 56, topMm: 98, widthMm: 4.8, heightMm: 8 },
    { row: 0, field: 'nos', digit: 1, leftMm: 61.3, topMm: 98, widthMm: 4.8, heightMm: 8 },
    { row: 1, field: 'nos', digit: 0, leftMm: 56, topMm: 110, widthMm: 4.8, heightMm: 8 },
    { row: 1, field: 'nos', digit: 1, leftMm: 61.3, topMm: 110, widthMm: 4.8, heightMm: 8 },
  ],
};

const SCALE = 4; // px per mm

function blankPage(): { data: Uint8ClampedArray; width: number; height: number } {
  const width = Math.round(geometry.pageWidthMm * SCALE);
  const height = Math.round(geometry.pageHeightMm * SCALE);
  const data = new Uint8ClampedArray(width * height * 4).fill(255);
  return { data, width, height };
}

function fillRect(
  page: { data: Uint8ClampedArray; width: number },
  xMm: number,
  yMm: number,
  wMm: number,
  hMm: number,
) {
  for (let y = Math.round(yMm * SCALE); y < Math.round((yMm + hMm) * SCALE); y += 1) {
    for (let x = Math.round(xMm * SCALE); x < Math.round((xMm + wMm) * SCALE); x += 1) {
      const i = (y * page.width + x) * 4;
      page.data[i] = 0;
      page.data[i + 1] = 0;
      page.data[i + 2] = 0;
    }
  }
}

/** Draws the four marks exactly where the geometry says they are printed. */
function withMarks(page: { data: Uint8ClampedArray; width: number }, upsideDown = false) {
  const i = geometry.markInsetMm;
  const s = geometry.markSizeMm;
  const ws = geometry.markWideSizeMm;
  const right = geometry.pageWidthMm - i - s;
  const bottom = geometry.pageHeightMm - i - s;
  fillRect(page, i, i, upsideDown ? ws : s, s);
  fillRect(page, right, i, s, s);
  fillRect(page, i, bottom, upsideDown ? s : ws, s);
  fillRect(page, right, bottom, s, s);
}

describe('finding the printed corner marks', () => {
  it('finds all four on a clean page', () => {
    const page = blankPage();
    withMarks(page);
    const grey = toGrey(page.data, page.width, page.height);
    const blobs = findDarkBlobs(grey, page.width, page.height, otsuThreshold(grey), 20);
    const marks = findCornerMarks(blobs, page.width, page.height);

    expect(marks).not.toBeNull();
    const expected = markCentres(geometry);
    expect(marks!.topLeft.x / SCALE).toBeCloseTo(expected.topLeft.x, 0);
    expect(marks!.bottomRight.y / SCALE).toBeCloseTo(expected.bottomRight.y, 0);
  });

  /** Three marks and a guess is worse than no transform: it reads confidently and wrongly. */
  it('refuses when a corner is missing', () => {
    const page = blankPage();
    const i = geometry.markInsetMm;
    const s = geometry.markSizeMm;
    fillRect(page, i, i, s, s);
    fillRect(page, geometry.pageWidthMm - i - s, i, s, s);
    const grey = toGrey(page.data, page.width, page.height);
    const blobs = findDarkBlobs(grey, page.width, page.height, otsuThreshold(grey), 20);

    expect(findCornerMarks(blobs, page.width, page.height)).toBeNull();
  });

  /**
   * The wide mark is printed bottom-left. Seeing it at the top means the sheet was photographed
   * upside down — and reading it as it stands would reverse every row.
   */
  it('notices a page photographed upside down', () => {
    const upright = blankPage();
    withMarks(upright);
    const uprightGrey = toGrey(upright.data, upright.width, upright.height);
    const uprightBlobs = findDarkBlobs(
      uprightGrey, upright.width, upright.height, otsuThreshold(uprightGrey), 20);
    expect(looksUpsideDown(uprightBlobs, upright.width, upright.height)).toBe(false);

    const flipped = blankPage();
    withMarks(flipped, true);
    const flippedGrey = toGrey(flipped.data, flipped.width, flipped.height);
    const flippedBlobs = findDarkBlobs(
      flippedGrey, flipped.width, flipped.height, otsuThreshold(flippedGrey), 20);
    expect(looksUpsideDown(flippedBlobs, flipped.width, flipped.height)).toBe(true);
  });
});

describe('mapping a photograph onto the printed page', () => {
  /** The round trip the whole design rests on: page millimetres in, page millimetres out. */
  it('solves a transform that puts a known point back where it belongs', () => {
    const expected = markCentres(geometry);
    // A plausible phone photograph: scaled, and keystoned because the far edge is further away.
    const photographed = [
      { x: 40, y: 30 },
      { x: 700, y: 55 },
      { x: 55, y: 980 },
      { x: 690, y: 950 },
    ];
    const h = solveHomography(
      [expected.topLeft, expected.topRight, expected.bottomLeft, expected.bottomRight],
      photographed,
    )!;
    expect(h).not.toBeNull();

    const back = applyHomography(h, expected.topRight);
    expect(back.x).toBeCloseTo(700, 3);
    expect(back.y).toBeCloseTo(55, 3);
  });

  /** Degenerate points must fail loudly, not return a transform that is quietly nonsense. */
  it('refuses collinear points', () => {
    const collinear = [
      { x: 0, y: 0 },
      { x: 10, y: 0 },
      { x: 20, y: 0 },
      { x: 30, y: 0 },
    ];
    expect(solveHomography(collinear, collinear)).toBeNull();
  });
});

describe('reading a sheet', () => {
  it('reports marks not found rather than guessing at an unreadable photo', () => {
    const page = blankPage();
    const image = { data: page.data, width: page.width, height: page.height } as ImageData;
    const result = readSheet(image, geometry, { allowRotate: false });

    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).toBe('MARKS_NOT_FOUND');
  });

  it('reads an empty but well-marked sheet as empty rows, not as zeros', () => {
    const page = blankPage();
    withMarks(page);
    const image = { data: page.data, width: page.width, height: page.height } as ImageData;
    const result = readSheet(image, geometry, { allowRotate: false });

    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.rows).toHaveLength(geometry.rows);
    // An unwritten box is not a zero — that distinction is the whole of the shape system.
    expect(result.rows.every((row) => row.empty)).toBe(true);
    expect(result.rows.every((row) => row.nos.value === null)).toBe(true);
    expect(result.needsAttention).toHaveLength(0);
  });
});
