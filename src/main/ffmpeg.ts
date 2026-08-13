import { spawn, type ChildProcess } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join } from 'node:path';

/** Binarios copiados por extraResources para resources/bin no app instalado. */
function packagedBinary(name: string): string | null {
  const resourcesPath = process.resourcesPath;
  if (!resourcesPath) return null;
  const path = join(resourcesPath, 'bin', name);
  return existsSync(path) ? path : null;
}

/**
 * Em desenvolvimento, usa os executaveis baixados pelos pacotes static. No app
 * instalado, eles ficam fora do asar e sao encontrados por packagedBinary().
 */
function bundled(load: () => string | undefined): string | null {
  try {
    const resolved = load();
    if (!resolved) return null;
    return existsSync(resolved) ? resolved : null;
  } catch {
    return null;
  }
}

/** Ordem: variavel de ambiente -> resources/bin -> pacote de dev -> PATH. */
export function resolveFfmpeg(): string {
  if (process.env.FFMPEG_PATH && existsSync(process.env.FFMPEG_PATH)) return process.env.FFMPEG_PATH;
  return (
    packagedBinary('ffmpeg.exe') ??
    bundled(() => {
      const m = require('ffmpeg-static');
      return m?.default ?? m;
    }) ??
    'ffmpeg'
  );
}

export function resolveFfprobe(): string {
  if (process.env.FFPROBE_PATH && existsSync(process.env.FFPROBE_PATH)) return process.env.FFPROBE_PATH;
  return (
    packagedBinary('ffprobe.exe') ??
    bundled(() => {
      const m = require('ffprobe-static');
      return (m?.default ?? m)?.path;
    }) ??
    'ffprobe'
  );
}

export interface ProgressEvent {
  outTimeSeconds: number;
  fps: number;
  speed: string;
}

export interface RunHandle {
  promise: Promise<void>;
  cancel: () => void;
}

/**
 * Roda o ffmpeg lendo `-progress pipe:1`, que emite pares chave=valor a cada
 * bloco em vez do texto de status normal (bem mais confiavel de parsear).
 */
export function runFfmpeg(args: string[], onProgress?: (p: ProgressEvent) => void): RunHandle {
  let child: ChildProcess | null = null;
  let cancelled = false;

  const promise = new Promise<void>((resolve, reject) => {
    const bin = resolveFfmpeg();
    child = spawn(bin, ['-hide_banner', '-nostdin', '-y', ...args, '-progress', 'pipe:1', '-nostats'], {
      windowsHide: true,
    });

    let stderrTail = '';
    let buf = '';
    const state: ProgressEvent = { outTimeSeconds: 0, fps: 0, speed: '0x' };

    child.stdout?.on('data', (d: Buffer) => {
      buf += d.toString();
      const lines = buf.split('\n');
      buf = lines.pop() ?? '';
      for (const line of lines) {
        const [k, v] = line.split('=');
        if (!k || v === undefined) continue;
        if (k === 'out_time_us' || k === 'out_time_ms') {
          const us = Number(v);
          // out_time_ms do ffmpeg vem em microssegundos, apesar do nome
          if (Number.isFinite(us)) state.outTimeSeconds = us / 1_000_000;
        } else if (k === 'fps') state.fps = Number(v) || 0;
        else if (k === 'speed') state.speed = v.trim();
        else if (k === 'progress') onProgress?.({ ...state });
      }
    });

    child.stderr?.on('data', (d: Buffer) => {
      stderrTail = (stderrTail + d.toString()).slice(-4000);
    });

    child.on('error', (err) => reject(new Error(`Nao consegui executar o ffmpeg (${bin}): ${err.message}`)));
    child.on('close', (code) => {
      if (cancelled) return reject(new Error('cancelado'));
      if (code === 0) resolve();
      else reject(new Error(`ffmpeg saiu com codigo ${code}\n${stderrTail}`));
    });
  });

  return {
    promise,
    cancel: () => {
      cancelled = true;
      child?.kill('SIGKILL');
    },
  };
}

export async function ffprobeDuration(path: string): Promise<number> {
  return new Promise((resolve, reject) => {
    const child = spawn(
      resolveFfprobe(),
      ['-v', 'error', '-select_streams', 'v:0', '-show_entries', 'format=duration', '-of', 'csv=p=0', path],
      { windowsHide: true },
    );
    let out = '';
    child.stdout.on('data', (d) => (out += d.toString()));
    child.on('error', reject);
    child.on('close', () => {
      const n = Number(out.trim());
      Number.isFinite(n) ? resolve(n) : reject(new Error('nao consegui ler a duracao'));
    });
  });
}
