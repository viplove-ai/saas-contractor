import type { SxProps, Theme } from '@mui/material';
import { tokens } from './theme';

/**
 * The hand-drawn edge, as a value rather than a picture.
 *
 * <p>Four different corner radii on each axis is enough to read as a line drawn by a hand and
 * cheap enough to put on every card — no images, no SVG filters, nothing that costs a repaint
 * on a five-year-old phone. Six variants, chosen by index, so a list of cards is not six
 * identical wobbles; `inkEdge(i)` from a map index is the intended use.</p>
 */
const EDGES = [
  '14px 8px 15px 9px / 9px 15px 8px 14px',
  '8px 14px 9px 15px / 15px 9px 14px 8px',
  '15px 9px 13px 10px / 10px 13px 9px 15px',
  '9px 15px 8px 14px / 14px 8px 15px 9px',
  '13px 8px 16px 9px / 9px 16px 8px 13px',
  '16px 10px 14px 8px / 8px 14px 10px 16px',
] as const;

export function edgeRadius(seed = 0): string {
  return EDGES[Math.abs(seed) % EDGES.length]!;
}

/** A drawn card: inked outline, irregular corners, the pencil shadow offset down-right. */
export function inkEdge(seed = 0, opts: { emphasis?: boolean } = {}): SxProps<Theme> {
  return {
    bgcolor: 'background.paper',
    border: opts.emphasis ? `1.8px solid ${tokens.ink}` : `1.5px solid ${tokens.ink}`,
    borderRadius: edgeRadius(seed),
    boxShadow: opts.emphasis ? `4px 5px 0 ${tokens.ink}` : '3px 4px 0 rgba(20,24,29,0.10)',
  };
}

/**
 * The graph-paper ground. Two 1px gradients at 5% ink — visible as paper, invisible as
 * texture, and it does not move under scrolled content the way a raster tile would.
 */
export const graphPaper: SxProps<Theme> = {
  backgroundColor: tokens.paper,
  backgroundImage:
    'linear-gradient(rgba(20,24,29,0.05) 1px, transparent 1px), linear-gradient(90deg, rgba(20,24,29,0.05) 1px, transparent 1px)',
  backgroundSize: '26px 26px',
};

/** A margin note in the surveyor's own hand. Never carries a figure anybody must read. */
export const marginNote: SxProps<Theme> = {
  fontFamily: '"Kalam", cursive',
  fontSize: '0.95rem',
  lineHeight: 1.45,
  color: tokens.annotation,
};

/** Any figure. Mono, so a column of them aligns and a 3 is never a 8. */
export const figure: SxProps<Theme> = {
  fontFamily: '"IBM Plex Mono", monospace',
  fontWeight: 600,
  fontVariantNumeric: 'tabular-nums',
};
