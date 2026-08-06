import { spawnSync } from 'node:child_process';
import { buildExport } from '../export.ts';
import { outputDuration, buildAtoms } from '../../shared/timeline.ts';
import type { EditState } from '../../shared/types';

const SP =
  'C:/Users/saulo/AppData/Local/Temp/claude/c--Users-saulo-Documents-projetos-sentinela-carousel-video-bible-slow/fe583def-c4e0-4c55-ac44-f2d0b31829ab/scratchpad';
const SRC = 'C:/Users/saulo/AppData/Roaming/bible-slow/videos/nwt_19_083_LSB_480p.mp4';

/**
 * Reproduz o caso relatado: um versiculo la pelo meio do capitulo, com camera
 * lenta e zoom. O player mostrava a duracao do RESTO do arquivo de origem
 * (duracao do capitulo menos o ponto de corte) em vez da duracao real.
 */
const CENARIOS: { nome: string; edit: EditState }[] = [
  {
    nome: 'so corte',
    edit: { ranges: [{ start: 90, end: 101.4 }], speedRegions: [], zoomRegions: [], smoothSlowMotion: false },
  },
  {
    nome: 'corte + camera lenta',
    edit: {
      ranges: [{ start: 90, end: 101.4 }],
      speedRegions: [{ id: 's', start: 8.65, end: 11.27, speed: 0.5 }],
      zoomRegions: [],
      smoothSlowMotion: false,
    },
  },
  {
    nome: 'corte + zoom',
    edit: {
      ranges: [{ start: 90, end: 101.4 }],
      speedRegions: [],
      zoomRegions: [{ id: 'z', start: 8.7, end: 11.37, zoom: 1.4, cx: 0.5, cy: 0.45, ramp: 0.4 }],
      smoothSlowMotion: false,
    },
  },
  {
    nome: 'corte + lenta + zoom (caso relatado)',
    edit: {
      ranges: [{ start: 90, end: 101.4 }],
      speedRegions: [{ id: 's', start: 8.65, end: 11.27, speed: 0.5 }],
      zoomRegions: [{ id: 'z', start: 8.7, end: 11.37, zoom: 1.4, cx: 0.5, cy: 0.45, ramp: 0.4 }],
      smoothSlowMotion: false,
    },
  },
  {
    nome: 'lenta + zoom + SUAVIZAR ligado',
    edit: {
      ranges: [{ start: 90, end: 101.4 }],
      speedRegions: [{ id: 's', start: 8.65, end: 11.27, speed: 0.5 }],
      zoomRegions: [{ id: 'z', start: 8.7, end: 11.37, zoom: 1.4, cx: 0.5, cy: 0.45, ramp: 0.4 }],
      smoothSlowMotion: true,
    },
  },
  {
    nome: 'so camera lenta + SUAVIZAR ligado',
    edit: {
      ranges: [{ start: 90, end: 101.4 }],
      speedRegions: [{ id: 's', start: 8.65, end: 11.27, speed: 0.5 }],
      zoomRegions: [],
      smoothSlowMotion: true,
    },
  },
];

const probe = (path: string, campos: string) =>
  spawnSync(
    'ffprobe',
    ['-v', 'error', '-select_streams', 'v:0', '-count_frames', '-show_entries', campos, '-of', 'default=nw=1', path],
    { encoding: 'utf8' },
  ).stdout.trim();

let fail = 0;
console.log('origem: Salmos 83 (258.7s), trecho 90s-101.4s\n');

for (const [i, c] of CENARIOS.entries()) {
  const out = `${SP}/dur${i}.mp4`;
  const built = buildExport({
    sourcePath: SRC,
    outputPath: out,
    edit: c.edit,
    width: 960,
    height: 540,
    frameRate: 29.97002997002997,
    crf: 22,
  });

  const r = spawnSync('ffmpeg', ['-hide_banner', '-v', 'error', '-y', ...built.args], { encoding: 'utf8' });
  if (r.status !== 0) {
    console.log(`${c.nome}: ffmpeg falhou\n${r.stderr.slice(0, 500)}`);
    fail++;
    continue;
  }

  const info = probe(out, 'format=duration:stream=nb_read_frames');
  const dur = Number(/duration=([\d.]+)/.exec(info)?.[1] ?? 0);
  const frames = Number(/nb_read_frames=(\d+)/.exec(info)?.[1] ?? 0);
  const previsto = outputDuration(buildAtoms(c.edit.ranges, c.edit.speedRegions));

  // TODAS as streams, nao so a de video: os players mostram a MAIOR duracao
  // entre elas, e era ai que uma stream de dados herdada da origem fazia um
  // corte de 14s aparecer como 6 minutos.
  const todas = spawnSync(
    'ffprobe',
    ['-v', 'error', '-show_entries', 'stream=index,codec_type,duration', '-of', 'csv=p=0', out],
    { encoding: 'utf8' },
  ).stdout.trim();
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
}

console.log('\n' + (fail ? `FALHOU em ${fail} cenario(s)` : 'TUDO OK'));
process.exit(fail ? 1 : 0);
