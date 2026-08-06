import { create } from 'zustand';
import { versesToRanges } from '../shared/timeline.ts';
import type {
  Book,
  BookDetail,
  Chapter,
  DownloadProgress,
  EditState,
  ExportProgress,
  Quality,
  SpeedRegion,
  Verse,
  ZoomRegion,
} from '../shared/types';

export type Step = 'book' | 'chapter' | 'verses' | 'quality' | 'editor';

let idSeq = 0;
const nextId = (prefix: string) => `${prefix}${++idSeq}`;

interface State {
  step: Step;
  book: Book | null;
  detail: BookDetail | null;
  loadingBook: boolean;
  bookError: string | null;

  chapter: Chapter | null;
  /** vazio = capitulo inteiro, sem cortes */
  selectedVerses: number[];
  quality: Quality;

  downloading: boolean;
  downloadProgress: DownloadProgress | null;
  downloadError: string | null;
  mediaUrl: string | null;
  sourcePath: string | null;

  edit: EditState;

  exporting: boolean;
  exportProgress: ExportProgress | null;
  exportError: string | null;
  exportedPath: string | null;

  go: (step: Step) => void;
  chooseBook: (book: Book) => Promise<void>;
  chooseChapter: (chapter: Chapter) => void;
  toggleVerse: (n: number) => void;
  selectVerseRange: (from: number, to: number) => void;
  setAllVerses: (all: boolean) => void;
  setQuality: (q: Quality) => void;
  startDownload: () => Promise<void>;
  cancelDownload: () => void;

  addSpeedRegion: (r: Omit<SpeedRegion, 'id'>) => void;
  updateSpeedRegion: (id: string, patch: Partial<SpeedRegion>) => void;
  removeSpeedRegion: (id: string) => void;
  /** devolve o id para quem criou poder editar o ponto em seguida */
  addZoomRegion: (k: Omit<ZoomRegion, 'id'>) => string;
  updateZoomRegion: (id: string, patch: Partial<ZoomRegion>) => void;
  removeZoomRegion: (id: string) => void;
  setSmoothSlowMotion: (v: boolean) => void;

  startExport: () => Promise<void>;
  cancelExport: () => void;
  reset: () => void;
}

const emptyEdit: EditState = {
  ranges: [],
  speedRegions: [],
  zoomRegions: [],
  smoothSlowMotion: false,
};

/** Versiculos escolhidos -> trechos da origem. Vazio = capitulo inteiro. */
function rangesFor(chapter: Chapter | null, selected: number[]) {
  if (!chapter) return [];
  if (selected.length === 0 || chapter.verses.length === 0) {
    return [{ start: 0, end: chapter.duration }];
  }
  const picked: Verse[] = chapter.verses.filter((v) => selected.includes(v.verseNumber));
  return versesToRanges(picked);
}

