import { formatBytes, formatTime } from '../../shared/timeline.ts';
import { QUALITIES } from '../../shared/types';
import { useStore } from '../store.ts';

export function QualityStep() {
  const { chapter, quality, setQuality, startDownload, downloading, downloadProgress, downloadError, cancelDownload } =
    useStore();

  if (!chapter) return null;

  if (downloading) {
    const p = downloadProgress;
    return (
      <div className="center">
        <div className="stack" style={{ width: 420 }}>
          <h2>Baixando…</h2>
          <div className="bar" style={{ width: '100%' }}>
            <i style={{ width: `${p?.percent ?? 0}%` }} />
          </div>
          <div className="time">
            {p
              ? `${formatBytes(p.receivedBytes)} de ${formatBytes(p.totalBytes)} · ${p.percent.toFixed(0)}%`
              : 'Iniciando…'}
          </div>
          <button className="danger" onClick={cancelDownload}>
            Cancelar
          </button>
        </div>
      </div>
    );
  }

  return (
    <>
      <h2>Qualidade do vídeo</h2>
      <p className="sub">O arquivo do capítulo inteiro é baixado uma vez; o corte acontece na exportação.</p>

      <div className="qualities">
        {QUALITIES.map((q) => {
          const f = chapter.files[q];
          return (
            <button key={q} className={`q${quality === q ? ' on' : ''}`} disabled={!f} onClick={() => setQuality(q)}>
              <div className="big">{q}</div>
              <div className="dim">{f ? `${f.width}×${f.height}` : 'indisponível'}</div>
              <div className="sz">{f ? formatBytes(f.filesize) : '—'}</div>
            </button>
          );
        })}
      </div>

      {downloadError && (
        <div className="notice err" style={{ marginTop: 18 }}>
          {downloadError}
        </div>
      )}

      <div className="toolbar" style={{ marginTop: 22 }}>
        <button className="primary" onClick={startDownload} disabled={!chapter.files[quality]}>
          Baixar e abrir o editor
        </button>
        <span className="time">Capítulo de {formatTime(chapter.duration)}</span>
      </div>
    </>
  );
}
