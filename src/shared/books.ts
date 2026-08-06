import type { Book } from './types';

/**
 * Nomes conforme retornados pela propria API (campo `pubName`), para o app abrir
 * instantaneo sem esperar rede. A disponibilidade real e conferida em tempo de
 * execucao: em LSB, Levitico (3) ainda responde 404, e a lista cresce com o tempo.
 */
const NAMES: string[] = [
  'Gênesis',
  'Êxodo',
  'Levítico',
  'Números',
  'Deuteronômio',
  'Josué',
  'Juízes',
  'Rute',
  '1 Samuel',
  '2 Samuel',
  '1 Reis',
  '2 Reis',
  '1 Crônicas',
  '2 Crônicas',
  'Esdras',
  'Neemias',
  'Ester',
  'Jó',
  'Salmos',
  'Provérbios',
  'Eclesiastes',
  'Cântico de Salomão',
  'Isaías',
  'Jeremias',
  'Lamentações',
  'Ezequiel',
  'Daniel',
  'Oseias',
  'Joel',
  'Amós',
  'Obadias',
  'Jonas',
  'Miqueias',
  'Naum',
  'Habacuque',
  'Sofonias',
  'Ageu',
  'Zacarias',
  'Malaquias',
  'Mateus',
  'Marcos',
  'Lucas',
  'João',
  'Atos',
  'Romanos',
  '1 Coríntios',
  '2 Coríntios',
  'Gálatas',
  'Efésios',
  'Filipenses',
  'Colossenses',
  '1 Tessalonicenses',
  '2 Tessalonicenses',
  '1 Timóteo',
  '2 Timóteo',
  'Tito',
  'Filêmon',
  'Hebreus',
  'Tiago',
  '1 Pedro',
  '2 Pedro',
  '1 João',
  '2 João',
  '3 João',
  'Judas',
  'Apocalipse',
];

export const BOOKS: Book[] = NAMES.map((name, i) => ({
  booknum: i + 1,
  name,
  testament: i + 1 <= 39 ? 'hebraicas' : 'gregas',
}));

/** Busca sem acento e sem caixa, para "genesis" achar "Gênesis". */
export function normalize(s: string): string {
  return s
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLowerCase()
    .trim();
}

export function searchBooks(query: string): Book[] {
  const q = normalize(query);
  if (!q) return BOOKS;
  return BOOKS.filter((b) => normalize(b.name).includes(q) || String(b.booknum) === q);
}
