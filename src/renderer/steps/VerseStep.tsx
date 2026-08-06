import { useState } from 'react';
import { editDuration, formatTime, versesToRanges } from '../../shared/timeline.ts';
import { useStore } from '../store.ts';

export function VerseStep() {
  const { chapter, detail, selectedVerses, toggleVerse, setAllVerses, selectVerseRange, go } = useStore();
  const [anchor, setAnchor] = useState<number | null>(null);

  if (!chapter) return null;

  const verses = chapter.verses;
  const picked = verses.filter((v) => selectedVerses.includes(v.verseNumber));
  const ranges = versesToRanges(picked);
  const total = selectedVerses.length ? editDuration(ranges) : chapter.duration;

  const click = (n: number, shift: boolean) => {
    if (shift && anchor !== null) selectVerseRange(anchor, n);
    else {
      toggleVerse(n);
      setAnchor(n);
    }
  };

  return (
    <>
      <h2>
        {detail?.name} {chapter.track}
      </h2>
      <p className="sub">
        {verses.length > 0
          ? 'Escolha os versículos. Clique para marcar, Shift+clique para selecionar um intervalo. Sem nenhum marcado, entra o capítulo inteiro.'
          : 'Este capítulo não traz marcadores de versículo, então entra inteiro.'}
      </p>

      {verses.length > 0 && (
        <div className="toolbar">
          <button onClick={() => setAllVerses(true)}>Todos</button>
          <button onClick={() => setAllVerses(false)}>Limpar</button>
          <div className="spacer" />
          <span className="time">
            {selectedVerses.length === 0 ? (
              <>
                Capítulo inteiro · <b>{formatTime(chapter.duration)}</b>
              </>
            ) : (
              <>
                {selectedVerses.length} versículo
                {selectedVerses.length > 1 ? 's' : ''} · {ranges.length} trecho
                {ranges.length > 1 ? 's' : ''} · <b>{formatTime(total)}</b>
              </>
            )}
          </span>
        </div>
      )}

      {verses.length > 0 && (
        <div className="verses">
          {verses.map((v) => (
            <div
              key={v.verseNumber}
              className={`verse${selectedVerses.includes(v.verseNumber) ? ' on' : ''}`}
              onClick={(e) => click(v.verseNumber, e.shiftKey)}
            >
              <input type="checkbox" readOnly checked={selectedVerses.includes(v.verseNumber)} />
              <span>{v.verseNumber}</span>
              <span className="dur">{formatTime(v.end - v.start)}</span>
            </div>
          ))}
        </div>
      )}

      <div className="toolbar" style={{ marginTop: 20 }}>
        <button className="primary" onClick={() => go('quality')}>
          Continuar
        </button>
      </div>
    </>
  );
}
