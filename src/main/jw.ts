import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { BOOKS } from '../shared/books.ts';
import type { BookDetail, Chapter, ChapterFile, Quality, Verse } from '../shared/types';
import { MARKER_QUALITY, QUALITIES } from '../shared/types';

const API = 'https://b.jw-cdn.org/apis/pub-media/GETPUBMEDIALINKS';
const LANG = 'LSB';
const CACHE_TTL = 1000 * 60 * 60 * 24 * 7; // catalogo muda raramente

export class BookUnavailableError extends Error {
  constructor(public booknum: number) {
    super(`Livro ${booknum} ainda nao esta disponivel em ${LANG}.`);
    this.name = 'BookUnavailableError';
  }
}

/** "00:01:17.877" -> 77.877 */
function parseTimecode(tc: string): number {
  const [h = '0', m = '0', s = '0'] = tc.split(':');
  return Number(h) * 3600 + Number(m) * 60 + Number(s);
}

interface RawMarker {
  verseNumber: number;
  startTime: string;
  duration: string;
  label: string;
  endTransitionDuration?: string;
}

interface RawItem {
  title: string;
  label: string;
  track: number;
  duration: number;
  filesize: number;
  frameWidth: number;
  frameHeight: number;
  frameRate: number;
  file: { url: string; checksum: string };
  markers: { markers: RawMarker[] } | null;
}

function isQuality(label: string): label is Quality {
  return (QUALITIES as string[]).includes(label);
}

function toVerses(raw: RawMarker[] | undefined, chapterDuration: number): Verse[] {
  if (!raw?.length) return [];
  return raw
    .map((m) => {
      const start = parseTimecode(m.startTime);
      // `duration` cobre so a fala do versiculo; estica ate o proximo para nao
      // deixar buracos no meio de um trecho continuo.
      return {
        verseNumber: m.verseNumber,
        start,
        end: start + parseTimecode(m.duration),
        label: m.label,
      };
    })
    .sort((a, b) => a.verseNumber - b.verseNumber)
    .map((v, i, all) => ({
      ...v,
      end: i < all.length - 1 ? Math.max(v.end, all[i + 1].start) : Math.min(chapterDuration, v.end),
    }));
}

function parseBook(json: any, booknum: number): BookDetail {
  const items: RawItem[] = json?.files?.[LANG]?.MP4 ?? [];
  if (items.length === 0) throw new BookUnavailableError(booknum);

  const byTrack = new Map<number, RawItem[]>();
  for (const it of items) {
    if (!byTrack.has(it.track)) byTrack.set(it.track, []);
    byTrack.get(it.track)!.push(it);
  }

  const chapters: Chapter[] = [...byTrack.entries()]
    .sort(([a], [b]) => a - b)
    .map(([track, group]) => {
      const files = {} as Record<Quality, ChapterFile | undefined>;
      for (const it of group) {
        if (!isQuality(it.label)) continue;
        files[it.label] = {
          quality: it.label,
          url: it.file.url,
          filesize: it.filesize,
          width: it.frameWidth,
          height: it.frameHeight,
          frameRate: it.frameRate,
          duration: it.duration,
          checksum: it.file.checksum,
        };
      }
      // marcadores so vem no 720p, mas as duracoes batem entre as qualidades
      const withMarkers =
        group.find((it) => it.label === MARKER_QUALITY && it.markers) ?? group.find((it) => it.markers);
      const duration = group[0]?.duration ?? 0;
      return {
        track,
        title: group[0]?.title ?? `Capítulo ${track}`,
        duration,
        files,
        verses: toVerses(withMarkers?.markers?.markers, duration),
      };
    });

  return {
    booknum,
    name: json?.pubName ?? BOOKS[booknum - 1]?.name ?? `Livro ${booknum}`,
    chapters,
    fetchedAt: Date.now(),
  };
}

export class JwClient {
  constructor(private cacheDir: string) {}

  private cachePath(booknum: number) {
    return join(this.cacheDir, `book-${booknum}-${LANG}.json`);
  }

  private async readCache(booknum: number): Promise<BookDetail | null> {
    try {
      const raw = await readFile(this.cachePath(booknum), 'utf8');
      const data: BookDetail = JSON.parse(raw);
      if (Date.now() - data.fetchedAt > CACHE_TTL) return null;
      return data;
    } catch {
      return null;
    }
  }

  /** Uma requisicao por livro traz capitulos, qualidades e marcadores de uma vez. */
  async getBook(booknum: number, force = false): Promise<BookDetail> {
    if (!force) {
      const cached = await this.readCache(booknum);
      if (cached) return cached;
    }

    const url = `${API}?pub=nwt&langwritten=${LANG}&txtCMSLang=${LANG}&booknum=${booknum}&output=json&fileformat=MP4`;
    const res = await fetch(url);
    if (res.status === 404) throw new BookUnavailableError(booknum);
    if (!res.ok) throw new Error(`API respondeu ${res.status} para o livro ${booknum}`);

    const detail = parseBook(await res.json(), booknum);
    await mkdir(this.cacheDir, { recursive: true });
    await writeFile(this.cachePath(booknum), JSON.stringify(detail), 'utf8');
    return detail;
  }
}
