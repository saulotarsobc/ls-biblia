import { contextBridge, ipcRenderer } from 'electron';
import type { Book, BookDetail, ChapterFile, DownloadProgress, ExportProgress, ExportRequest } from '../shared/types';

type BookResult = { ok: true; data: BookDetail } | { ok: false; unavailable: boolean; error: string };

type DownloadResult = { ok: true; path: string; mediaUrl: string } | { ok: false; error: string };

type ExportResult = { ok: true; path: string } | { ok: false; error: string };

function subscribe<T>(channel: string, cb: (payload: T) => void): () => void {
  const listener = (_e: unknown, payload: T) => cb(payload);
  ipcRenderer.on(channel, listener);
  return () => ipcRenderer.off(channel, listener);
}

const api = {
  listBooks: (): Promise<Book[]> => ipcRenderer.invoke('books:list'),

  getBook: (booknum: number, force = false): Promise<BookResult> => ipcRenderer.invoke('book:get', booknum, force),

  download: (booknum: number, track: number, file: ChapterFile): Promise<DownloadResult> =>
    ipcRenderer.invoke('download:start', booknum, track, file),

  cancelDownload: (): Promise<void> => ipcRenderer.invoke('download:cancel'),

  onDownloadProgress: (cb: (p: DownloadProgress) => void) => subscribe<DownloadProgress>('download:progress', cb),

  pickExportPath: (suggested: string): Promise<string | null> => ipcRenderer.invoke('export:pick-path', suggested),

  exportVideo: (req: ExportRequest): Promise<ExportResult> => ipcRenderer.invoke('export:start', req),

  cancelExport: (): Promise<void> => ipcRenderer.invoke('export:cancel'),

  onExportProgress: (cb: (p: ExportProgress) => void) => subscribe<ExportProgress>('export:progress', cb),

  reveal: (path: string): Promise<void> => ipcRenderer.invoke('shell:reveal', path),
};

contextBridge.exposeInMainWorld('api', api);

export type Api = typeof api;
