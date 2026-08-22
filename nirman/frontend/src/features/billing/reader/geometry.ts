/**
 * The printed sheet's layout, as the server defines it.
 *
 * <p>Fetched rather than duplicated. The PDF is rendered from these millimetres and the reader
 * corrects a photograph onto the same ones — a layout written down twice is a reader that
 * silently starts on the wrong column the first time somebody nudges a margin.</p>
 */
export interface GeometryBox {
  row: number;
  field: string;
  digit: number;
  leftMm: number;
  topMm: number;
  widthMm: number;
  heightMm: number;
}

export interface GeometryField {
  name: string;
  digits: number;
  decimals: number;
}

export interface SheetGeometry {
  pageWidthMm: number;
  pageHeightMm: number;
  markInsetMm: number;
  markSizeMm: number;
  markWideSizeMm: number;
  rows: number;
  fields: GeometryField[];
  boxes: GeometryBox[];
}

/** Where the four registration marks' centres sit, in page millimetres. */
export function markCentres(geometry: SheetGeometry) {
  const { pageWidthMm: w, pageHeightMm: h, markInsetMm: i, markSizeMm: s, markWideSizeMm: ws } =
    geometry;
  return {
    topLeft: { x: i + s / 2, y: i + s / 2 },
    topRight: { x: w - i - s / 2, y: i + s / 2 },
    // Wider, and that asymmetry is the only thing that tells an upside-down page from an
    // upright one. A symmetric set reads a rotated sheet as valid and transposes every row.
    bottomLeft: { x: i + ws / 2, y: h - i - s / 2 },
    bottomRight: { x: w - i - s / 2, y: h - i - s / 2 },
  };
}
