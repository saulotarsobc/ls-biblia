import type { Atom, SourceRange, SpeedRegion, Verse, ZoomRegion } from './types';

const EPS = 1e-4;

/** Une versiculos contiguos num unico trecho: 3..10 vira um corte so, nao oito. */
export function versesToRanges(verses: Verse[]): SourceRange[] {
  if (verses.length === 0) return [];
  const sorted = [...verses].sort((a, b) => a.start - b.start);
  const out: SourceRange[] = [{ start: sorted[0].start, end: sorted[0].end }];
  for (const v of sorted.slice(1)) {
    const last = out[out.length - 1];
    // versiculos consecutivos no arquivo encostam um no outro
    if (v.start - last.end <= 0.05) last.end = Math.max(last.end, v.end);
    else out.push({ start: v.start, end: v.end });
  }
  return out;
}

export function normalizeRanges(ranges: SourceRange[]): SourceRange[] {
  const valid = ranges.filter((r) => r.end - r.start > EPS).sort((a, b) => a.start - b.start);
  const out: SourceRange[] = [];
  for (const r of valid) {
    const last = out[out.length - 1];
    if (last && r.start <= last.end + EPS) last.end = Math.max(last.end, r.end);
    else out.push({ ...r });
  }
  return out;
}

interface EditSpan {
  editStart: number;
  editEnd: number;
  src: SourceRange;
}

function editSpans(ranges: SourceRange[]): EditSpan[] {
  let acc = 0;
  return normalizeRanges(ranges).map((src) => {
    const span = { editStart: acc, editEnd: acc + (src.end - src.start), src };
    acc = span.editEnd;
    return span;
  });
}

/** Duracao do video depois do corte, antes de aplicar velocidade. */
export function editDuration(ranges: SourceRange[]): number {
  return normalizeRanges(ranges).reduce((s, r) => s + (r.end - r.start), 0);
}

/** Tempo de edicao -> tempo dentro do arquivo original. */
export function editToSource(t: number, ranges: SourceRange[]): number {
  const spans = editSpans(ranges);
  if (spans.length === 0) return t;
  for (const s of spans) {
    if (t < s.editEnd - EPS) return s.src.start + Math.max(0, t - s.editStart);
  }
  const last = spans[spans.length - 1];
  return last.src.end;
}

/** Tempo do arquivo original -> tempo de edicao. Retorna null fora dos trechos. */
export function sourceToEdit(s: number, ranges: SourceRange[]): number | null {
  for (const span of editSpans(ranges)) {
    if (s >= span.src.start - EPS && s <= span.src.end + EPS) {
      return span.editStart + (s - span.src.start);
    }
  }
  return null;
}

/** Proximo trecho depois de `s`, usado para pular buracos durante o preview. */
export function nextRangeStart(s: number, ranges: SourceRange[]): number | null {
  for (const r of normalizeRanges(ranges)) if (r.start > s + EPS) return r.start;
  return null;
}

export function speedAt(t: number, regions: SpeedRegion[]): number {
  for (const r of regions) {
    if (t >= r.start - EPS && t < r.end - EPS) return r.speed;
  }
  return 1;
}

/**
 * Fatia a timeline em atomos: trechos continuos da origem com velocidade unica.
 * Corta nas bordas dos trechos E nas bordas das regioes de velocidade, entao uma
 * regiao de camera lenta pode atravessar varios versiculos sem problema.
 */
export function buildAtoms(ranges: SourceRange[], regions: SpeedRegion[]): Atom[] {
  const spans = editSpans(ranges);
  if (spans.length === 0) return [];
  const total = spans[spans.length - 1].editEnd;

  const cuts = new Set<number>([0, total]);
  for (const s of spans) {
    cuts.add(s.editStart);
    cuts.add(s.editEnd);
  }
  for (const r of regions) {
    if (r.start > EPS && r.start < total - EPS) cuts.add(r.start);
    if (r.end > EPS && r.end < total - EPS) cuts.add(r.end);
  }

  const pts = [...cuts].sort((a, b) => a - b);
  const atoms: Atom[] = [];
  for (let i = 0; i < pts.length - 1; i++) {
    const a = pts[i];
    const b = pts[i + 1];
    if (b - a < EPS) continue;
    const mid = (a + b) / 2;
    const span = spans.find((s) => mid >= s.editStart && mid < s.editEnd);
    if (!span) continue;
    atoms.push({
      srcStart: span.src.start + (a - span.editStart),
      srcEnd: span.src.start + (b - span.editStart),
      speed: speedAt(mid, regions),
      editStart: a,
    });
  }
  return atoms;
}

/** Duracao final do arquivo exportado, ja com a camera lenta esticando o tempo. */
export function outputDuration(atoms: Atom[]): number {
  return atoms.reduce((s, a) => s + (a.srcEnd - a.srcStart) / a.speed, 0);
}

export interface ZoomValue {
  zoom: number;
  cx: number;
  cy: number;
}

