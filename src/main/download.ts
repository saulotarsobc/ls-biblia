import { createWriteStream } from 'node:fs';
import { mkdir, rename, stat, unlink } from 'node:fs/promises';
import { join } from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import type { ChapterFile, DownloadProgress } from '../shared/types';

export interface DownloadHandle {
  promise: Promise<string>;
  cancel: () => void;
}

function fileNameFor(booknum: number, track: number, file: ChapterFile): string {
  return `nwt_${String(booknum).padStart(2, '0')}_${String(track).padStart(3, '0')}_LSB_${file.quality}.mp4`;
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
  const target = join(dir, fileNameFor(booknum, track, file));
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
