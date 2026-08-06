import { useCallback, useEffect, useRef, useState } from 'react';
import {
  buildAtoms,
  clampCenter,
  editDuration,
  editToSource,
  formatTime,
  nextRangeStart,
  outputDuration,
  previewTransform,
  sourceToEdit,
  speedAt,
  zoomAt,
} from '../../shared/timeline.ts';
import { useStore } from '../store.ts';
import { Timeline } from './Timeline.tsx';

export function Editor() {
  const {
    mediaUrl,
    sourcePath,
    edit,
    detail,
    chapter,
    updateSpeedRegion,
    removeSpeedRegion,
    addZoomRegion,
    updateZoomRegion,
    removeZoomRegion,
    setSmoothSlowMotion,
    startExport,
    cancelExport,
    exporting,
    exportProgress,
    exportError,
    exportedPath,
  } = useStore();

  const video = useRef<HTMLVideoElement>(null);
  const frame = useRef<HTMLDivElement>(null);
  const [editTime, setEditTime] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [videoError, setVideoError] = useState<string | null>(null);
  const [panning, setPanning] = useState(false);

  /**
   * Uma selecao so, das duas faixas juntas.
   *
   * Antes eram dois estados independentes, e escolher um nao limpava o outro:
   * com um zoom selecionado antes, apertar Del num trecho de camera lenta
   * apagava os dois. Guardar tipo + id impede que sobrem duas selecoes vivas.
   */
  const [sel, setSel] = useState<{ tipo: 'lenta' | 'zoom'; id: string } | null>(null);

  const selRegion = sel?.tipo === 'lenta' ? sel.id : null;
  const selZoom = sel?.tipo === 'zoom' ? sel.id : null;
  const setSelRegion = (id: string | null) => setSel(id ? { tipo: 'lenta', id } : null);
  const setSelZoom = (id: string | null) => setSel(id ? { tipo: 'zoom', id } : null);

  const total = editDuration(edit.ranges);
  const outDur = outputDuration(buildAtoms(edit.ranges, edit.speedRegions));

  const seek = useCallback(
    (t: number) => {
      const clamped = Math.min(Math.max(0, t), total);
      setEditTime(clamped);
      if (video.current) video.current.currentTime = editToSource(clamped, edit.ranges);
    },
    [total, edit.ranges],
  );

  /**
   * O preview toca o arquivo ORIGINAL: a cada quadro convertemos a posicao para
   * tempo de edicao e, ao sair de um trecho selecionado, pulamos para o proximo.
   * Assim o editor abre na hora, sem renderizar nenhum arquivo intermediario.
   */
  useEffect(() => {
    const el = video.current;
    if (!el) return;
    let raf = 0;

    const tick = () => {
      const src = el.currentTime;
      const mapped = sourceToEdit(src, edit.ranges);

      if (mapped === null) {
        const next = nextRangeStart(src, edit.ranges);
        if (next === null) {
          el.pause();
          setPlaying(false);
        } else {
          el.currentTime = next;
        }
      } else {
        setEditTime(mapped);
        // camera lenta ao vivo, para conferir o ritmo antes de exportar
        const s = speedAt(mapped, edit.speedRegions);
        if (Math.abs(el.playbackRate - s) > 1e-3) el.playbackRate = s;
      }
      raf = requestAnimationFrame(tick);
    };

    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [edit.ranges, edit.speedRegions]);

  /**
   * Posicao inicial do preview. Estes videos abrem com cerca de um segundo de
   * tela preta, entao parar exatamente em 0 faz o editor parecer quebrado —
   * avanca um pouco para cair num frame com imagem. So afeta o preview; os
   * trechos usados na exportacao continuam intactos.
   */
  useEffect(() => {
    const el = video.current;
    if (!el || !edit.ranges.length) return;
    const first = edit.ranges[0];
    const pos = first.start < 0.5 ? Math.min(1, first.end) : first.start;
    const apply = () => {
      el.currentTime = pos;
    };
    if (el.readyState >= 1) apply();
    else el.addEventListener('loadedmetadata', apply, { once: true });
    return () => el.removeEventListener('loadedmetadata', apply);
  }, [edit.ranges]);

  const toggle = () => {
    const el = video.current;
    if (!el) return;
    if (el.paused) {
      if (editTime >= total - 0.05) seek(0);
      el.play();
      setPlaying(true);
    } else {
      el.pause();
      setPlaying(false);
    }
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.code === 'Space') {
        e.preventDefault();
        toggle();
      } else if (e.key === 'ArrowLeft') seek(editTime - (e.shiftKey ? 5 : 1 / 30));
      else if (e.key === 'ArrowRight') seek(editTime + (e.shiftKey ? 5 : 1 / 30));
      else if (e.key === 'Delete' && sel) {
        // apaga so o que esta selecionado agora, nunca os dois
        if (sel.tipo === 'lenta') removeSpeedRegion(sel.id);
        else removeZoomRegion(sel.id);
        setSel(null);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  // Mesmo enquadramento que o ffmpeg produz: a janela visivel mede 1/zoom do
  // quadro e e presa nas bordas para nao mostrar area vazia.
  const z = zoomAt(editTime, edit.zoomRegions);

  const region = edit.speedRegions.find((r) => r.id === selRegion);

  /** Faixa de zoom que vale no instante atual, se houver. */
  const zoomAqui = edit.zoomRegions.find((r) => editTime >= r.start && editTime <= r.end) ?? null;

  /**
   * Faixa que recebe o ajuste. Se a agulha nao esta dentro de nenhuma, cria uma
   * de dois segundos centrada nela — assim basta parar no momento certo e rolar
   * o scroll, sem precisar desenhar a faixa antes.
   */
  const faixaParaEditar = useCallback((): string => {
    if (zoomAqui) return zoomAqui.id;
    const meio = 1;
    const start = Math.max(0, editTime - meio);
    const end = Math.min(total, editTime + meio);
    return addZoomRegion({ start, end, zoom: 1, cx: 0.5, cy: 0.5, ramp: 0.4 });
  }, [zoomAqui, editTime, total, addZoomRegion]);

  /** Converte a posicao do cursor para fracao 0..1 dentro do quadro exibido. */
  const cursorNoQuadro = (clientX: number, clientY: number) => {
    const el = frame.current;
    if (!el) return { u: 0.5, v: 0.5 };
    const r = el.getBoundingClientRect();
    return {
      u: Math.min(1, Math.max(0, (clientX - r.left) / r.width)),
      v: Math.min(1, Math.max(0, (clientY - r.top) / r.height)),
    };
  };

  /**
   * Scroll aproxima e afasta mantendo fixo o ponto sob o cursor — o mesmo
   * comportamento de mapas, que e o que as pessoas ja esperam.
   */
  useEffect(() => {
    const el = frame.current;
    if (!el) return;

    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      // Ajusta o ALVO da faixa, nao o valor interpolado: parado dentro de uma
      // rampa, o zoom visivel e parcial, e usa-lo como base faria cada scroll
      // render um passo diferente.
      const base = zoomAqui ?? { zoom: 1, cx: 0.5, cy: 0.5 };
      const alvo = Math.min(4, Math.max(1, base.zoom * (e.deltaY < 0 ? 1.12 : 1 / 1.12)));
      const centro = clampCenter(base.zoom, base.cx, base.cy);

      const { u, v } = cursorNoQuadro(e.clientX, e.clientY);
      // ponto do quadro que esta sob o cursor agora
      const larguraAtual = 1 / base.zoom;
      const pontoX = centro.cx - larguraAtual / 2 + u * larguraAtual;
      const pontoY = centro.cy - larguraAtual / 2 + v * larguraAtual;
      // reposiciona para esse mesmo ponto continuar sob o cursor
      const larguraNova = 1 / alvo;
      const novo = clampCenter(
        alvo,
        pontoX - u * larguraNova + larguraNova / 2,
        pontoY - v * larguraNova + larguraNova / 2,
      );

      const id = faixaParaEditar();
      updateZoomRegion(id, { zoom: alvo, cx: novo.cx, cy: novo.cy });
      setSelZoom(id);
    };

    // precisa ser nao-passivo para o preventDefault valer
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [zoomAqui, faixaParaEditar, updateZoomRegion]);

  /** Arrastar move o enquadramento junto com o cursor, como arrastar uma foto. */
  const startPan = (e: React.PointerEvent) => {
    const atual = zoomAqui;
    if (!atual || atual.zoom <= 1.001) return; // sem zoom nao ha para onde mover
    e.preventDefault();

    const el = video.current;
    const larguraLayout = el?.offsetWidth ?? 1;
    const alturaLayout = el?.offsetHeight ?? 1;
    const inicio = clampCenter(atual.zoom, atual.cx, atual.cy);
    const x0 = e.clientX;
    const y0 = e.clientY;
    const id = atual.id;
    setSelZoom(id);
    setPanning(true);

    const mover = (ev: PointerEvent) => {
      const dx = (ev.clientX - x0) / (larguraLayout * atual.zoom);
      const dy = (ev.clientY - y0) / (alturaLayout * atual.zoom);
      const novo = clampCenter(atual.zoom, inicio.cx - dx, inicio.cy - dy);
      updateZoomRegion(id, { cx: novo.cx, cy: novo.cy });
    };
    const soltar = () => {
      setPanning(false);
      window.removeEventListener('pointermove', mover);
      window.removeEventListener('pointerup', soltar);
    };
    window.addEventListener('pointermove', mover);
    window.addEventListener('pointerup', soltar);
  };

  const temZoom = z.zoom > 1.001;

  return (
    <div className="editor">
      <div className="stage">
        <div className="viewport">
          <div
            ref={frame}
            className="frame"
            onPointerDown={startPan}
            style={{ cursor: temZoom ? (panning ? 'grabbing' : 'grab') : 'default' }}
            title="Role para aproximar. Com zoom, arraste para enquadrar."
          >
            <video
              ref={video}
              src={mediaUrl ?? undefined}
              // sem zoom nao aplica transform nenhum: evita criar uma camada de
              // composicao no elemento de video sem necessidade
              style={
                temZoom
                  ? {
                      transform: previewTransform(z.zoom, z.cx, z.cy),
                      transformOrigin: '0 0',
                    }
                  : undefined
              }
              onError={() => {
                const err = video.current?.error;
                setVideoError(
                  `Não consegui abrir o vídeo baixado (código ${err?.code ?? '?'}). ` +
                    `O arquivo está em ${sourcePath ?? '?'}.`,
                );
              }}
              onLoadedMetadata={() => setVideoError(null)}
            />
            {videoError && (
              <div className="notice err" style={{ position: 'absolute', inset: 12, height: 'fit-content' }}>
                {videoError}
              </div>
            )}
          </div>
        </div>

        <div className="side">
          <div className="block">
            <h3>Resumo</h3>
            <div className="stat">
              <span>Origem</span>
              <b>
                {detail?.name} {chapter?.track}
              </b>
            </div>
            <div className="stat">
              <span>Trechos</span>
              <b>{edit.ranges.length}</b>
            </div>
            <div className="stat">
              <span>Depois do corte</span>
              <b>{formatTime(total)}</b>
            </div>
            <div className="stat">
              <span>Duração final</span>
              <b>{formatTime(outDur)}</b>
            </div>
          </div>

          <div className="block">
            <h3>Câmera lenta</h3>
            {edit.speedRegions.length === 0 && (
              <p className="sub" style={{ margin: 0 }}>
                Arraste na faixa laranja da linha do tempo para marcar um trecho.
              </p>
            )}
            {edit.speedRegions.map((r) => (
              <div key={r.id} className={`item${selRegion === r.id ? ' on' : ''}`} onClick={() => setSelRegion(r.id)}>
                <span className="chip slow">{r.speed}×</span>
                <span>
                  {formatTime(r.start)} – {formatTime(r.end)}
                </span>
                <button
                  className="x"
                  onClick={(e) => {
                    e.stopPropagation();
                    removeSpeedRegion(r.id);
                  }}
                >
                  ✕
                </button>
              </div>
            ))}
            {region && (
              <div className="row" style={{ marginTop: 10 }}>
                <label>Velocidade</label>
                <input
                  type="range"
                  min={0.15}
                  max={1}
                  step={0.05}
                  value={region.speed}
                  onChange={(e) => updateSpeedRegion(region.id, { speed: Number(e.target.value) })}
                />
                <span className="val">{region.speed.toFixed(2)}×</span>
              </div>
            )}
            <label className="stat" style={{ marginTop: 8, cursor: 'pointer' }}>
              <span>Suavizar (interpolar frames)</span>
              <input
                type="checkbox"
                checked={edit.smoothSlowMotion}
                onChange={(e) => setSmoothSlowMotion(e.target.checked)}
              />
            </label>
          </div>

          <div className="block">
            <h3>Zoom</h3>
            <p className="sub" style={{ margin: '0 0 10px' }}>
              Pare a agulha no momento que quer destacar e <b>role o scroll no vídeo</b>. Um trecho de 2 segundos é
              criado ali, e a imagem <b>entra e sai do zoom suavemente</b> nas pontas. Fora do trecho, o quadro fica
              cheio. Com zoom, <b>arraste o vídeo</b> para enquadrar.
            </p>

            <div className="readout">
              <span>Neste instante</span>
              <b>
                {z.zoom.toFixed(2)}× · {(clampCenter(z.zoom, z.cx, z.cy).cx * 100).toFixed(0)}%/
                {(clampCenter(z.zoom, z.cx, z.cy).cy * 100).toFixed(0)}%
              </b>
            </div>

            {edit.zoomRegions.length === 0 && (
              <p className="sub" style={{ margin: '10px 0 0' }}>
                Nenhum trecho de zoom ainda.
              </p>
            )}

            {edit.zoomRegions.map((r) => (
              <div key={r.id}>
                <div
                  className={`item${selZoom === r.id ? ' on' : ''}`}
                  onClick={() => {
                    setSelZoom(r.id);
                    seek((r.start + r.end) / 2);
                  }}
                >
                  <span className="chip zoom">{r.zoom.toFixed(1)}×</span>
                  <span>
                    {formatTime(r.start)} – {formatTime(r.end)}
                  </span>
                  <button
                    className="x"
                    onClick={(e) => {
                      e.stopPropagation();
                      removeZoomRegion(r.id);
                    }}
                  >
                    ✕
                  </button>
                </div>
                {selZoom === r.id && (
                  <div className="row" style={{ margin: '2px 0 10px' }}>
                    <label>Suavidade</label>
                    <input
                      type="range"
                      min={0}
                      max={1.5}
                      step={0.05}
                      value={r.ramp}
                      onChange={(e) => updateZoomRegion(r.id, { ramp: Number(e.target.value) })}
                      title="Quanto tempo a imagem leva para entrar e sair do zoom"
                    />
                    <span className="val">{r.ramp.toFixed(2)}s</span>
                  </div>
                )}
              </div>
            ))}
          </div>

          <div className="block">
            <h3>Exportar</h3>
            {exporting ? (
              <>
                <div className="bar">
                  <i style={{ width: `${(exportProgress?.percent ?? 0) * 100}%` }} />
                </div>
                <div className="stat" style={{ marginTop: 6 }}>
                  <span>{((exportProgress?.percent ?? 0) * 100).toFixed(0)}%</span>
                  <b>{exportProgress?.speed ?? ''}</b>
                </div>
                <button className="danger" onClick={cancelExport} style={{ width: '100%', marginTop: 8 }}>
                  Cancelar
                </button>
              </>
            ) : (
              <button className="primary" onClick={startExport} style={{ width: '100%' }}>
                Gerar vídeo final
              </button>
            )}
            {exportError && (
              <div className="notice err" style={{ marginTop: 10, fontSize: 12 }}>
                {exportError}
              </div>
            )}
            {exportedPath && !exporting && (
              <div className="notice ok" style={{ marginTop: 10, fontSize: 12 }}>
                Pronto!{' '}
                <a
                  href="#"
                  style={{ color: 'inherit' }}
                  onClick={(e) => {
                    e.preventDefault();
                    window.api.reveal(exportedPath);
                  }}
                >
                  Mostrar na pasta
                </a>
              </div>
            )}
          </div>
        </div>
      </div>

      <div className="transport">
        <button onClick={toggle}>{playing ? '❚❚ Pausar' : '▶ Reproduzir'}</button>
        <span className="time">
          <b>{formatTime(editTime)}</b> / {formatTime(total)}
        </span>
        <span className="time">
          velocidade atual {speedAt(editTime, edit.speedRegions).toFixed(2)}× · zoom {z.zoom.toFixed(2)}×
        </span>
        <div className="spacer" />
        <span className="time">Espaço reproduz · ←/→ avança · Del apaga</span>
      </div>

      <Timeline
        editTime={editTime}
        onSeek={seek}
        selectedRegion={selRegion}
        setSelectedRegion={setSelRegion}
        selectedZoom={selZoom}
        setSelectedZoom={setSelZoom}
      />
    </div>
  );
}
