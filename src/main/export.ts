import { buildAtoms, outputDuration, zoomExpr } from '../shared/timeline.ts';
import type { Atom, ExportRequest } from '../shared/types';

/**
 * Supersampling antes do zoompan. Reduz o tremido do arredondamento inteiro de
 * x/y durante zooms lentos. Custa ~1.5x de tempo, e so entra quando ha zoom.
 */
const SUPERSAMPLE = 2;

function hasZoom(req: ExportRequest): boolean {
  const ks = req.edit.zoomRegions;
  return ks.length > 0 && ks.some((k) => Math.abs(k.zoom - 1) > 1e-3);
}

/**
 * Filtros de zoom de um atomo.
 *
 * Detalhes que so apareceram testando com ffmpeg de verdade:
 *
 * - `crop` NAO serve: as expressoes de w/h nao enxergam o tempo, porque o tamanho
 *   de saida precisa ser fixo na inicializacao. Zoom animado exige `zoompan`.
 * - A variavel de tempo do zoompan chama `time`, nao `t`.
 * - O zoompan limita `zoom` a no minimo 1.0. Entao o supersampling nao pode ser
 *   feito dividindo o zoom (seria clampado e o zoom congelaria): o jeito certo e
 *   ampliar tambem o `s` e reduzir a imagem depois.
 * - O `s` do zoompan e um RECORTE da imagem ampliada, entao a janela visivel em
 *   coordenadas de entrada mede `ow/zoom` — nao `iw/zoom`, que so coincide
 *   quando a saida tem o mesmo tamanho da entrada.
 */
function zoomFilters(req: ExportRequest, atom: Atom): string[] {
  const ks = req.edit.zoomRegions;
  const z = zoomExpr(ks, atom, 'zoom', 'time');
  const cx = zoomExpr(ks, atom, 'cx', 'time');
  const cy = zoomExpr(ks, atom, 'cy', 'time');

  const sw = req.width * SUPERSAMPLE;
  const sh = req.height * SUPERSAMPLE;

  return [
    `scale=iw*${SUPERSAMPLE}:ih*${SUPERSAMPLE}:flags=bicubic`,
    `zoompan=z='${z}':x='(${cx})*iw-(ow/zoom)/2':y='(${cy})*ih-(oh/zoom)/2':d=1:s=${sw}x${sh}:fps=${req.frameRate}`,
    `scale=${req.width}:${req.height}:flags=lanczos`,
  ];
}

export interface BuiltExport {
  args: string[];
  expectedDuration: number;
  atomCount: number;
}

/**
 * Monta a exportacao num unico passe: corte, velocidade e zoom juntos, direto do
 * arquivo original — uma so geracao de recompressao.
 *
 * Usa um input por atomo com `-ss` antes do `-i` (busca rapida) em vez de
 * split+trim, que faria o ffmpeg bufferizar na RAM os frames de trechos distantes.
 */
export function buildExport(req: ExportRequest): BuiltExport {
  const atoms = buildAtoms(req.edit.ranges, req.edit.speedRegions);
  if (atoms.length === 0) throw new Error('Nada selecionado para exportar.');

  const fps = req.frameRate;
  const useZoom = hasZoom(req);
  const inputs: string[] = [];
  const chains: string[] = [];

  atoms.forEach((atom, i) => {
    const dur = atom.srcEnd - atom.srcStart;
    inputs.push('-ss', atom.srcStart.toFixed(6), '-t', dur.toFixed(6), '-i', req.sourcePath);

    const filters: string[] = [];
    // velocidade 0.5 = metade do ritmo = tempo multiplicado por 2
    const stretch = (1 / atom.speed).toFixed(6);

    if (useZoom) {
      filters.push(...zoomFilters(req, atom));
      // O zoompan recarimba os PTS num timebase proprio: aplicar setpts em cima
      // disso da duracoes absurdas. Reconstruir o tempo a partir do indice do
      // frame (N) devolve uma timeline limpa, ja com a camera lenta aplicada.
      filters.push('settb=AVTB', `setpts=N/${fps}/TB*${stretch}`);
    } else {
      filters.push(`setpts=(PTS-STARTPTS)*${stretch}`);
    }

    if (req.edit.smoothSlowMotion && atom.speed < 1) {
      // interpola frames em vez de duplicar; caro, so vale nos trechos lentos
      filters.push(`minterpolate=fps=${fps}:mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1`);
    }
    filters.push('setsar=1');

    // `:v:0` e explicito de proposito: estes MP4 trazem uma capa PNG 600x600 como
    // segunda stream de video, e o ffmpeg poderia escolher ela como "melhor".
    chains.push(`[${i}:v:0]${filters.join(',')}[v${i}]`);
  });

  const concatIn = atoms.map((_, i) => `[v${i}]`).join('');
  chains.push(`${concatIn}concat=n=${atoms.length}:v=1:a=0,fps=${fps},format=yuv420p[out]`);

  const args = [
    ...inputs,
    '-filter_complex',
    chains.join(';'),
    '-map',
    '[out]',
    '-an',
    // Descarta streams de dados e legendas. Estes MP4 trazem uma stream
    // `bin_data` junto do video, e ela vinha parar na saida com a duracao do
    // RESTO do arquivo de origem — os players usam a maior duracao entre as
    // streams, entao um corte de 14s aparecia como 6 minutos.
    '-dn',
    '-sn',
    // nada de metadados nem capitulos herdados da origem
    '-map_metadata',
    '-1',
    '-map_chapters',
    '-1',
    '-c:v',
    'libx264',
    '-preset',
    'medium',
    '-crf',
    String(req.crf),
    '-pix_fmt',
    'yuv420p',
    '-movflags',
    '+faststart',
    req.outputPath,
  ];

  return {
    args,
    expectedDuration: outputDuration(atoms),
    atomCount: atoms.length,
  };
}
