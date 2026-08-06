import { BrowserWindow, app, dialog, ipcMain, protocol, shell } from 'electron';
import { autoUpdater } from 'electron-updater';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { join, normalize, sep } from 'node:path';
import { Readable } from 'node:stream';
import { BOOKS } from '../shared/books.ts';
import type { ChapterFile, DownloadProgress, ExportProgress, ExportRequest, UpdateStatus } from '../shared/types';
import { downloadChapter, type DownloadHandle } from './download.ts';
import { buildExport } from './export.ts';
import { runFfmpeg, type RunHandle } from './ffmpeg.ts';
import { BookUnavailableError, JwClient } from './jw.ts';

const SCHEME = 'media';

// `stream: true` habilita requisicoes Range, sem as quais o <video> nao consegue
// buscar posicao no arquivo local — essencial para arrastar a agulha na timeline.
protocol.registerSchemesAsPrivileged([
  {
    scheme: SCHEME,
    privileges: { standard: true, secure: true, stream: true, supportFetchAPI: true },
  },
]);

let win: BrowserWindow | null = null;
let videosDir = '';
let client: JwClient;
let activeDownload: DownloadHandle | null = null;
let activeExport: RunHandle | null = null;

function send(channel: string, payload: unknown) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload);
}

/**
 * Caminho do disco -> URL do protocolo interno, com host fixo e o caminho inteiro
 * como um unico segmento percent-encoded. Assim a letra do drive e as barras nao
 * passam pela canonicalizacao de URL do Chromium.
 */
function toMediaUrl(filePath: string): string {
  return `${SCHEME}://local/${encodeURIComponent(filePath.replace(/\\/g, '/'))}`;
}

/**
 * Serve o arquivo honrando o header Range.
 *
 * Isto e o que permite ARRASTAR a agulha no editor. O `net.fetch` em file://
 * ignora o Range e devolve 200 com o arquivo inteiro; sem uma resposta 206 o
 * Chromium nao consegue buscar posicao, `currentTime` fica travado em 0 e o
 * preview mostra para sempre o primeiro frame (que nestes videos e preto).
 */
