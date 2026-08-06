import { createWriteStream } from 'node:fs';
import { mkdir, rename, stat, unlink } from 'node:fs/promises';
import { join } from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import type { ChapterFile, DownloadProgress, Quality } from '../shared/types';
import { QUALITIES } from '../shared/types.ts';

export interface DownloadHandle {
  promise: Promise<string>;
  cancel: () => void;
}

/**
 * Nome do capitulo no disco.
 *
 * A extensao e sempre .mp4, mesmo quando a origem e .m4v: e o mesmo container,
 * e um padrao unico deixa a pasta legivel para o gerenciador de cache.
 */
export function videoFileName(booknum: number, track: number, quality: Quality): string {
  return `nwt_${String(booknum).padStart(2, '0')}_${String(track).padStart(3, '0')}_LSB_${quality}.mp4`;
}

const VIDEO_RE = /^nwt_(\d+)_(\d+)_LSB_([0-9]+p)\.mp4(\.part)?$/;

export interface ParsedVideoName {
  booknum: number;
  track: number;
  quality: Quality;
  /** download interrompido (.part): nao serve para editar */
  partial: boolean;
}

/** Inverso de `videoFileName`. Null para qualquer arquivo que o app nao criou. */
export function parseVideoFileName(name: string): ParsedVideoName | null {
  const m = VIDEO_RE.exec(name);
  if (!m || !(QUALITIES as string[]).includes(m[3])) return null;
  return {
    booknum: Number(m[1]),
    track: Number(m[2]),
    quality: m[3] as Quality,
    partial: Boolean(m[4]),
  };
}

async function exists(p: string): Promise<number | null> {
  try {
    return (await stat(p)).size;
  } catch {
    return null;
  }
}

/**
 * Baixa o capitulo para a pasta local. Se o arquivo ja estiver la com o tamanho
 * que a API informa, reaproveita em vez de baixar de novo.
 *
 * Escreve num `.part` e so renomeia no fim, para um download interrompido nunca
 * passar por arquivo completo.
 */
export function downloadChapter(
  dir: string,
  booknum: number,
  track: number,
  file: ChapterFile,
  onProgress: (p: DownloadProgress) => void,
): DownloadHandle {
  const controller = new AbortController();
  const target = join(dir, videoFileName(booknum, track, file.quality));
  const partial = `${target}.part`;

  const promise = (async () => {
    await mkdir(dir, { recursive: true });

    const already = await exists(target);
    if (already !== null && already === file.filesize) {
      onProgress({
        receivedBytes: already,
        totalBytes: already,
        percent: 100,
      });
      return target;
    }

    const res = await fetch(file.url, { signal: controller.signal });
    if (!res.ok || !res.body) {
      throw new Error(`Falha ao baixar (HTTP ${res.status}).`);
    }

    const total = Number(res.headers.get('content-length')) || file.filesize || 0;
    let received = 0;
    let lastEmit = 0;

    const source = Readable.fromWeb(res.body as any);
    source.on('data', (chunk: Buffer) => {
      received += chunk.length;
      const now = Date.now();
      // limita a taxa de eventos para nao inundar o IPC
      if (now - lastEmit > 100 || received === total) {
        lastEmit = now;
        onProgress({
          receivedBytes: received,
          totalBytes: total,
          percent: total ? (received / total) * 100 : 0,
        });
      }
    });

    try {
      await pipeline(source, createWriteStream(partial));
    } catch (err) {
      await unlink(partial).catch(() => {});
      throw err;
    }

    await rename(partial, target);
    return target;
  })();

  return { promise, cancel: () => controller.abort() };
}