export const useStore = create<State>((set, get) => ({
  step: 'book',
  book: null,
  detail: null,
  loadingBook: false,
  bookError: null,
  chapter: null,
  selectedVerses: [],
  quality: '480p',
  downloading: false,
  downloadProgress: null,
  downloadError: null,
  mediaUrl: null,
  sourcePath: null,
  edit: emptyEdit,
  exporting: false,
  exportProgress: null,
  exportError: null,
  exportedPath: null,

  go: (step) => set({ step }),

  chooseBook: async (book) => {
    set({
      book,
      loadingBook: true,
      bookError: null,
      detail: null,
      chapter: null,
      step: 'chapter',
    });
    const res = await window.api.getBook(book.booknum);
    if (res.ok) set({ detail: res.data, loadingBook: false });
    else set({ bookError: res.error, loadingBook: false });
  },

  chooseChapter: (chapter) => set({ chapter, selectedVerses: [], step: 'verses' }),

  toggleVerse: (n) =>
    set((s) => ({
      selectedVerses: s.selectedVerses.includes(n)
        ? s.selectedVerses.filter((v) => v !== n)
        : [...s.selectedVerses, n].sort((a, b) => a - b),
    })),

  selectVerseRange: (from, to) =>
    set((s) => {
      const [lo, hi] = from <= to ? [from, to] : [to, from];
      const add = (s.chapter?.verses ?? []).map((v) => v.verseNumber).filter((n) => n >= lo && n <= hi);
      return {
        selectedVerses: [...new Set([...s.selectedVerses, ...add])].sort((a, b) => a - b),
      };
    }),

  setAllVerses: (all) =>
    set((s) => ({
      selectedVerses: all ? (s.chapter?.verses ?? []).map((v) => v.verseNumber) : [],
    })),

  setQuality: (quality) => set({ quality }),

  startDownload: async () => {
    const { chapter, quality, book, selectedVerses } = get();
    if (!chapter || !book) return;
    const file = chapter.files[quality];
    if (!file) {
      set({ downloadError: `Qualidade ${quality} indisponível neste capítulo.` });
      return;
    }

    set({ downloading: true, downloadError: null, downloadProgress: null });
    const res = await window.api.download(book.booknum, chapter.track, file);

    if (!res.ok) {
      set({ downloading: false, downloadError: res.error });
      return;
    }
    set({
      downloading: false,
      mediaUrl: res.mediaUrl,
      sourcePath: res.path,
      step: 'editor',
      edit: { ...emptyEdit, ranges: rangesFor(chapter, selectedVerses) },
      exportedPath: null,
      exportError: null,
    });
  },

  cancelDownload: () => {
    window.api.cancelDownload();
    set({ downloading: false });
  },

  addSpeedRegion: (r) =>
    set((s) => ({
      edit: {
        ...s.edit,
        speedRegions: [...s.edit.speedRegions, { ...r, id: nextId('s') }].sort((a, b) => a.start - b.start),
      },
    })),

  updateSpeedRegion: (id, patch) =>
    set((s) => ({
      edit: {
        ...s.edit,
        speedRegions: s.edit.speedRegions
          .map((r) => (r.id === id ? { ...r, ...patch } : r))
          .sort((a, b) => a.start - b.start),
      },
    })),

  removeSpeedRegion: (id) =>
    set((s) => ({
      edit: {
        ...s.edit,
        speedRegions: s.edit.speedRegions.filter((r) => r.id !== id),
      },
    })),

  addZoomRegion: (k) => {
    const id = nextId('k');
    set((s) => ({
      edit: {
        ...s.edit,
        zoomRegions: [...s.edit.zoomRegions, { ...k, id }].sort((a, b) => a.start - b.start),
      },
    }));
    return id;
  },

  updateZoomRegion: (id, patch) =>
    set((s) => ({
      edit: {
        ...s.edit,
        zoomRegions: s.edit.zoomRegions
          .map((k) => (k.id === id ? { ...k, ...patch } : k))
          .sort((a, b) => a.start - b.start),
      },
    })),

  removeZoomRegion: (id) =>
    set((s) => ({
      edit: {
        ...s.edit,
        zoomRegions: s.edit.zoomRegions.filter((k) => k.id !== id),
      },
    })),

  setSmoothSlowMotion: (v) => set((s) => ({ edit: { ...s.edit, smoothSlowMotion: v } })),

  startExport: async () => {
    const { sourcePath, chapter, quality, book, edit, selectedVerses } = get();
    if (!sourcePath || !chapter || !book) return;
    const file = chapter.files[quality];
    if (!file) return;

    const versePart =
      selectedVerses.length > 0 ? `_v${selectedVerses[0]}-${selectedVerses[selectedVerses.length - 1]}` : '';
    const suggested = `${book.name.replace(/\s+/g, '-')}-${chapter.track}${versePart}.mp4`;

    const outputPath = await window.api.pickExportPath(suggested);
    if (!outputPath) return;

    set({ exporting: true, exportError: null, exportProgress: null, exportedPath: null });
    const res = await window.api.exportVideo({
      sourcePath,
      outputPath,
      edit,
      width: file.width,
      height: file.height,
      frameRate: file.frameRate,
      crf: 20,
    });
    if (res.ok) set({ exporting: false, exportedPath: res.path });
    else set({ exporting: false, exportError: res.error });
  },

  cancelExport: () => {
    window.api.cancelExport();
    set({ exporting: false });
  },

  reset: () =>
    set({
      step: 'book',
      book: null,
      detail: null,
      chapter: null,
      selectedVerses: [],
      mediaUrl: null,
      sourcePath: null,
      edit: emptyEdit,
      exportedPath: null,
      exportError: null,
      downloadError: null,
    }),
}));
