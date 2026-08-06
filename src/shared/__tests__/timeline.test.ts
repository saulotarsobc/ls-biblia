import * as T from '../timeline.ts';

let pass = 0,
  fail = 0;
const eq = (name: string, got: any, want: any) => {
  const g = JSON.stringify(got),
    w = JSON.stringify(want);
  if (g === w) {
    pass++;
    console.log('  ok  ' + name);
  } else {
    fail++;
    console.log('  FAIL ' + name + '\n       got  ' + g + '\n       want ' + w);
  }
};
const close = (name: string, got: number, want: number, tol = 1e-6) => {
  if (Math.abs(got - want) < tol) {
    pass++;
    console.log('  ok  ' + name);
  } else {
    fail++;
    console.log('  FAIL ' + name + ' got ' + got + ' want ' + want);
  }
};

console.log('\n== versesToRanges: contiguos viram um corte so ==');
const verses = [
  { verseNumber: 1, start: 13.279, end: 28.594, label: 'a' },
  { verseNumber: 2, start: 28.595, end: 42.208, label: 'b' },
  { verseNumber: 3, start: 42.208, end: 52.084, label: 'c' },
  { verseNumber: 9, start: 200.0, end: 210.0, label: 'i' },
];
eq('4 versiculos -> 2 trechos', T.versesToRanges(verses).length, 2);
eq('primeiro trecho unido', T.versesToRanges(verses)[0], { start: 13.279, end: 52.084 });

console.log('\n== mapeamento edicao <-> origem ==');
const ranges = [
  { start: 10, end: 20 },
  { start: 100, end: 105 },
];
close('duracao de edicao', T.editDuration(ranges), 15);
close('edit 0   -> src 10', T.editToSource(0, ranges), 10);
close('edit 9.9 -> src 19.9', T.editToSource(9.9, ranges), 19.9);
close('edit 10  -> src 100 (pula o buraco)', T.editToSource(10, ranges), 100);
close('edit 12  -> src 102', T.editToSource(12, ranges), 102);
eq('src 15  -> edit 5', T.sourceToEdit(15, ranges), 5);
eq('src 102 -> edit 12', T.sourceToEdit(102, ranges), 12);
eq('src 50 fora dos trechos -> null', T.sourceToEdit(50, ranges), null);
eq('ida e volta', T.sourceToEdit(T.editToSource(12, ranges), ranges), 12);

console.log('\n== atomos: camera lenta atravessando um corte ==');
const regions = [{ id: 'r1', start: 8, end: 12, speed: 0.5 }];
const atoms = T.buildAtoms(ranges, regions);
eq('quantidade de atomos', atoms.length, 4);
eq('atomo 0: normal ate 8', { s: atoms[0].srcStart, e: atoms[0].srcEnd, v: atoms[0].speed }, { s: 10, e: 18, v: 1 });
eq(
  'atomo 1: lento 8-10 (fim do 1o trecho)',
  { s: atoms[1].srcStart, e: atoms[1].srcEnd, v: atoms[1].speed },
  { s: 18, e: 20, v: 0.5 },
);
eq(
  'atomo 2: lento 10-12 (inicio do 2o trecho)',
  { s: atoms[2].srcStart, e: atoms[2].srcEnd, v: atoms[2].speed },
  { s: 100, e: 102, v: 0.5 },
);
eq(
  'atomo 3: normal ate o fim',
  { s: atoms[3].srcStart, e: atoms[3].srcEnd, v: atoms[3].speed },
  { s: 102, e: 105, v: 1 },
);
// 4s de edicao em 0.5x viram 8s de saida: 15 - 4 + 8 = 19
close('duracao final estica a camera lenta', T.outputDuration(atoms), 8 + 4 + 4 + 3);
close(
  'soma da origem preservada',
  atoms.reduce((s, a) => s + (a.srcEnd - a.srcStart), 0),
  15,
);

