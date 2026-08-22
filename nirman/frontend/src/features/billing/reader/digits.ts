/**
 * Reading a single handwritten digit out of a box whose position is already known.
 *
 * <p><b>On what this is and is not.</b> Recognising free handwriting is hard. Recognising one
 * digit, isolated, already deskewed, inside a box the reader placed by arithmetic, is a much
 * smaller problem — and it is the only problem this file has, because the printed sheet and the
 * homography did the rest.</p>
 *
 * <p>The classifier is a nearest-centroid over a small set of structural features. It is
 * deliberately simple and deliberately <b>not trusted on its own</b>: every reading carries a
 * confidence, low-confidence cells are held back for the engineer, and the row checksum below
 * catches what slips through. Nothing this file returns is ever saved without a person
 * confirming it.</p>
 *
 * <p>It is trained on nothing yet. The centroids are hand-specified from the shape of the
 * digits, which is enough to be useful with the checksum and not enough to be trusted without
 * it. Replacing this with a small CNN trained on the engineer's own hand is a drop-in: the
 * interface is one function from a bitmap to a digit and a confidence, and everything around it
 * stays.</p>
 */

/** A digit box, cropped, thresholded and normalised. */
export interface DigitBitmap {
  /** 1 for ink, 0 for paper. Row-major, SIZE × SIZE. */
  pixels: Uint8Array;
  /** How much ink was found at all. Near zero means the box is empty. */
  inkRatio: number;
}

export const SIZE = 16;

export interface Reading {
  /** null for an empty box, which is a real and common answer. */
  digit: number | null;
  /** 0..1. Anything below CONFIDENT is shown to the engineer before it counts. */
  confidence: number;
}

/** Below this, the cell is presented for confirmation rather than filled in. */
export const CONFIDENT = 0.62;

/** Less ink than this and the box was never written in. */
const EMPTY_INK = 0.035;

/**
 * Structural features, chosen because they separate digits without needing training data:
 * where the ink sits vertically and horizontally, how many strokes a scanline crosses, and how
 * many enclosed holes the shape has.
 */
function features(bitmap: DigitBitmap): number[] {
  const { pixels } = bitmap;
  const rowDensity = new Array<number>(4).fill(0);
  const colDensity = new Array<number>(4).fill(0);
  let ink = 0;

  for (let y = 0; y < SIZE; y += 1) {
    for (let x = 0; x < SIZE; x += 1) {
      if (!pixels[y * SIZE + x]) continue;
      ink += 1;
      const rowBand = Math.min(3, Math.floor((y / SIZE) * 4));
      const colBand = Math.min(3, Math.floor((x / SIZE) * 4));
      rowDensity[rowBand] = (rowDensity[rowBand] ?? 0) + 1;
      colDensity[colBand] = (colDensity[colBand] ?? 0) + 1;
    }
  }
  if (ink === 0) return new Array<number>(11).fill(0);

  const crossings: number[] = [];
  for (const y of [Math.floor(SIZE * 0.25), Math.floor(SIZE * 0.5), Math.floor(SIZE * 0.75)]) {
    let count = 0;
    let previous = 0;
    for (let x = 0; x < SIZE; x += 1) {
      const value = pixels[y * SIZE + x] ?? 0;
      if (value && !previous) count += 1;
      previous = value;
    }
    crossings.push(Math.min(count, 3) / 3);
  }

  return [
    ...rowDensity.map((v) => v / ink),
    ...colDensity.map((v) => v / ink),
    ...crossings,
  ];
}

/**
 * Hand-specified centroids, one per digit, in the feature space above.
 *
 * <p>These encode what the shapes are: a 1 is a narrow vertical stroke with one crossing
 * everywhere; an 8 is dense top and bottom with two crossings in the middle; a 7 is heavy at
 * the top and thin below. They are a starting point, not a trained model, and the confidence
 * they return reflects that.</p>
 */
const CENTROIDS: Record<number, number[]> = {
  0: [0.28, 0.24, 0.24, 0.24, 0.26, 0.24, 0.24, 0.26, 0.67, 0.67, 0.67],
  1: [0.25, 0.25, 0.25, 0.25, 0.1, 0.35, 0.4, 0.15, 0.33, 0.33, 0.33],
  2: [0.3, 0.22, 0.22, 0.26, 0.24, 0.26, 0.26, 0.24, 0.33, 0.33, 0.33],
  3: [0.3, 0.2, 0.2, 0.3, 0.18, 0.24, 0.28, 0.3, 0.33, 0.33, 0.33],
  4: [0.2, 0.28, 0.32, 0.2, 0.24, 0.24, 0.28, 0.24, 0.67, 0.67, 0.33],
  5: [0.32, 0.22, 0.2, 0.26, 0.28, 0.24, 0.24, 0.24, 0.33, 0.33, 0.33],
  6: [0.28, 0.24, 0.24, 0.24, 0.3, 0.26, 0.22, 0.22, 0.33, 0.67, 0.67],
  7: [0.36, 0.24, 0.2, 0.2, 0.22, 0.24, 0.26, 0.28, 0.33, 0.33, 0.33],
  8: [0.28, 0.22, 0.22, 0.28, 0.26, 0.24, 0.24, 0.26, 0.67, 0.67, 0.67],
  9: [0.26, 0.26, 0.22, 0.26, 0.24, 0.24, 0.26, 0.26, 0.67, 0.67, 0.33],
};

export function classifyDigit(bitmap: DigitBitmap): Reading {
  if (bitmap.inkRatio < EMPTY_INK) {
    // An empty box is an answer, not a failure — most rows leave several unwritten.
    return { digit: null, confidence: 1 };
  }

  const f = features(bitmap);
  let best = 0;
  let bestDistance = Infinity;
  let runnerUp = Infinity;

  for (const [digit, centroid] of Object.entries(CENTROIDS)) {
    let distance = 0;
    for (let i = 0; i < centroid.length; i += 1) {
      distance += (f[i]! - centroid[i]!) ** 2;
    }
    if (distance < bestDistance) {
      runnerUp = bestDistance;
      bestDistance = distance;
      best = Number(digit);
    } else if (distance < runnerUp) {
      runnerUp = distance;
    }
  }

  // Confidence is the margin over the second-best match, not the absolute distance. A digit
  // that is nearly as close to a 3 as to an 8 is exactly the one a person should look at.
  const margin = runnerUp === Infinity ? 0 : (runnerUp - bestDistance) / (runnerUp + 1e-6);
  return { digit: best, confidence: Math.max(0, Math.min(1, margin * 2.2)) };
}

/**
 * Assembles a field's digits into a number, and says how sure it is of the whole thing.
 *
 * <p>A field is only as trustworthy as its least trustworthy digit, so the confidence is the
 * minimum rather than the mean. A 6-digit quantity read with five certainties and one guess is
 * a guess.</p>
 */
export function assembleField(readings: Reading[], decimals: number): {
  value: number | null;
  confidence: number;
} {
  const written = readings.filter((r) => r.digit !== null);
  if (written.length === 0) return { value: null, confidence: 1 };

  let digits = '';
  let confidence = 1;
  for (const reading of readings) {
    if (reading.digit === null) continue;
    digits += String(reading.digit);
    confidence = Math.min(confidence, reading.confidence);
  }

  const asNumber = Number(digits);
  if (Number.isNaN(asNumber)) return { value: null, confidence: 0 };
  return { value: decimals > 0 ? asNumber / 10 ** decimals : asNumber, confidence };
}
