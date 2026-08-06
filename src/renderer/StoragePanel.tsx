import { useCallback, useEffect, useMemo, useState } from 'react';
import { BOOKS } from '../shared/books.ts';
import { formatBytes } from '../shared/timeline.ts';
import type { CacheKind, CacheReport, CachedVideo } from '../shared/types';
import { useStore } from './store.ts';

const bookName = (booknum: number) => BOOKS[booknum - 1]?.name ?? `Livro ${booknum}`;

const sum = (list: { bytes: number }[]) => list.reduce((t, x) => t + x.bytes, 0);

function formatDate(ms: number): string {
  if (!ms) return 'data desconhecida';
  return new Date(ms).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' });
}

/**
 * Gerenciador do que o app guarda em disco: os capitulos baixados e as respostas
 * da API. Nada aqui e insubstituivel — apagar so custa uma nova consulta ou um
 * novo download — entao a unica trava e o arquivo aberto no editor.
 */
export function StoragePanel({ onClose }: { onClose: () => void }) {
  const [report, setReport] = useState<CacheReport | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [armed, setArmed] = useState<CacheKind | null>(null);

  const { book, sourcePath, refreshBook } = useStore();

  const load = useCallback(async () => {
    setReport(await window.api.cacheReport());
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // Esc fecha, como em qualquer dialogo
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  /** Roda uma acao destrutiva, recarrega o relatorio e resume o resultado. */
  const run = useCallback(
    async (paths: string[], action: () => Promise<{ removed: number; freed: number; errors: string[] }>) => {
      if (busy) return;
      setBusy(true);
      setArmed(null);
      try {
        const res = await action();
        setMsg(
          res.errors.length
            ? `${res.removed} removido(s), ${res.errors.length} falha(s): ${res.errors[0]}`
            : res.removed === 0
              ? 'Nada a remover.'
              : `${res.removed} arquivo(s) removido(s) · ${formatBytes(res.freed)} liberado(s).`,
        );
        // o catalogo do livro aberto sumiu: busca de novo para a tela nao ficar
        // mostrando uma lista de capitulos que ja nao tem lastro em disco
        const catalogoAtual = report?.catalogs.find((c) => c.booknum === book?.booknum);
        if (book && catalogoAtual && paths.includes(catalogoAtual.path)) await refreshBook();
        await load();
      } finally {
        setBusy(false);
      }
    },
    [busy, load, report, book, refreshBook],
  );

  const remove = (paths: string[]) => run(paths, () => window.api.removeFromCache(paths));

  const clear = (kind: CacheKind) => {
    const paths = [
      ...(kind === 'catalogs' ? [] : (report?.videos ?? []).map((v) => v.path)),
      ...(kind === 'videos' ? [] : (report?.catalogs ?? []).map((c) => c.path)),
      ...(report?.strays ?? []).filter((s) => kind === 'all' || s.where === kind).map((s) => s.path),
    ];
    return run(paths, () => window.api.clearCache(kind));
  };

  const porLivro = useMemo(() => {
    const map = new Map<number, CachedVideo[]>();
    for (const v of report?.videos ?? []) {
      if (!map.has(v.booknum)) map.set(v.booknum, []);
      map.get(v.booknum)!.push(v);
    }
    return [...map.entries()].sort(([a], [b]) => a - b);
  }, [report]);

  const totalVideos = sum(report?.videos ?? []);
  const totalCatalogos = sum(report?.catalogs ?? []);
  const totalStrays = sum(report?.strays ?? []);
  const total = totalVideos + totalCatalogos + totalStrays;

  /** Botao de confirmar em dois toques: apagar em massa nao pode ser um clique so. */
  const clearButton = (kind: CacheKind, label: string, bytes: number, count: number) =>
    armed === kind ? (
      <button className="danger on" disabled={busy} onClick={() => clear(kind)}>
        Confirmar ({formatBytes(bytes)})
      </button>
    ) : (
      <button className="danger" disabled={busy || count === 0} onClick={() => setArmed(kind)}>
        {label}
      </button>
    );

  return (
    <div className="overlay" onMouseDown={onClose}>
      <div className="modal" onMouseDown={(e) => e.stopPropagation()}>
        <header className="modal-head">
          <div>
            <h2>Armazenamento</h2>
            <p className="sub" style={{ margin: 0 }}>
              {report ? `${formatBytes(total)} em disco` : 'Lendo as pastas…'}
            </p>
          </div>
          <div className="spacer" />
          <button className="ghost" onClick={onClose}>
            Fechar
          </button>
        </header>

        <div className="modal-body">
          {msg && <div className="notice ok cache-msg">{msg}</div>}

          {/* ------------------------------------------------------- videos */}
          <div className="cache-section">
            <div className="cache-section-head">
              <span className="group-label" style={{ margin: 0 }}>
                Capítulos baixados
              </span>
              <span className="time">
                {report?.videos.length ?? 0} arquivo(s) · {formatBytes(totalVideos)}
              </span>
              <div className="spacer" />
              <button className="ghost" onClick={() => window.api.openCacheDir('videos')}>
                Abrir pasta
              </button>
              {clearButton('videos', 'Limpar vídeos', totalVideos + totalStrays, report?.videos.length ?? 0)}
            </div>

            {porLivro.length === 0 && <div className="cache-empty">Nenhum vídeo baixado ainda.</div>}

            {porLivro.map(([booknum, files]) => {
              const removiveis = files.filter((f) => f.path !== sourcePath);
              return (
                <div className="cache-group" key={booknum}>
                  <div className="cache-group-head">
                    <b>{bookName(booknum)}</b>
                    <span className="time">
                      {files.length} arquivo(s) · {formatBytes(sum(files))}
                    </span>
                    <div className="spacer" />
                    <button
                      className="linkish"
                      disabled={busy || removiveis.length === 0}
                      onClick={() => remove(removiveis.map((f) => f.path))}
                    >
                      remover livro
                    </button>
                  </div>
                  {files.map((f) => {
                    const emUso = f.path === sourcePath;
                    return (
                      <div className="cache-row" key={f.path}>
                        <span className="cache-name">
                          Cap. {f.track} · {f.quality}
                        </span>
                        {f.partial && <span className="tag warn">incompleto</span>}
                        {emUso && <span className="tag">em uso</span>}
                        <div className="spacer" />
                        <span className="time">{formatBytes(f.bytes)}</span>
                        <span className="time dim-date">{formatDate(f.mtime)}</span>
                        <button
                          className="linkish"
                          disabled={busy || emUso}
                          title={emUso ? 'Aberto no editor' : 'Remover'}
                          onClick={() => remove([f.path])}
                        >
                          remover
                        </button>
                      </div>
                    );
                  })}
                </div>
              );
            })}
          </div>

          {/* ----------------------------------------------------- catalogo */}
          <div className="cache-section">
            <div className="cache-section-head">
              <span className="group-label" style={{ margin: 0 }}>
                Catálogo de livros
              </span>
              <span className="time">
                {report?.catalogs.length ?? 0} livro(s) · {formatBytes(totalCatalogos)}
              </span>
              <div className="spacer" />
              <button className="ghost" onClick={() => window.api.openCacheDir('catalogs')}>
                Abrir pasta
              </button>
              {clearButton('catalogs', 'Limpar catálogo', totalCatalogos, report?.catalogs.length ?? 0)}
            </div>

            <p className="cache-hint">
              Guarda a resposta da API por 7 dias. Remover força uma nova consulta — é o que traz capítulos publicados
              depois da última visita.
            </p>

            {report?.catalogs.length === 0 && <div className="cache-empty">Nenhum livro consultado ainda.</div>}

            {report?.catalogs.map((c) => (
              <div className="cache-row" key={c.path}>
                <span className="cache-name">{c.name}</span>
                <span className="time">{c.chapters} cap.</span>
                {c.stale && <span className="tag warn">vencido</span>}
                <div className="spacer" />
                <span className="time">{formatBytes(c.bytes)}</span>
                <span className="time dim-date">{formatDate(c.fetchedAt)}</span>
                <button className="linkish" disabled={busy} onClick={() => remove([c.path])}>
                  remover
                </button>
              </div>
            ))}
          </div>

          {/* ------------------------------------------------------- outros */}
          {(report?.strays.length ?? 0) > 0 && (
            <div className="cache-section">
              <div className="cache-section-head">
                <span className="group-label" style={{ margin: 0 }}>
                  Outros arquivos
                </span>
                <span className="time">
                  {report!.strays.length} arquivo(s) · {formatBytes(totalStrays)}
                </span>
              </div>
              <p className="cache-hint">Estão nas pastas do app, mas não seguem o padrão que ele cria.</p>
              {report!.strays.map((s) => (
                <div className="cache-row" key={s.path}>
                  <span className="cache-name">{s.name}</span>
                  <span className="time">{s.where === 'videos' ? 'vídeos' : 'catálogo'}</span>
                  <div className="spacer" />
                  <span className="time">{formatBytes(s.bytes)}</span>
                  <button className="linkish" disabled={busy} onClick={() => remove([s.path])}>
                    remover
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>

        <footer className="modal-foot">
          <span className="time" title={report?.videosDir}>
            {report?.videosDir}
          </span>
          {clearButton('all', 'Limpar tudo', total, (report?.videos.length ?? 0) + (report?.catalogs.length ?? 0))}
        </footer>
      </div>
    </div>
  );
}