console.log('\n== zoom por faixas ==');
// aproxima entre 4.8s e 7.8s, com meio segundo de rampa em cada ponta
const faixa = [{ id: 'z1', start: 4.8, end: 7.8, zoom: 2.2, cx: 0.41, cy: 0.25, ramp: 0.5 }];

// este e exatamente o bug relatado: com keyframe solto o video ja comecava
// ampliado; com faixa, antes dela o enquadramento tem de ser o cheio
close('antes da faixa = quadro cheio', T.zoomAt(0, faixa).zoom, 1);
close('antes da faixa, centro no meio', T.zoomAt(0, faixa).cx, 0.5);
close('logo antes de comecar ainda e 1x', T.zoomAt(4.79, faixa).zoom, 1);
close('depois da faixa volta ao cheio', T.zoomAt(9, faixa).zoom, 1);
close('no miolo aplica o zoom todo', T.zoomAt(6.3, faixa).zoom, 2.2);
close('no miolo aplica o enquadre', T.zoomAt(6.3, faixa).cx, 0.41);
eq('sem faixa nenhuma = neutro', T.zoomAt(5, []), { zoom: 1, cx: 0.5, cy: 0.5 });

// a rampa sobe do cheio ate o alvo sem passar do alvo nem voltar atras
const meioRampa = T.zoomAt(4.8 + 0.25, faixa).zoom;
if (meioRampa > 1.01 && meioRampa < 2.19) {
  pass++;
  console.log('  ok  no meio da rampa fica entre 1x e o alvo (' + meioRampa.toFixed(2) + 'x)');
} else {
  fail++;
  console.log('  FAIL meio da rampa deu ' + meioRampa);
}
let crescendo = true;
for (let t = 4.8; t < 5.3; t += 0.05) {
  if (T.zoomAt(t + 0.05, faixa).zoom < T.zoomAt(t, faixa).zoom - 1e-9) crescendo = false;
}
if (crescendo) {
  pass++;
  console.log('  ok  a rampa de entrada so cresce');
} else {
  fail++;
  console.log('  FAIL a rampa de entrada oscila');
}

console.log('\n== duas faixas nao interferem uma na outra ==');
const duas = [
  { id: 'a', start: 1, end: 3, zoom: 2, cx: 0.3, cy: 0.3, ramp: 0.3 },
  { id: 'b', start: 6, end: 8, zoom: 3, cx: 0.7, cy: 0.7, ramp: 0.3 },
];
close('entre as duas volta a 1x', T.zoomAt(4.5, duas).zoom, 1);
close('miolo da primeira', T.zoomAt(2, duas).zoom, 2);
close('miolo da segunda', T.zoomAt(7, duas).zoom, 3);
close('centro da segunda nao vaza para a primeira', T.zoomAt(2, duas).cx, 0.3);

