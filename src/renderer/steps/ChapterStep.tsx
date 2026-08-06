import { formatTime } from '../../shared/timeline.ts';
import { useStore } from '../store.ts';

export function ChapterStep() {
  const { book, detail, loadingBook, bookError, chooseChapter, refreshBook, go } = useStore();

  if (loadingBook) {
    return (
      <div className="center">
        <div className="stack">
          <div className="notice">Consultando o catálogo de {book?.name}…</div>
        </div>
      </div>
    );
  }

  if (bookError) {
    return (
      <div className="center">
        <div className="stack">
          <div className="notice err">{bookError}</div>
          <div className="toolbar" style={{ margin: 0 }}>
            <button onClick={() => go('book')}>Escolher outro livro</button>
            <button className="ghost" onClick={refreshBook}>
              Tentar de novo
            </button>
          </div>
        </div>
      </div>
    );
  }

  if (!detail) return null;

  return (
    <>
      <h2>{detail.name}</h2>
      <p className="sub">
        {detail.chapters.length} {detail.chapters.length === 1 ? 'capítulo disponível' : 'capítulos disponíveis'}. Nem
        todo livro saiu inteiro em LSB.
      </p>

      <div className="toolbar">
        <button className="ghost" onClick={refreshBook} title="Consulta a API de novo, ignorando o cache local">
          Atualizar catálogo
        </button>
        <span className="time">Consultado em {new Date(detail.fetchedAt).toLocaleDateString('pt-BR')}</span>
      </div>

      <div className="grid-nums">
        {detail.chapters.map((c) => (
          <button
            key={c.track}
            className="card num"
            onClick={() => chooseChapter(c)}
            title={`${c.title} — ${formatTime(c.duration)}${
              c.verses.length ? ` — ${c.verses.length} versículos marcados` : ''
            }`}
          >
            {c.track}
          </button>
        ))}
      </div>
    </>
  );
}
