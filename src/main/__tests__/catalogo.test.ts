import { QUALITIES } from '../../shared/types.ts';
import { parseVideoFileName, videoFileName } from '../download.ts';
import { BookUnavailableError, parseBook, parseCatalogFileName } from '../jw.ts';

let pass = 0;
let fail = 0;
const check = (name: string, got: unknown, want: unknown) => {
  const g = JSON.stringify(got);
  const w = JSON.stringify(want);
  if (g === w) {
    pass++;
    console.log('  ok  ' + name);
  } else {
    fail++;
    console.log(`  FAIL ${name}\n       got  ${g}\n       want ${w}`);
  }
};

/** Um item da API, no formato que GETPUBMEDIALINKS devolve. */
const item = (track: number, label: string, ext: string, markers: number | null = null) => ({
  title: `Capítulo ${track}`,
  label,
  track,
  duration: 100 + track,
  filesize: 1000 + track,
  frameWidth: 1280,
  frameHeight: 720,
  frameRate: 29.97,
  file: { url: `https://cdn/nwt_03_Le_LSB_${track}_${label}.${ext}`, checksum: `sum-${track}-${label}` },
  markers: markers === null ? null : { markers: Array.from({ length: markers }, (_, i) => marker(i + 1)) },
});

const marker = (verseNumber: number) => ({
  verseNumber,
  startTime: `00:00:${String(verseNumber).padStart(2, '0')}.000`,
  duration: '00:00:00.500',
  label: `Versículo ${verseNumber}`,
});

/** Todas as qualidades de um capitulo, com marcadores so no 720p (como a API faz). */
const chapterItems = (track: number, ext: string) => QUALITIES.map((q) => item(track, q, ext, q === '720p' ? 3 : null));

console.log('\n== Levitico: 26 capitulos em .m4v e so o 11 em .mp4 ==');
// exatamente a forma da resposta real: o app pedia apenas MP4 e via 1 capitulo
const levitico = parseBook(
  {
    pubName: 'Levítico',
    files: {
      LSB: {
        MP4: chapterItems(11, 'mp4'),
        M4V: [1, 2, 3, 12, 27].flatMap((t) => chapterItems(t, 'm4v')),
      },
    },
  },
  3,
);

check('junta os dois formatos', levitico.chapters.length, 6);
check(
  'capitulos vem ordenados por numero',
  levitico.chapters.map((c) => c.track),
  [1, 2, 3, 11, 12, 27],
);
check('capitulo so em m4v tem as 4 qualidades', Object.keys(levitico.chapters[0].files).sort(), [
  '240p',
  '360p',
  '480p',
  '720p',
]);
check('capitulo so em m4v mantem os marcadores', levitico.chapters[0].verses.length, 3);
check('a url preservada e a m4v', levitico.chapters[0].files['720p']?.url.endsWith('.m4v'), true);
check('o capitulo que ja funcionava segue em mp4', levitico.chapters[3].files['720p']?.url.endsWith('.mp4'), true);

console.log('\n== livro publicado nos dois formatos: MP4 tem preferencia ==');
const ambos = parseBook(
  { pubName: 'Teste', files: { LSB: { MP4: chapterItems(1, 'mp4'), M4V: chapterItems(1, 'm4v') } } },
  1,
);
check('nao duplica o capitulo', ambos.chapters.length, 1);
check('escolhe o mp4 em cada qualidade', ambos.chapters[0].files['480p']?.url.endsWith('.mp4'), true);

console.log('\n== livro sem nenhum video ==');
let erro: unknown = null;
try {
  parseBook({ pubName: 'Vazio', files: { LSB: { MP3: [] } } }, 7);
} catch (e) {
  erro = e;
}
check('avisa que o livro nao saiu em LSB', erro instanceof BookUnavailableError, true);

console.log('\n== nomes de arquivo do cache ==');
check('nome do video', videoFileName(3, 11, '480p'), 'nwt_03_011_LSB_480p.mp4');
check('ida e volta do nome do video', parseVideoFileName(videoFileName(3, 11, '480p')), {
  booknum: 3,
  track: 11,
  quality: '480p',
  partial: false,
});
check('download interrompido e reconhecido', parseVideoFileName('nwt_19_083_LSB_720p.mp4.part')?.partial, true);
check('livro de tres digitos ainda casa', parseVideoFileName('nwt_19_150_LSB_240p.mp4')?.track, 150);
check('qualidade fora da lista nao casa', parseVideoFileName('nwt_03_011_LSB_999p.mp4'), null);
check('exportacao do usuario nao casa', parseVideoFileName('Levitico-11.mp4'), null);
check('nome do catalogo', parseCatalogFileName('book-3-LSB.json'), 3);
check('catalogo de outro idioma nao casa', parseCatalogFileName('book-3-ASL.json'), null);
check('qualquer outro arquivo nao casa', parseCatalogFileName('config.json'), null);

console.log('\n' + (fail ? 'FALHOU: ' : 'TUDO OK: ') + pass + ' passaram, ' + fail + ' falharam');
process.exit(fail ? 1 : 0);