async function serveWithRange(filePath: string, rangeHeader: string | null): Promise<Response> {
  const { size } = await stat(filePath);
  const base = { 'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes' };

  const match = rangeHeader ? /bytes=(\d*)-(\d*)/.exec(rangeHeader) : null;
  if (match) {
    const start = match[1] ? Number(match[1]) : 0;
    const end = Math.min(match[2] ? Number(match[2]) : size - 1, size - 1);
    if (!Number.isFinite(start) || start >= size || start > end) {
      return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${size}` } });
    }
    return new Response(Readable.toWeb(createReadStream(filePath, { start, end })) as ReadableStream, {
      status: 206,
      headers: {
        ...base,
        'Content-Range': `bytes ${start}-${end}/${size}`,
        'Content-Length': String(end - start + 1),
      },
    });
  }

  return new Response(Readable.toWeb(createReadStream(filePath)) as ReadableStream, {
    status: 200,
    headers: { ...base, 'Content-Length': String(size) },
  });
}

/** So serve arquivos de dentro da pasta de videos do app. */
function isInsideVideosDir(candidate: string): boolean {
  const base = normalize(videosDir).toLowerCase();
  const target = normalize(candidate).toLowerCase();
  return target === base || target.startsWith(base.endsWith(sep) ? base : base + sep);
}

function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 860,
    minWidth: 940,
    minHeight: 640,
    backgroundColor: '#0f1115',
    show: false,
    autoHideMenuBar: true,
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  win.once('ready-to-show', () => win?.show());

  if (process.env.ELECTRON_RENDERER_URL) {
    win.loadURL(process.env.ELECTRON_RENDERER_URL);
  } else {
    win.loadFile(join(__dirname, '../renderer/index.html'));
  }
}

/**
 * Checa releases publicados no GitHub (config `build.publish` no package.json).
 * So faz sentido empacotado: em dev nao existe `app-update.yml` e o electron-updater
 * lançaria erro so por tentar ler esse arquivo.
 */
function setupAutoUpdater() {
  if (!app.isPackaged) return;

  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;

  autoUpdater.on('update-available', (info) =>
    send('update:status', { state: 'available', version: info.version } satisfies UpdateStatus),
  );
  autoUpdater.on('download-progress', (p) =>
    send('update:status', { state: 'downloading', percent: p.percent } satisfies UpdateStatus),
  );
  autoUpdater.on('update-downloaded', (info) =>
    send('update:status', { state: 'downloaded', version: info.version } satisfies UpdateStatus),
  );
  autoUpdater.on('error', (err) =>
    send('update:status', { state: 'error', message: err.message } satisfies UpdateStatus),
  );

  autoUpdater.checkForUpdates().catch((err) => console.error('checkForUpdates falhou:', err));
}

app.whenReady().then(() => {
  videosDir = join(app.getPath('userData'), 'videos');
  client = new JwClient(join(app.getPath('userData'), 'catalogo'));

  protocol.handle(SCHEME, (request) => {
    // O caminho vem como UM segmento percent-encoded depois do host fixo. Deixar
    // o caminho cru na URL (media:///C:/...) nao funciona: sendo um esquema
    // `standard`, o Chromium canonicaliza o "C:" e o handler recebe um caminho
    // que nao resolve — o video falha com MEDIA_ERR_SRC_NOT_SUPPORTED.
    const filePath = decodeURIComponent(new URL(request.url).pathname.replace(/^\//, ''));
    if (!isInsideVideosDir(filePath)) {
      return new Response('Acesso negado', { status: 403 });
    }
    return serveWithRange(filePath, request.headers.get('range'));
  });

  createWindow();
  setupAutoUpdater();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  activeDownload?.cancel();
  activeExport?.cancel();
  if (process.platform !== 'darwin') app.quit();
});

// ---------------------------------------------------------------- catalogo

ipcMain.handle('books:list', () => BOOKS);

ipcMain.handle('book:get', async (_e, booknum: number, force = false) => {
  try {
    return { ok: true as const, data: await client.getBook(booknum, force) };
  } catch (err) {
    const unavailable = err instanceof BookUnavailableError;
    return {
      ok: false as const,
      unavailable,
      error: unavailable
        ? 'Este livro ainda não foi publicado em LSB.'
        : err instanceof Error
          ? err.message
          : 'Erro desconhecido ao consultar o catálogo.',
    };
  }
});

// ---------------------------------------------------------------- download

ipcMain.handle('download:start', async (_e, booknum: number, track: number, file: ChapterFile) => {
  activeDownload?.cancel();
  const handle = downloadChapter(videosDir, booknum, track, file, (p: DownloadProgress) =>
    send('download:progress', p),
  );
  activeDownload = handle;
  try {
    const path = await handle.promise;
    return { ok: true as const, path, mediaUrl: toMediaUrl(path) };
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    return {
      ok: false as const,
      error: msg.includes('abort') ? 'Download cancelado.' : msg,
    };
  } finally {
    if (activeDownload === handle) activeDownload = null;
  }
});

ipcMain.handle('download:cancel', () => {
  activeDownload?.cancel();
  activeDownload = null;
});

// ---------------------------------------------------------------- exportacao

ipcMain.handle('export:pick-path', async (_e, suggested: string) => {
  const res = await dialog.showSaveDialog(win!, {
    title: 'Salvar vídeo',
    defaultPath: suggested,
    filters: [{ name: 'Vídeo MP4', extensions: ['mp4'] }],
  });
  return res.canceled ? null : res.filePath;
});

ipcMain.handle('export:start', async (_e, req: ExportRequest) => {
  activeExport?.cancel();
  try {
    const built = buildExport(req);
    const handle = runFfmpeg(built.args, (p) => {
      const progress: ExportProgress = {
        percent: built.expectedDuration ? Math.min(1, p.outTimeSeconds / built.expectedDuration) : 0,
        outTimeSeconds: p.outTimeSeconds,
        speed: p.speed,
        fps: p.fps,
      };
      send('export:progress', progress);
    });
    activeExport = handle;
    await handle.promise;
    return { ok: true as const, path: req.outputPath };
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    return {
      ok: false as const,
      error: msg === 'cancelado' ? 'Exportação cancelada.' : msg,
    };
  } finally {
    activeExport = null;
  }
});

ipcMain.handle('export:cancel', () => {
  activeExport?.cancel();
  activeExport = null;
});

ipcMain.handle('shell:reveal', (_e, path: string) => shell.showItemInFolder(path));

// ---------------------------------------------------------------- auto-update

ipcMain.handle('update:install', () => autoUpdater.quitAndInstall());
