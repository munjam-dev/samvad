// Minimal, dependency-free JPEG structure handling for profile photos (Phase 1.6).
// It never decodes pixels: it walks the marker segments, which is enough to reject non-JPEG uploads,
// read the frame size and drop metadata segments (EXIF with GPS, XMP, ICC, comments).

export class JpegError extends Error {}

const SOI = 0xd8;
const EOI = 0xd9;
const SOS = 0xda;
const APP0 = 0xe0;
const COM = 0xfe;
/** Start-of-frame markers that carry the image size (excluding DHT C4, JPG C8 and DAC CC). */
const SOF = new Set([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf]);

export interface CleanJpeg {
  bytes: Buffer;
  width: number;
  height: number;
}

/**
 * Validates [input] as a baseline/progressive JPEG and returns a copy without APP1–APP15 and COM
 * segments (APP0/JFIF is kept). Throws JpegError for anything that is not a well-formed JPEG.
 */
export function stripJpegMetadata(input: Buffer): CleanJpeg {
  if (input.length < 4 || input[0] !== 0xff || input[1] !== SOI) throw new JpegError('not a JPEG');
  if (input[input.length - 2] !== 0xff || input[input.length - 1] !== EOI) throw new JpegError('truncated JPEG');
  const out: Buffer[] = [input.subarray(0, 2)];
  let width = 0;
  let height = 0;
  let pos = 2;
  while (pos < input.length) {
    if (input[pos] !== 0xff) throw new JpegError('bad marker');
    while (input[pos] === 0xff) pos++; // fill bytes
    const marker = input[pos];
    const start = pos - 1;
    pos++;
    if (marker === EOI) throw new JpegError('no image data');
    if (pos + 2 > input.length) throw new JpegError('truncated segment');
    const length = input.readUInt16BE(pos);
    if (length < 2 || pos + length > input.length) throw new JpegError('bad segment length');
    if (SOF.has(marker)) {
      if (length < 7) throw new JpegError('bad frame header');
      height = input.readUInt16BE(pos + 3);
      width = input.readUInt16BE(pos + 5);
    }
    if (marker === SOS) {
      // Entropy-coded data follows until EOI; copy it unchanged.
      out.push(input.subarray(start));
      if (width === 0 || height === 0) throw new JpegError('no frame size');
      return { bytes: Buffer.concat(out), width, height };
    }
    const metadata = (marker > APP0 && marker <= 0xef) || marker === COM;
    if (!metadata) out.push(input.subarray(start, pos + length));
    pos += length;
  }
  throw new JpegError('no image data');
}
