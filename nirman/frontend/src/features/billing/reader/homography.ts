/**
 * Mapping a photographed page onto the printed page's own millimetres.
 *
 * <p>A phone photograph of a sheet on a table is a perspective projection: the far edge is
 * shorter than the near one, and no amount of scaling fixes that. Four known points are exactly
 * enough to solve for the eight unknowns of a projective transform, which is why the sheet
 * carries four corner marks and why they are the first thing the reader looks for.</p>
 *
 * <p>Once this matrix exists, every box on the page is arithmetic instead of a search — and
 * that is the entire reason a local reader is tractable where general handwriting recognition
 * is not.</p>
 */

export interface Point {
  x: number;
  y: number;
}

/** Row-major 3×3. */
export type Matrix3 = readonly number[];

/**
 * Solves the 8×8 system for the projective transform taking `from` onto `to`.
 *
 * <p>Gaussian elimination with partial pivoting. Eight equations, two per correspondence, and
 * the ninth element of the matrix fixed at 1 because a homography is only defined up to
 * scale.</p>
 *
 * @returns null when the points are degenerate — three collinear marks, or two detected as the
 *          same blob. A null here must surface as "retake the photo", never as a transform that
 *          happens to be nonsense.
 */
export function solveHomography(from: Point[], to: Point[]): Matrix3 | null {
  if (from.length !== 4 || to.length !== 4) return null;

  const a: number[][] = [];
  const b: number[] = [];
  for (let i = 0; i < 4; i += 1) {
    const s = from[i]!;
    const d = to[i]!;
    a.push([s.x, s.y, 1, 0, 0, 0, -d.x * s.x, -d.x * s.y]);
    b.push(d.x);
    a.push([0, 0, 0, s.x, s.y, 1, -d.y * s.x, -d.y * s.y]);
    b.push(d.y);
  }

  for (let col = 0; col < 8; col += 1) {
    let pivot = col;
    for (let row = col + 1; row < 8; row += 1) {
      if (Math.abs(a[row]![col]!) > Math.abs(a[pivot]![col]!)) pivot = row;
    }
    if (Math.abs(a[pivot]![col]!) < 1e-9) return null;
    [a[col], a[pivot]] = [a[pivot]!, a[col]!];
    [b[col], b[pivot]] = [b[pivot]!, b[col]!];

    const p = a[col]![col]!;
    for (let k = col; k < 8; k += 1) a[col]![k]! /= p;
    b[col]! /= p;

    for (let row = 0; row < 8; row += 1) {
      if (row === col) continue;
      const factor = a[row]![col]!;
      if (factor === 0) continue;
      for (let k = col; k < 8; k += 1) a[row]![k]! -= factor * a[col]![k]!;
      b[row]! -= factor * b[col]!;
    }
  }

  return [b[0]!, b[1]!, b[2]!, b[3]!, b[4]!, b[5]!, b[6]!, b[7]!, 1];
}

export function applyHomography(h: Matrix3, point: Point): Point {
  const denominator = h[6]! * point.x + h[7]! * point.y + h[8]!;
  if (Math.abs(denominator) < 1e-12) return { x: 0, y: 0 };
  return {
    x: (h[0]! * point.x + h[1]! * point.y + h[2]!) / denominator,
    y: (h[3]! * point.x + h[4]! * point.y + h[5]!) / denominator,
  };
}
