/**
 * The one shape every signature is cut to, and the arithmetic that cuts it.
 *
 * <p>The documents draw a signature into a box of fixed size — 42 by 14 millimetres on the
 * offer letter and on the daily report — and a picture that is not that shape is squashed into
 * it. So the shape is settled here, once, on the device, before anything is uploaded: three
 * units wide to one high, rendered at a size that prints crisply and costs a few tens of
 * kilobytes. The server stores no size and checks none; what reaches it is already right.</p>
 *
 * <p>Flattened onto white rather than kept transparent. A signature is photographed on paper
 * and a PNG somebody exported from a drawing app may carry an alpha channel; the PDF renderer
 * draws either onto a white page, and the two look the same there — but the thumbnail on a
 * dark screen would not, and a picture that looks different on the phone and on the letter is
 * one somebody uploads twice.</p>
 */
export const SIGNATURE_ASPECT = 3;
export const SIGNATURE_WIDTH_PX = 900;
export const SIGNATURE_HEIGHT_PX = SIGNATURE_WIDTH_PX / SIGNATURE_ASPECT;

/** A rectangle in the source image's own pixels. */
export interface CropRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

/**
 * The largest 3:1 rectangle that fits a picture of the given size, centred.
 *
 * <p>The starting position of the crop box: a photograph of a signature on a sheet of paper is
 * usually wider than it is tall, so the box lands across the middle where the pen went.</p>
 */
export function initialCrop(imageWidth: number, imageHeight: number): CropRect {
  const wide = imageWidth / imageHeight >= SIGNATURE_ASPECT;
  const width = wide ? imageHeight * SIGNATURE_ASPECT : imageWidth;
  const height = width / SIGNATURE_ASPECT;
  return {
    x: (imageWidth - width) / 2,
    y: (imageHeight - height) / 2,
    width,
    height,
  };
}

/** Keeps a crop box inside the picture, moving it rather than shrinking it. */
export function clampCrop(crop: CropRect, imageWidth: number, imageHeight: number): CropRect {
  const width = Math.min(crop.width, imageWidth, imageHeight * SIGNATURE_ASPECT);
  const height = width / SIGNATURE_ASPECT;
  return {
    width,
    height,
    x: Math.min(Math.max(crop.x, 0), imageWidth - width),
    y: Math.min(Math.max(crop.y, 0), imageHeight - height),
  };
}

/** Loads a picked file as an image the canvas can draw. The URL is revoked either way. */
export function loadImage(file: Blob): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const image = new Image();
    image.onload = () => {
      URL.revokeObjectURL(url);
      resolve(image);
    };
    image.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('That picture could not be read.'));
    };
    image.src = url;
  });
}

/**
 * Cuts the chosen rectangle out of the picture and renders it at the standard size on white.
 *
 * @returns a PNG, because a signature is a line drawing and JPEG fringes the pen stroke
 */
export function renderSignature(image: HTMLImageElement, crop: CropRect): Promise<Blob> {
  const canvas = document.createElement('canvas');
  canvas.width = SIGNATURE_WIDTH_PX;
  canvas.height = SIGNATURE_HEIGHT_PX;
  const context = canvas.getContext('2d');
  if (!context) {
    return Promise.reject(new Error('This browser cannot prepare the picture.'));
  }
  context.fillStyle = '#ffffff';
  context.fillRect(0, 0, canvas.width, canvas.height);
  context.drawImage(
    image,
    crop.x,
    crop.y,
    crop.width,
    crop.height,
    0,
    0,
    SIGNATURE_WIDTH_PX,
    SIGNATURE_HEIGHT_PX,
  );
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (blob) resolve(blob);
      else reject(new Error('This browser cannot prepare the picture.'));
    }, 'image/png');
  });
}
