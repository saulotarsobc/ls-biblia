import { buildExport } from '../export.ts';
import { versesToRanges, outputDuration, buildAtoms } from '../../shared/timeline.ts';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const SP =
  'C:/Users/saulo/AppData/Local/Temp/claude/c--Users-saulo-Documents-projetos-sentinela-carousel-video-bible-slow/fe583def-c4e0-4c55-ac44-f2d0b31829ab/scratchpad';
const SRC = SP + '/gen1.mp4';

// marcadores reais de Genesis 1, vindos da propria API
const raw = JSON.parse(readFileSync(SP + '/t1.json', 'utf8'));
const md = raw.files.LSB.MP4.find((f: any) => f.label === '720p').markers.markers;
const tc = (s: string) => {
  const [h, m, x] = s.split(':');
  return +h * 3600 + +m * 60 + +x;
};
const verses = md.map((m: any) => ({
  verseNumber: m.verseNumber,
  start: tc(m.startTime),
  end: tc(m.startTime) + tc(m.duration),
  label: m.label,
}));
console.log('versiculos com marcador:', verses.length);

// versiculos 1-3 e o 9 -> deve virar 2 trechos, nao 4
const picked = verses.filter((v: any) => [1, 2, 3, 9].includes(v.verseNumber));
const ranges = versesToRanges(picked);
console.log('trechos:', ranges.map((r: any) => r.start.toFixed(2) + '-' + r.end.toFixed(2)).join('  '));

const edit = {
  ranges,
  // camera lenta 0.4x atravessando a fronteira entre os dois trechos
  speedRegions: [{ id: 's1', start: 30, end: 42, speed: 0.4 }],
  // faixa de zoom no meio: antes e depois dela o quadro volta a ficar cheio
  zoomRegions: [{ id: 'z1', start: 20, end: 40, zoom: 1.8, cx: 0.5, cy: 0.42, ramp: 0.5 }],
  smoothSlowMotion: false,
};

const req = {
  sourcePath: SRC,
  outputPath: SP + '/final.mp4',
  edit,
  width: 416,
  height: 234,
  frameRate: 29.97002997002997,
  crf: 20,
};
const built = buildExport(req);
console.log('atomos:', built.atomCount, '| duracao prevista:', built.expectedDuration.toFixed(3) + 's');

const t0 = Date.now();
const r = spawnSync('ffmpeg', ['-hide_banner', '-v', 'error', '-y', ...built.args], { encoding: 'utf8' });
console.log('render:', ((Date.now() - t0) / 1000).toFixed(1) + 's', '| exit', r.status);
if (r.status !== 0) {
  console.log(r.stderr.slice(0, 2000));
  process.exit(1);
}

const probe = spawnSync(
  'ffprobe',
  [
    '-v',
    'error',
    '-select_streams',
    'v:0',
    '-count_frames',
    '-show_entries',
    'stream=width,height,nb_read_frames',
    '-show_entries',
    'format=duration',
    '-of',
    'csv=p=0',
    req.outputPath,
  ],
  { encoding: 'utf8' },
);
const [dims, dur] = probe.stdout.trim().split('\n');
const real = parseFloat(dur);
console.log('saida:', dims, '| duracao real:', real.toFixed(3) + 's');
const diff = Math.abs(real - built.expectedDuration);
console.log(
  diff < 0.15
    ? '\nOK: duracao real bate com a prevista (dif ' + diff.toFixed(3) + 's)'
    : '\nFALHOU: dif de ' + diff.toFixed(3) + 's',
);
process.exit(diff < 0.15 ? 0 : 1);
