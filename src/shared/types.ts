/** Qualidades expostas pela API GETPUBMEDIALINKS para pub=nwt. */
export type Quality = '240p' | '360p' | '480p' | '720p';

export const QUALITIES: Quality[] = ['240p', '360p', '480p', '720p'];

/** Marcadores de versiculo so acompanham o arquivo 720p, mas as duracoes sao
 *  identicas entre as qualidades, entao os tempos valem para qualquer arquivo. */
export const MARKER_QUALITY: Quality = '720p';

export interface Book {
  booknum: number;
  name: string;
  testament: 'hebraicas' | 'gregas';
}

export interface Verse {
  verseNumber: number;
  /** segundos, relativo ao inicio do arquivo do capitulo */
  start: number;
  /** segundos */
  end: number;
  label: string;
}

export interface ChapterFile {
  quality: Quality;
  url: string;
  filesize: number;
  width: number;
  height: number;
  frameRate: number;
  duration: number;
  checksum: string;
}

export interface Chapter {
  track: number;
  title: string;
  duration: number;
  files: Record<Quality, ChapterFile | undefined>;
  /** vazio quando o capitulo nao traz marcadores */
  verses: Verse[];
}

export interface BookDetail {
  booknum: number;
  name: string;
  chapters: Chapter[];
  fetchedAt: number;
}

/** Trecho continuo do arquivo de origem, em segundos. */
export interface SourceRange {
  start: number;
  end: number;
}

/** Regiao de camera lenta, em tempo de EDICAO (pos-corte, pre-velocidade). */
export interface SpeedRegion {
  id: string;
  start: number;
  end: number;
  /** 1 = normal, 0.5 = metade da velocidade, 0.25 = um quarto */
  speed: number;
}

/**
 * Faixa de zoom, em tempo de EDICAO.
 *
 * Fora da faixa o enquadramento e sempre o cheio (1x). Dentro dela a imagem
 * entra e sai do zoom durante `ramp` segundos em cada ponta — a mesma ideia das
 * rampas de velocidade de um editor de video. Keyframes soltos nao serviam: o
 * primeiro ponto valia para todo o video antes dele, e a cena ja comecava
 * ampliada.
 */
export interface ZoomRegion {
  id: string;
  start: number;
  end: number;
  /** 1 = enquadramento cheio, 2 = 2x de aproximacao */
  zoom: number;
  /** centro do enquadramento, normalizado 0..1 */
  cx: number;
  cy: number;
  /** segundos de transicao em cada ponta */
  ramp: number;
}

export interface EditState {
  ranges: SourceRange[];
  speedRegions: SpeedRegion[];
  zoomRegions: ZoomRegion[];
  /** interpola frames na camera lenta (bem mais lento para processar) */
  smoothSlowMotion: boolean;
}

/** Unidade atomica de render: trecho continuo da origem com uma unica velocidade. */
export interface Atom {
  srcStart: number;
  srcEnd: number;
  speed: number;
  /** tempo de edicao em que este atomo comeca */
  editStart: number;
}

export interface DownloadProgress {
  receivedBytes: number;
  totalBytes: number;
  percent: number;
}

export interface ExportProgress {
  /** 0..1 */
  percent: number;
  outTimeSeconds: number;
  speed: string;
  fps: number;
}

export interface ExportRequest {
  sourcePath: string;
  outputPath: string;
  edit: EditState;
  width: number;
  height: number;
  frameRate: number;
  crf: number;
}

export interface DownloadedFile {
  path: string;
  booknum: number;
  track: number;
  quality: Quality;
  bytes: number;
}

export type UpdateStatus =
  | { state: 'available'; version: string }
  | { state: 'downloading'; percent: number }
  | { state: 'downloaded'; version: string }
  | { state: 'error'; message: string };
