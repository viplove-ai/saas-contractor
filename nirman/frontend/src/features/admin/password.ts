/**
 * First-time passwords. These get read aloud over a phone or written on paper before the
 * member changes them, so the alphabet drops every character pair that gets misheard or
 * miscopied — no O against 0, no l against 1 — and the groups are hyphenated in threes so a
 * long string can be dictated without losing the place.
 */

const ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789';
const GROUPS = 4;
const GROUP_SIZE = 3;

export function generatePassword(): string {
  const bytes = new Uint32Array(GROUPS * GROUP_SIZE);
  crypto.getRandomValues(bytes);
  const chars = [...bytes].map((value) => ALPHABET[value % ALPHABET.length]);
  const groups: string[] = [];
  for (let i = 0; i < GROUPS; i += 1) {
    groups.push(chars.slice(i * GROUP_SIZE, (i + 1) * GROUP_SIZE).join(''));
  }
  return groups.join('-');
}
