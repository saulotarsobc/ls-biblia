import { normalize, sep } from 'node:path';

const SCHEME = 'media';

// mesma logica de src/main/index.ts
const toMediaUrl = (filePath: string) => `${SCHEME}://local/${encodeURIComponent(filePath.replace(/\\/g, '/'))}`;

const parse = (url: string) => decodeURIComponent(new URL(url).pathname.replace(/^\//, ''));

const isInside = (candidate: string, base: string) => {
  const b = normalize(base).toLowerCase();
  const t = normalize(candidate).toLowerCase();
  return t === b || t.startsWith(b.endsWith(sep) ? b : b + sep);
};

let pass = 0;
let fail = 0;
const check = (name: string, got: unknown, want: unknown) => {
  if (got === want) {
    pass++;
    console.log('  ok  ' + name);
  } else {
    fail++;
    console.log(`  FAIL ${name}\n       got  ${got}\n       want ${want}`);
  }
};

const VIDEOS = 'C:\\Users\\saulo\\AppData\\Roaming\\bible-slow\\videos';
const FILE = VIDEOS + '\\nwt_19_083_LSB_480p.mp4';

console.log('\n== ida e volta do caminho pela URL do protocolo ==');
const url = toMediaUrl(FILE);
console.log('  url:', url);
check('volta igual ao original', parse(url), FILE.replace(/\\/g, '/'));
check('host e fixo', new URL(url).host, 'local');
check('drive nao vira host', new URL(url).host === 'c', false);

console.log('\n== guarda de pasta ==');
check('arquivo de dentro passa', isInside(parse(url), VIDEOS), true);
check(
  'arquivo de fora e barrado',
  isInside(parse(toMediaUrl('C:\\Windows\\System32\\drivers\\etc\\hosts')), VIDEOS),
  false,
);
check(
  'truque de subida de diretorio e barrado',
  isInside(parse(toMediaUrl(VIDEOS + '\\..\\..\\segredo.txt')), VIDEOS),
  false,
);
check(
  'pasta parecida nao passa',
  isInside(parse(toMediaUrl('C:\\Users\\saulo\\AppData\\Roaming\\bible-slow\\videos-outros\\x.mp4')), VIDEOS),
  false,
);

console.log('\n== formato antigo, que quebrava ==');
const antigo = `${SCHEME}:///${encodeURI(FILE.replace(/\\/g, '/'))}`;
console.log('  url antiga:', antigo);
const antigoParsed = decodeURIComponent(new URL(antigo).pathname).replace(/^\//, '');
check('formato antigo devolvia caminho diferente', antigoParsed === FILE.replace(/\\/g, '/'), true);

console.log('\n' + (fail ? 'FALHOU: ' : 'TUDO OK: ') + pass + ' passaram, ' + fail + ' falharam');
process.exit(fail ? 1 : 0);
