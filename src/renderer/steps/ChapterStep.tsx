import { formatTime } from '../../shared/timeline.ts';
import { useStore } from '../store.ts';

export function ChapterStep() {
  const { book, detail, loadingBook, bookError, chooseChapter, go } = useStore();

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
          <button onClick={() => go('book')}>Escolher outro livro</button>
        </div>
      </div>
    );
  }

  if (!detail) return null;

  return (
    <>
      <h2>{detail.name}</h2>
      <p className="sub">{detail.chapters.length} capítulos disponíveis. Escolha um para continuar.</p>
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
