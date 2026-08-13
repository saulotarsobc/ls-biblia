import ffmpegPath from 'ffmpeg-static';
import ffprobeStatic from 'ffprobe-static';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildAtoms, outputDuration } from '../../shared/timeline.ts';
import type { EditState } from '../../shared/types';
import { buildExport } from '../export.ts';

if (!ffmpegPath) throw new Error('O pacote ffmpeg-static nao forneceu um executavel.');

const FFMPEG = ffmpegPath;
const FFPROBE = ffprobeStatic.path;

/**
 * Reproduz o caso relatado: um trecho no meio do video, com camera lenta e
 * zoom. O player mostrava a duracao do RESTO do arquivo de origem em vez da
 * duracao real.
 */
const CENARIOS: { nome: string; edit: EditState }[] = [
  {
    nome: 'so corte',
    edit: { ranges: [{ start: 2, end: 4.5 }], speedRegions: [], zoomRegions: [], smoothSlowMotion: false },
  },
  {
    nome: 'corte + camera lenta',
    edit: {
      ranges: [{ start: 2, end: 4.5 }],
      speedRegions: [{ id: 's', start: 1.4, end: 2.2, speed: 0.5 }],
      zoomRegions: [],
      smoothSlowMotion: false,
    },
  },
  {
    nome: 'corte + zoom',
    edit: {
      ranges: [{ start: 2, end: 4.5 }],
      speedRegions: [],
      zoomRegions: [{ id: 'z', start: 1.45, end: 2.45, zoom: 1.4, cx: 0.5, cy: 0.45, ramp: 0.25 }],
      smoothSlowMotion: false,
    },
  },
  {
    nome: 'corte + lenta + zoom (caso relatado)',
    edit: {
      ranges: [{ start: 2, end: 4.5 }],
      speedRegions: [{ id: 's', start: 1.4, end: 2.2, speed: 0.5 }],
      zoomRegions: [{ id: 'z', start: 1.45, end: 2.45, zoom: 1.4, cx: 0.5, cy: 0.45, ramp: 0.25 }],
      smoothSlowMotion: false,
    },
  },
  {
    nome: 'lenta + zoom + SUAVIZAR ligado',
    edit: {
      ranges: [{ start: 2, end: 4.5 }],
      speedRegions: [{ id: 's', start: 1.4, end: 2.2, speed: 0.5 }],
      zoomRegions: [{ id: 'z', start: 1.45, end: 2.45, zoom: 1.4, cx: 0.5, cy: 0.45, ramp: 0.25 }],
      smoothSlowMotion: true,
    },
  },
  {
    nome: 'so camera lenta + SUAVIZAR ligado',
    edit: {
      ranges: [{ start: 2, end: 4.5 }],
      speedRegions: [{ id: 's', start: 1.4, end: 2.2, speed: 0.5 }],
      zoomRegions: [],
      smoothSlowMotion: true,
    },
  },
];

function run(bin: string, args: string[]): string {
  const result = spawnSync(bin, args, { encoding: 'utf8', windowsHide: true });
  if (result.error) throw new Error(`Nao consegui executar ${bin}: ${result.error.message}`);
  if (result.status !== 0) {
    const detalhe = (result.stderr || result.stdout || 'sem detalhes').slice(0, 2000);
    throw new Error(`${bin} saiu com codigo ${result.status}\n${detalhe}`);
  }
  return result.stdout ?? '';
}

const probe = (path: string, campos: string) =>
  run(FFPROBE, [
    '-v',
    'error',
    '-select_streams',
    'v:0',
    '-count_frames',
    '-show_entries',
    campos,
    '-of',
    'default=nw=1',
    path,
  ]).trim();

const tempRoot = fileURLToPath(new URL('../../../tmp/', import.meta.url));
mkdirSync(tempRoot, { recursive: true });
const tempDir = mkdtempSync(join(tempRoot, 'duracao-'));
const sourcePath = join(tempDir, 'origem.mp4');
let fail = 0;

try {
  // A legenda cria uma stream extra na origem. A exportacao deve manter apenas
  // o video, sem herdar a duracao de nenhuma outra stream.
  const legenda = join(tempDir, 'legenda.srt');
  writeFileSync(legenda, '1\n00:00:00,000 --> 00:00:06,000\nteste\n', 'utf8');
  run(FFMPEG, [
    '-hide_banner',
    '-v',
    'error',
    '-y',
    '-f',
    'lavfi',
    '-i',
    'testsrc2=size=160x90:rate=30:duration=6',
    '-f',
    'srt',
    '-i',
    legenda,
    '-map',
    '0:v:0',
    '-map',
    '1:s:0',
    '-c:v',
    'libx264',
    '-preset',
    'ultrafast',
    '-pix_fmt',
    'yuv420p',
    '-c:s',
    'mov_text',
    sourcePath,
  ]);

  console.log('origem sintetica: 6s, trecho 2s-4.5s\n');

  for (const [i, c] of CENARIOS.entries()) {
    const out = join(tempDir, `dur${i}.mp4`);
    const built = buildExport({
      sourcePath,
      outputPath: out,
      edit: c.edit,
      width: 160,
      height: 90,
      frameRate: 30,
      crf: 22,
    });

    try {
      run(FFMPEG, ['-hide_banner', '-v', 'error', '-y', ...built.args]);

      const info = probe(out, 'format=duration:stream=nb_read_frames');
      const dur = Number(/duration=([\d.]+)/.exec(info)?.[1] ?? 0);
      const frames = Number(/nb_read_frames=(\d+)/.exec(info)?.[1] ?? 0);
      const previsto = outputDuration(buildAtoms(c.edit.ranges, c.edit.speedRegions));

      // TODAS as streams, nao so a de video: os players mostram a MAIOR
      // duracao entre elas.
      const todas = run(FFPROBE, [
        '-v',
        'error',
        '-show_entries',
        'stream=index,codec_type,duration',
        '-of',
        'csv=p=0',
        out,
      ]).trim();
      const linhas = todas.split('\n').filter(Boolean);
      const piorDuracao = Math.max(...linhas.map((l) => Number(l.split(',')[2]) || 0));
      const soVideo = linhas.length === 1 && linhas[0].includes('video');

      const ok = Math.abs(dur - previsto) < 0.2 && soVideo && Math.abs(piorDuracao - previsto) < 0.2;
      if (!ok) fail++;
      console.log(
        `${ok ? 'ok  ' : 'FAIL'} ${c.nome.padEnd(36)} previsto ${previsto.toFixed(2)}s | ` +
          `container ${dur.toFixed(2)}s | frames ${frames} | streams ${linhas.length} | ` +
          `maior duracao entre streams ${piorDuracao.toFixed(2)}s`,
      );
      if (!soVideo) console.log(`     stream extra na saida: ${linhas.join(' | ')}`);
    } catch (error) {
      fail++;
      console.log(`${c.nome}: ffmpeg/ffprobe falhou\n${error instanceof Error ? error.message : String(error)}`);
    }
  }
} catch (error) {
  fail++;
  console.error(
    `Nao foi possivel preparar o teste de duracao:\n${error instanceof Error ? error.message : String(error)}`,
  );
} finally {
  rmSync(tempDir, { recursive: true, force: true });
}

console.log('\n' + (fail ? `FALHOU em ${fail} cenario(s)` : 'TUDO OK'));
process.exitCode = fail ? 1 : 0;
