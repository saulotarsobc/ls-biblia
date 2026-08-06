import { readFile, readdir, rm, stat } from 'node:fs/promises';
import { join, normalize, sep } from 'node:path';
import { BOOKS } from '../shared/books.ts';
import type {
  BookDetail,
  CacheKind,
  CacheRemoval,
  CacheReport,
  CachedCatalog,
  CachedVideo,
  StrayFile,
} from '../shared/types';
import { parseVideoFileName } from './download.ts';
import { CATALOG_TTL, parseCatalogFileName } from './jw.ts';

/** `target` esta dentro de `base` (ou e o proprio)? Normaliza e ignora caixa. */
export function isInside(base: string, target: string): boolean {
  const b = normalize(base).toLowerCase();
  const t = normalize(target).toLowerCase();
  return t === b || t.startsWith(b.endsWith(sep) ? b : b + sep);
}

/** Pasta inexistente e cache vazio, nao erro: o app pode nunca ter baixado nada. */
async function listDir(dir: string): Promise<string[]> {
  try {
    return await readdir(dir);
  } catch {
    return [];
  }
}

async function sizeOf(path: string): Promise<number | null> {
  try {
    const s = await stat(path);
    return s.isFile() ? s.size : null;
  } catch {
    return null;
  }
}

/**
 * Le e apaga o que o app guarda em `userData`: os videos baixados e as respostas
 * da API. Nada aqui e essencial — tudo pode ser buscado de novo — entao o unico
 * cuidado real e nunca sair das duas pastas conhecidas.
 */
export class CacheManager {
  private videosDir: string;
  private catalogDir: string;

  constructor(videosDir: string, catalogDir: string) {
    this.videosDir = videosDir;
    this.catalogDir = catalogDir;
  }

  private async scanVideos(): Promise<{ videos: CachedVideo[]; strays: StrayFile[] }> {
    const videos: CachedVideo[] = [];
    const strays: StrayFile[] = [];

    for (const name of await listDir(this.videosDir)) {
      const path = join(this.videosDir, name);
      const parsed = parseVideoFileName(name);
      let bytes: number | null;
      let mtime = 0;
      try {
        const s = await stat(path);
        if (!s.isFile()) continue;
        bytes = s.size;
        mtime = s.mtimeMs;
      } catch {
        continue; // sumiu no meio da varredura
      }
      if (parsed) videos.push({ path, ...parsed, bytes, mtime });
      else strays.push({ path, name, bytes, where: 'videos' });
    }

    videos.sort((a, b) => a.booknum - b.booknum || a.track - b.track || a.quality.localeCompare(b.quality));
    return { videos, strays };
  }

  private async scanCatalogs(): Promise<{ catalogs: CachedCatalog[]; strays: StrayFile[] }> {
    const catalogs: CachedCatalog[] = [];
    const strays: StrayFile[] = [];
    const now = Date.now();

    for (const name of await listDir(this.catalogDir)) {
      const path = join(this.catalogDir, name);
      const bytes = await sizeOf(path);
      if (bytes === null) continue;

      const booknum = parseCatalogFileName(name);
      if (booknum === null) {
        strays.push({ path, name, bytes, where: 'catalogs' });
        continue;
      }

      // um JSON corrompido ainda ocupa disco e precisa aparecer para ser apagado
      let detail: Partial<BookDetail> = {};
      try {
        detail = JSON.parse(await readFile(path, 'utf8'));
      } catch {
        /* segue com os campos vazios */
      }

      const fetchedAt = typeof detail.fetchedAt === 'number' ? detail.fetchedAt : 0;
      catalogs.push({
        path,
        booknum,
        name: detail.name ?? BOOKS[booknum - 1]?.name ?? `Livro ${booknum}`,
        chapters: detail.chapters?.length ?? 0,
        bytes,
        fetchedAt,
        stale: now - fetchedAt > CATALOG_TTL,
      });
    }

    catalogs.sort((a, b) => a.booknum - b.booknum);
    return { catalogs, strays };
  }

  async report(): Promise<CacheReport> {
    const [v, c] = await Promise.all([this.scanVideos(), this.scanCatalogs()]);
    return {
      videosDir: this.videosDir,
      catalogDir: this.catalogDir,
      videos: v.videos,
      catalogs: c.catalogs,
      strays: [...v.strays, ...c.strays],
      catalogTtl: CATALOG_TTL,
    };
  }

  /** Apaga os caminhos pedidos, ignorando qualquer um fora das pastas do app. */
  async remove(paths: string[]): Promise<CacheRemoval> {
    let removed = 0;
    let freed = 0;
    const errors: string[] = [];

    for (const path of paths) {
      if (!isInside(this.videosDir, path) && !isInside(this.catalogDir, path)) {
        errors.push(`Fora das pastas do app: ${path}`);
        continue;
      }
      const bytes = (await sizeOf(path)) ?? 0;
      try {
        await rm(path, { force: true });
        removed++;
        freed += bytes;
      } catch (err) {
        errors.push(err instanceof Error ? err.message : String(err));
      }
    }

    return { removed, freed, errors };
  }

  async clear(kind: CacheKind): Promise<CacheRemoval> {
    const r = await this.report();
    const paths = [
      ...(kind === 'catalogs' ? [] : r.videos.map((v) => v.path)),
      ...(kind === 'videos' ? [] : r.catalogs.map((c) => c.path)),
      ...r.strays.filter((s) => kind === 'all' || s.where === kind).map((s) => s.path),
    ];
    return this.remove(paths);
  }

  dirFor(kind: Exclude<CacheKind, 'all'>): string {
    return kind === 'videos' ? this.videosDir : this.catalogDir;
  }
}
