import { useMemo, useState } from 'react';
import { searchBooks } from '../../shared/books.ts';
import type { Book } from '../../shared/types';
import { useStore } from '../store.ts';

export function BookStep() {
  const chooseBook = useStore((s) => s.chooseBook);
  const [query, setQuery] = useState('');

  const { hebraicas, gregas } = useMemo(() => {
    const found = searchBooks(query);
    return {
      hebraicas: found.filter((b) => b.testament === 'hebraicas'),
      gregas: found.filter((b) => b.testament === 'gregas'),
    };
  }, [query]);

  const card = (b: Book) => (
    <button key={b.booknum} className="card" onClick={() => chooseBook(b)}>
      <div className="idx">{b.booknum}</div>
      <div className="nm">{b.name}</div>
    </button>
  );

  return (
    <>
      <h2>Escolha o livro</h2>
      <p className="sub">Bíblia em Língua de Sinais Brasileira (LSB)</p>

      <div className="toolbar">
        <input
          type="search"
          placeholder="Buscar livro (ex.: joao, salmos, 19)…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={{ width: 300 }}
          autoFocus
        />
      </div>

      {hebraicas.length === 0 && gregas.length === 0 && (
        <div className="notice">Nenhum livro encontrado para “{query}”.</div>
      )}

      {hebraicas.length > 0 && (
        <>
          <div className="group-label">Escrituras Hebraicas</div>
          <div className="grid-books">{hebraicas.map(card)}</div>
        </>
      )}
      {gregas.length > 0 && (
        <>
          <div className="group-label">Escrituras Gregas Cristãs</div>
          <div className="grid-books">{gregas.map(card)}</div>
        </>
      )}
    </>
  );
}