const NEUTRAL: ZoomValue = { zoom: 1, cx: 0.5, cy: 0.5 };

/** Rampa efetiva: nunca passa da metade da faixa, e nunca e zero (evita divisao por 0). */
export function effectiveRamp(region: ZoomRegion): number {
  const metade = (region.end - region.start) / 2;
  return Math.max(1e-3, Math.min(region.ramp, metade));
}

/** Curva de aceleracao suave: sai e chega parado, em vez de virar de uma vez. */
const suavizar = (f: number) => f * f * (3 - 2 * f);

/**
 * Peso da faixa em um instante: 0 fora dela, 1 no miolo, subindo e descendo
 * suave nas rampas. Como as faixas nao se sobrepoem, no maximo uma pesa por vez.
 */
function peso(t: number, r: ZoomRegion): number {
  if (t <= r.start || t >= r.end) return 0;
  const ramp = effectiveRamp(r);
  const f = Math.min(1, Math.max(0, Math.min((t - r.start) / ramp, (r.end - t) / ramp)));
  return suavizar(f);
}

/** Enquadramento em um instante. Fora de qualquer faixa, o quadro cheio. */
export function zoomAt(t: number, regions: ZoomRegion[]): ZoomValue {
  let zoom = 1;
  let cx = 0.5;
  let cy = 0.5;
  for (const r of regions) {
    const w = peso(t, r);
    if (w === 0) continue;
    zoom += (r.zoom - 1) * w;
    cx += (r.cx - 0.5) * w;
    cy += (r.cy - 0.5) * w;
  }
  return { zoom, cx, cy };
}

/**
 * Prende o centro do enquadramento as bordas do quadro, igual ao clamp que o
 * ffmpeg faz com min/max nas expressoes de x/y do zoompan.
 */
export function clampCenter(zoom: number, cx: number, cy: number): ZoomValue {
  if (zoom <= 1) return { zoom, cx: 0.5, cy: 0.5 };
  const half = 0.5 / zoom;
  const fit = (v: number) => Math.min(Math.max(v, half), 1 - half);
  return { zoom, cx: fit(cx), cy: fit(cy) };
}

/**
 * Transform CSS que reproduz no preview exatamente a janela que o ffmpeg recorta.
 *
 * Precisa de `transform-origin: 0 0`. Usar a origem no ponto escolhido NAO
 * funciona: com origem `ox` e escala `z` a janela visivel acaba centrada em
 * `ox + (1-2*ox)/(2*z)`, que so bate com `ox` quando ele vale 0.5. Fora do
 * centro, o preview mostraria um enquadramento diferente do render.
 */
export function previewTransform(zoom: number, cx: number, cy: number): string {
  const c = clampCenter(zoom, cx, cy);
  const tx = (0.5 - c.cx * zoom) * 100;
  const ty = (0.5 - c.cy * zoom) * 100;
  return `translate(${tx.toFixed(4)}%, ${ty.toFixed(4)}%) scale(${zoom.toFixed(4)})`;
}

const n = (v: number) => v.toFixed(6);

/**
 * Gera a expressao ffmpeg de uma propriedade do zoom para um atomo especifico.
 *
 * O zoompan roda ANTES da mudanca de velocidade, entao a variavel de tempo dele
 * conta o tempo local da origem dentro do atomo (0 ate a duracao do trecho). O
 * tempo de edicao correspondente e `editStart + tempo`, o que faz as faixas
 * baterem exatamente com o que o usuario ve no preview.
 *
 * As virgulas vao escapadas com \\, porque o valor inteiro e entregue como um
 * argumento de filtro, onde virgula solta separaria filtros.
 *
 * `varName` e 'time' no zoompan; o padrao 't' serve para conferir em testes.
 */
export function zoomExpr(regions: ZoomRegion[], atom: Atom, prop: keyof ZoomValue, varName = 't'): string {
  const base = NEUTRAL[prop];
  if (regions.length === 0) return n(base);

  const T = `(${n(atom.editStart)}+${varName})`;

  // Mesma soma ponderada do zoomAt: como as faixas nao se sobrepoem, no maximo
  // um termo e diferente de zero em cada instante.
  const termos = regions.map((r) => {
    const ramp = effectiveRamp(r);
    const f = `clip(min((${T}-${n(r.start)})/${n(ramp)}\\,(${n(r.end)}-${T})/${n(ramp)})\\,0\\,1)`;
    const w = `if(between(${T}\\,${n(r.start)}\\,${n(r.end)})\\,(${f})*(${f})*(3-2*(${f}))\\,0)`;
    return `(${n(r[prop] - base)})*(${w})`;
  });

  return `${n(base)}+${termos.join('+')}`;
}

export function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) seconds = 0;
  const m = Math.floor(seconds / 60);
  const s = Math.floor(seconds % 60);
  const cs = Math.floor((seconds % 1) * 100);
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}.${String(cs).padStart(2, '0')}`;
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(0)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
}