console.log('\n== expressao ffmpeg bate com o preview ==');
// avalia a expressao do ffmpeg em JS: desescapa as virgulas e liga as funcoes
const ev = (e: string, t: number) =>
  Function(
    't',
    'const lt=(a,b)=>a<b?1:0;' +
      'const iff=(c,a,b)=>c?a:b;' +
      'const clip=(x,lo,hi)=>Math.min(Math.max(x,lo),hi);' +
      'const between=(x,lo,hi)=>(x>=lo&&x<=hi)?1:0;' +
      'const min=Math.min;' +
      'return ' +
      e.replace(/\\,/g, ',').replace(/if\(/g, 'iff(') +
      ';',
  )(t);

const atomN = { srcStart: 0, srcEnd: 10, speed: 1, editStart: 0 };
for (const prop of ['zoom', 'cx', 'cy'] as const) {
  const expr = T.zoomExpr(faixa, atomN, prop);
  let pior = 0;
  for (let t = 0; t <= 10; t += 0.05) {
    pior = Math.max(pior, Math.abs(ev(expr, t) - T.zoomAt(t, faixa)[prop]));
  }
  close(`${prop}: expressao bate em 200 instantes (pior desvio)`, pior, 0, 1e-5);
}

// o zoompan roda antes da mudanca de velocidade, entao seu tempo local e o da
// origem: no atomo que comeca em edit 4.8, o tempo local 1.5 vale como edit 6.3
const atomS = { srcStart: 18, srcEnd: 21, speed: 0.5, editStart: 4.8 };
const exprS = T.zoomExpr(faixa, atomS, 'zoom');
close('camera lenta: local 1.5 -> edit 6.3', ev(exprS, 1.5), T.zoomAt(6.3, faixa).zoom, 1e-4);
close('camera lenta: local 0 -> edit 4.8', ev(exprS, 0), T.zoomAt(4.8, faixa).zoom, 1e-4);

console.log('\n== rampa nunca passa da metade da faixa ==');
const curta = { id: 'c', start: 0, end: 0.4, zoom: 3, cx: 0.5, cy: 0.5, ramp: 5 };
close('rampa cabe na metade', T.effectiveRamp(curta), 0.2);
close('faixa curta ainda chega no alvo no meio', T.zoomAt(0.2, [curta]).zoom, 3, 1e-6);
close('faixa curta comeca em 1x', T.zoomAt(0.001, [curta]).zoom, 1, 0.05);

console.log('\n== enquadramento do preview bate com o recorte do ffmpeg ==');
// Extrai do transform CSS a janela que fica visivel: o transform mapeia a fracao
// p do quadro para p*z + t, entao e visivel o p que cai em [0,1].
const janelaDoCss = (css: string) => {
  const m = /translate\(([-\d.]+)%, ([-\d.]+)%\) scale\(([\d.]+)\)/.exec(css)!;
  const [tx, ty, z] = [Number(m[1]) / 100, Number(m[2]) / 100, Number(m[3])];
  return {
    largura: 1 / z,
    centroX: (1 - 2 * tx) / (2 * z),
    centroY: (1 - 2 * ty) / (2 * z),
  };
};

for (const [zoom, cx, cy] of [
  [2, 0.5, 0.5],
  [2, 0.3, 0.7],
  [1.5, 0.5, 0.4],
  [3, 0.8, 0.2],
] as const) {
  const alvo = T.clampCenter(zoom, cx, cy);
  const j = janelaDoCss(T.previewTransform(zoom, cx, cy));
  close(`zoom ${zoom} centro (${cx},${cy}): largura da janela = 1/zoom`, j.largura, 1 / zoom, 1e-3);
  close(`zoom ${zoom} centro (${cx},${cy}): centro X bate`, j.centroX, alvo.cx, 1e-3);
  close(`zoom ${zoom} centro (${cx},${cy}): centro Y bate`, j.centroY, alvo.cy, 1e-3);
}

// o jeito antigo (transform-origin no ponto) errava fora do centro
const centroAntigo = (ox: number, z: number) => ox + (1 - 2 * ox) / (2 * z);
close('origin no centro por acaso acertava', centroAntigo(0.5, 2), 0.5, 1e-9);
if (Math.abs(centroAntigo(0.3, 2) - 0.3) > 0.01) {
  pass++;
  console.log('  ok  origin fora do centro errava (0.3 virava ' + centroAntigo(0.3, 2).toFixed(2) + ')');
} else {
  fail++;
  console.log('  FAIL esperava que o jeito antigo errasse fora do centro');
}

console.log('\n== clamp nas bordas ==');
eq('zoom 1 fica sempre no centro', T.clampCenter(1, 0.2, 0.9), { zoom: 1, cx: 0.5, cy: 0.5 });
eq('zoom 2 nao deixa passar da borda', T.clampCenter(2, 0, 1), { zoom: 2, cx: 0.25, cy: 0.75 });
eq('zoom 4 prende mais perto da borda', T.clampCenter(4, 0, 1), { zoom: 4, cx: 0.125, cy: 0.875 });

console.log('\n' + (fail ? 'FALHOU: ' : 'TUDO OK: ') + pass + ' passaram, ' + fail + ' falharam');
process.exit(fail ? 1 : 0);
