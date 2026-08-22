import type { Point } from './homography';

/**
 * Finding the four printed corner marks in a photograph.
 *
 * <p>They are solid black squares on white paper, which is the easiest thing in the frame to
 * find and the reason the sheet has them at all. The method is deliberately dull: threshold to
 * black, label connected components, keep the blobs that are roughly square and roughly the
 * right size, and take the one nearest each corner of the image.</p>
 *
 * <p>Nothing here is clever, and that is the point — a clever detector fails in ways nobody can
 * debug at a site with the light going.</p>
 */

export interface Blob {
  x: number;
  y: number;
  width: number;
  height: number;
  pixels: number;
}

/** Grey, from a canvas RGBA buffer. Luma weights, because pen on paper is a contrast problem. */
export function toGrey(data: Uint8ClampedArray, width: number, height: number): Uint8Array {
  const grey = new Uint8Array(width * height);
  for (let i = 0, p = 0; p < grey.length; i += 4, p += 1) {
    grey[p] = (data[i]! * 299 + data[i + 1]! * 587 + data[i + 2]! * 114) / 1000;
  }
  return grey;
}

/**
 * Otsu's threshold: the cut that best separates the histogram into two groups.
 *
 * <p>Chosen over a fixed value because a sheet photographed on a site is lit by whatever is
 * available — a fixed 128 turns a page shot in shade entirely black and one in sun entirely
 * white, and in both cases the marks vanish.</p>
 */
export function otsuThreshold(grey: Uint8Array): number {
  const histogram = new Array<number>(256).fill(0);
  for (const value of grey) histogram[value] = (histogram[value] ?? 0) + 1;

  const total = grey.length;
  let sum = 0;
  for (let i = 0; i < 256; i += 1) sum += i * histogram[i]!;

  let sumBackground = 0;
  let weightBackground = 0;
  let best = 0;
  let bestVariance = -1;

  for (let t = 0; t < 256; t += 1) {
    weightBackground += histogram[t]!;
    if (weightBackground === 0) continue;
    const weightForeground = total - weightBackground;
    if (weightForeground === 0) break;

    sumBackground += t * histogram[t]!;
    const meanBackground = sumBackground / weightBackground;
    const meanForeground = (sum - sumBackground) / weightForeground;
    const variance =
      weightBackground * weightForeground * (meanBackground - meanForeground) ** 2;
    if (variance > bestVariance) {
      bestVariance = variance;
      best = t;
    }
  }
  return best;
}

/**
 * Connected dark components, found iteratively.
 *
 * <p>The flood fill uses an explicit stack rather than recursion: a dark region in a photograph
 * of a page can be tens of thousands of pixels, and recursing over it overflows the stack on a
 * phone.</p>
 */
export function findDarkBlobs(
  grey: Uint8Array,
  width: number,
  height: number,
  threshold: number,
  minPixels: number,
): Blob[] {
  const seen = new Uint8Array(width * height);
  const blobs: Blob[] = [];
  const stack: number[] = [];

  for (let start = 0; start < grey.length; start += 1) {
    if (seen[start] || grey[start]! > threshold) continue;

    let minX = width;
    let maxX = 0;
    let minY = height;
    let maxY = 0;
    let pixels = 0;

    stack.length = 0;
    stack.push(start);
    seen[start] = 1;

    while (stack.length > 0) {
      const index = stack.pop()!;
      const x = index % width;
      const y = (index - x) / width;
      pixels += 1;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;

      if (x > 0 && !seen[index - 1] && grey[index - 1]! <= threshold) {
        seen[index - 1] = 1;
        stack.push(index - 1);
      }
      if (x < width - 1 && !seen[index + 1] && grey[index + 1]! <= threshold) {
        seen[index + 1] = 1;
        stack.push(index + 1);
      }
      if (y > 0 && !seen[index - width] && grey[index - width]! <= threshold) {
        seen[index - width] = 1;
        stack.push(index - width);
      }
      if (y < height - 1 && !seen[index + width] && grey[index + width]! <= threshold) {
        seen[index + width] = 1;
        stack.push(index + width);
      }
    }

    if (pixels >= minPixels) {
      blobs.push({
        x: minX,
        y: minY,
        width: maxX - minX + 1,
        height: maxY - minY + 1,
        pixels,
      });
    }
  }
  return blobs;
}

/** A blob that looks like a printed mark: solid, and roughly as wide as it is tall. */
function looksLikeMark(blob: Blob, imageWidth: number): boolean {
  const aspect = blob.width / blob.height;
  // The wide bottom-left mark is 2:1, so the band has to admit it.
  if (aspect < 0.55 || aspect > 2.6) return false;
  // Solidity: a printed square fills its own bounding box. Text and smudges do not.
  if (blob.pixels < blob.width * blob.height * 0.55) return false;
  // A mark is a small fraction of the page; this rejects the grid, shadows and the table edge.
  const relative = blob.width / imageWidth;
  return relative > 0.008 && relative < 0.09;
}

/**
 * The four marks, one per image corner.
 *
 * @returns null when any corner has no candidate — which must surface as "retake the photo",
 *          because a transform fitted to three marks and a guess is worse than no transform.
 */
export function findCornerMarks(
  blobs: Blob[],
  imageWidth: number,
  imageHeight: number,
): { topLeft: Point; topRight: Point; bottomLeft: Point; bottomRight: Point } | null {
  const candidates = blobs.filter((blob) => looksLikeMark(blob, imageWidth));
  if (candidates.length < 4) return null;

  const centre = (blob: Blob): Point => ({
    x: blob.x + blob.width / 2,
    y: blob.y + blob.height / 2,
  });

  const nearest = (cornerX: number, cornerY: number): Blob | null => {
    let best: Blob | null = null;
    let bestDistance = Infinity;
    for (const blob of candidates) {
      const c = centre(blob);
      const distance = (c.x - cornerX) ** 2 + (c.y - cornerY) ** 2;
      if (distance < bestDistance) {
        bestDistance = distance;
        best = blob;
      }
    }
    return best;
  };

  const topLeft = nearest(0, 0);
  const topRight = nearest(imageWidth, 0);
  const bottomLeft = nearest(0, imageHeight);
  const bottomRight = nearest(imageWidth, imageHeight);
  if (!topLeft || !topRight || !bottomLeft || !bottomRight) return null;

  // Four distinct blobs, or the page was too skewed, too dark, or only partly in frame.
  const chosen = [topLeft, topRight, bottomLeft, bottomRight];
  for (let i = 0; i < chosen.length; i += 1) {
    for (let j = i + 1; j < chosen.length; j += 1) {
      if (chosen[i] === chosen[j]) return null;
    }
  }

  return {
    topLeft: centre(topLeft),
    topRight: centre(topRight),
    bottomLeft: centre(bottomLeft),
    bottomRight: centre(bottomRight),
  };
}

/**
 * Whether the page was photographed upside down.
 *
 * <p>The wide mark is printed at the bottom-left. If the widest of the four is found at the top
 * of the image, the sheet is rotated and every row would be read in reverse — so the caller
 * rotates the image and starts again rather than reading it backwards.</p>
 */
export function looksUpsideDown(blobs: Blob[], imageWidth: number, imageHeight: number): boolean {
  const candidates = blobs.filter((blob) => looksLikeMark(blob, imageWidth));
  if (candidates.length < 4) return false;
  let widest = candidates[0]!;
  for (const blob of candidates) {
    if (blob.width / blob.height > widest.width / widest.height) widest = blob;
  }
  return widest.y + widest.height / 2 < imageHeight / 2;
}
