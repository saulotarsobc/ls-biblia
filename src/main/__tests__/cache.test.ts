import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CacheManager, isInside } from '../cache.ts';

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

const raiz = await mkdtemp(join(tmpdir(), 'ls-biblia-cache-'));
const videosDir = join(raiz, 'videos');
const catalogDir = join(raiz, 'catalogo');
await mkdir(videosDir, { recursive: true });
await mkdir(catalogDir, { recursive: true });

const encher = (n: number) => 'x'.repeat(n);
const DIA = 1000 * 60 * 60 * 24;

await writeFile(join(videosDir, 'nwt_03_011_LSB_480p.mp4'), encher(500));
await writeFile(join(videosDir, 'nwt_03_001_LSB_720p.mp4'), encher(900));
await writeFile(join(videosDir, 'nwt_19_083_LSB_240p.mp4.part'), encher(100));
await writeFile(join(videosDir, 'anotacoes.txt'), encher(7));
await writeFile(
  join(catalogDir, 'book-3-LSB.json'),
  JSON.stringify({ booknum: 3, name: 'Levítico', chapters: new Array(27).fill({}), fetchedAt: Date.now() }),
);
await writeFile(
  join(catalogDir, 'book-19-LSB.json'),
  JSON.stringify({ booknum: 19, name: 'Salmos', chapters: [{}], fetchedAt: Date.now() - 30 * DIA }),
);
await writeFile(join(catalogDir, 'book-40-LSB.json'), '{ isto nao e json');

const cache = new CacheManager(videosDir, catalogDir);

console.log('\n== leitura das pastas ==');
let r = await cache.report();
check('acha os videos completos e o interrompido', r.videos.length, 3);
check(
  'ordena por livro e capitulo',
  r.videos.map((v) => `${v.booknum}:${v.track}`),
  ['3:1', '3:11', '19:83'],
);
check('marca o .part como incompleto', r.videos.find((v) => v.partial)?.track, 83);
check('arquivo estranho vai para outros, nao some do total', r.strays.length, 1);
check('e sabe de que pasta ele veio', r.strays[0].where, 'videos');
check('le nome e numero de capitulos do catalogo', [r.catalogs[0].name, r.catalogs[0].chapters], ['Levítico', 27]);
check('catalogo recente nao esta vencido', r.catalogs[0].stale, false);
check('catalogo de 30 dias esta vencido', r.catalogs.find((c) => c.booknum === 19)?.stale, true);
// JSON quebrado ainda ocupa disco: precisa aparecer na lista para poder ser apagado
const quebrado = r.catalogs.find((c) => c.booknum === 40);
check('catalogo corrompido aparece mesmo assim', quebrado?.chapters, 0);
check('e usa o nome conhecido do livro', quebrado?.name, 'Mateus');
check('e conta como vencido', quebrado?.stale, true);

console.log('\n== remocao ==');
const alvo = r.videos.find((v) => v.track === 11)!;
let res = await cache.remove([alvo.path]);
check('remove um arquivo', [res.removed, res.errors.length], [1, 0]);
check('devolve os bytes liberados', res.freed, 500);
r = await cache.report();
check('e ele some do relatorio', r.videos.length, 2);

console.log('\n== a remocao nao sai das pastas do app ==');
const forasteiro = join(raiz, 'nao-mexa.txt');
await writeFile(forasteiro, encher(10));
res = await cache.remove([forasteiro, join(videosDir, '..', 'nao-mexa.txt')]);
check('recusa caminho de fora', res.removed, 0);
check('e explica por que', res.errors.length, 2);
check('o arquivo continua la', (await cache.report()).videos.length, 2);

console.log('\n== limpeza por tipo ==');
res = await cache.clear('videos');
check('leva junto os arquivos estranhos da pasta de videos', res.removed, 3);
r = await cache.report();
check('pasta de videos vazia', r.videos.length + r.strays.length, 0);
check('catalogo intacto', r.catalogs.length, 3);

res = await cache.clear('all');
r = await cache.report();
check('limpar tudo esvazia o catalogo', r.catalogs.length, 0);

console.log('\n== pasta que ainda nao existe ==');
const vazio = new CacheManager(join(raiz, 'nada'), join(raiz, 'nada2'));
const rv = await vazio.report();
check('nao explode, so devolve vazio', [rv.videos.length, rv.catalogs.length], [0, 0]);

console.log('\n== guarda de caminho ==');
check('mesmo diretorio passa', isInside(videosDir, videosDir), true);
check('arquivo de dentro passa', isInside(videosDir, join(videosDir, 'a.mp4')), true);
check('subida de diretorio e barrada', isInside(videosDir, join(videosDir, '..', 'a.mp4')), false);
check('prefixo parecido nao passa', isInside(videosDir, videosDir + '-outro'), false);

await rm(raiz, { recursive: true, force: true });

console.log('\n' + (fail ? 'FALHOU: ' : 'TUDO OK: ') + pass + ' passaram, ' + fail + ' falharam');
process.exit(fail ? 1 : 0);
