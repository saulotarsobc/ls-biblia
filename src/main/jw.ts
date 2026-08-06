import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { BOOKS } from '../shared/books.ts';
import type { BookDetail, Chapter, ChapterFile, Quality, Verse } from '../shared/types';
import { MARKER_QUALITY, QUALITIES } from '../shared/types.ts';

const API = 'https://b.jw-cdn.org/apis/pub-media/GETPUBMEDIALINKS';
const LANG = 'LSB';
export const CATALOG_TTL = 1000 * 60 * 60 * 24 * 7; // catalogo muda raramente

/**
 * Formatos de video da API, em ordem de preferencia.
 *
 * Precisa ser mais de um: em LSB, Levitico saiu com o capitulo 11 em .mp4 e os
 * outros 26 em .m4v, entao pedir so MP4 escondia o livro quase inteiro. Sao o
 * mesmo container (H.264/AAC) e trazem as mesmas qualidades e os mesmos
 * marcadores de versiculo — muda so a extensao publicada.
 */
const FORMATS = ['MP4', 'M4V'] as const;

export function catalogFileName(booknum: number): string {
  return `book-${booknum}-${LANG}.json`;
}

const CATALOG_RE = new RegExp(`^book-(\\d+)-${LANG}\\.json$`);

/** Nome de arquivo do cache -> numero do livro. Null para qualquer outra coisa. */
export function parseCatalogFileName(name: string): number | null {
  const m = CATALOG_RE.exec(name);
  return m ? Number(m[1]) : null;
}

export class BookUnavailableError extends Error {
  // campo declarado a parte, e nao como parametro do construtor: assim o arquivo
  // roda direto no `node --experimental-strip-types` dos testes
  readonly booknum: number;

  constructor(booknum: number) {
    super(`Livro ${booknum} ainda nao esta disponivel em ${LANG}.`);
    this.name = 'BookUnavailableError';
    this.booknum = booknum;
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

/** Exportada para teste; em producao so `getBook` chama. */
export function parseBook(json: any, booknum: number): BookDetail {
  const byFormat = json?.files?.[LANG] ?? {};
  const items: RawItem[] = FORMATS.flatMap((f) => byFormat[f] ?? []);
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
        // group vem na ordem de FORMATS: o primeiro a preencher cada qualidade
        // vence, entao um capitulo publicado nos dois formatos usa o MP4.
        if (files[it.label]) continue;
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
  private cacheDir: string;

  constructor(cacheDir: string) {
    this.cacheDir = cacheDir;
  }

  private cachePath(booknum: number) {
    return join(this.cacheDir, catalogFileName(booknum));
  }

  private async readCache(booknum: number): Promise<BookDetail | null> {
    try {
      const raw = await readFile(this.cachePath(booknum), 'utf8');
      const data: BookDetail = JSON.parse(raw);
      if (Date.now() - data.fetchedAt > CATALOG_TTL) return null;
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

    // sem `fileformat`: a resposta traz todos os formatos e o parse escolhe
    const url = `${API}?pub=nwt&langwritten=${LANG}&txtCMSLang=${LANG}&booknum=${booknum}&output=json`;
    const res = await fetch(url);
    if (res.status === 404) throw new BookUnavailableError(booknum);
    if (!res.ok) throw new Error(`API respondeu ${res.status} para o livro ${booknum}`);

    const detail = parseBook(await res.json(), booknum);
    await mkdir(this.cacheDir, { recursive: true });
    await writeFile(this.cachePath(booknum), JSON.stringify(detail), 'utf8');
    return detail;
  }
}
