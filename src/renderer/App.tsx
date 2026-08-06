import { useEffect } from 'react';
import { Editor } from './editor/Editor.tsx';
import { BookStep } from './steps/BookStep.tsx';
import { ChapterStep } from './steps/ChapterStep.tsx';
import { QualityStep } from './steps/QualityStep.tsx';
import { VerseStep } from './steps/VerseStep.tsx';
import { type Step, useStore } from './store.ts';

const ORDER: { key: Step; label: string }[] = [
  { key: 'book', label: 'Livro' },
  { key: 'chapter', label: 'Capítulo' },
  { key: 'verses', label: 'Versículos' },
  { key: 'quality', label: 'Qualidade' },
  { key: 'editor', label: 'Editor' },
];

export function App() {
  const { step, go, book, chapter, detail, reset } = useStore();
  const current = ORDER.findIndex((s) => s.key === step);

  // eventos de progresso vindos do processo main
  useEffect(() => {
    const offDl = window.api.onDownloadProgress((p) => useStore.setState({ downloadProgress: p }));
    const offEx = window.api.onExportProgress((p) => useStore.setState({ exportProgress: p }));
    return () => {
      offDl();
      offEx();
    };
  }, []);

  const label = (s: Step) => {
    if (s === 'book' && book) return book.name;
    if (s === 'chapter' && chapter) return `Cap. ${chapter.track}`;
    return ORDER.find((o) => o.key === s)!.label;
  };

  const reachable = (i: number) => {
    if (i > current) return false;
    if (ORDER[i].key === 'chapter' && !detail) return false;
    return true;
  };

  return (
    <div className="app">
      <nav className="steps">
        {ORDER.map((s, i) => (
          <span key={s.key} style={{ display: 'contents' }}>
            {i > 0 && <span className="sep">›</span>}
            <button
              className={`crumb ${i === current ? 'active' : reachable(i) ? 'done' : ''}`}
              disabled={!reachable(i) || i === current}
              onClick={() => go(s.key)}
            >
              <span className="n">{i + 1}</span>
              {label(s.key)}
            </button>
          </span>
        ))}
        <div className="spacer" />
        <button className="ghost" onClick={reset}>
          Recomeçar
        </button>
      </nav>

      <main className={`body${step === 'editor' ? ' no-pad' : ''}`}>
        {step === 'book' && <BookStep />}
        {step === 'chapter' && <ChapterStep />}
        {step === 'verses' && <VerseStep />}
        {step === 'quality' && <QualityStep />}
        {step === 'editor' && <Editor />}
      </main>
    </div>
  );
}
