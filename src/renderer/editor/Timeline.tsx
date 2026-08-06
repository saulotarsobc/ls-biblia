import { useCallback, useEffect, useRef, useState } from 'react';
import { editDuration, formatTime } from '../../shared/timeline.ts';
import { useStore } from '../store.ts';

interface Props {
  editTime: number;
  onSeek: (t: number) => void;
  selectedRegion: string | null;
  setSelectedRegion: (id: string | null) => void;
  selectedZoom: string | null;
  setSelectedZoom: (id: string | null) => void;
}

type Lane = 'speed' | 'zoom';

type Drag =
  | { kind: 'new'; lane: Lane; from: number; to: number }
  | { kind: 'move'; lane: Lane; id: string; grabOffset: number; width: number }
  | { kind: 'edge'; lane: Lane; id: string; edge: 'l' | 'r' }
  | { kind: 'scrub' };

/** Marcas de tempo espacadas conforme a duracao, para nao virar sopa. */
function tickStep(total: number): number {
  for (const s of [1, 2, 5, 10, 15, 30, 60, 120, 300, 600]) {
    if (total / s <= 12) return s;
  }
  return 900;
}

export function Timeline({
  editTime,
  onSeek,
  selectedRegion,
  setSelectedRegion,
  selectedZoom,
  setSelectedZoom,
}: Props) {
  const edit = useStore((s) => s.edit);
  const addSpeedRegion = useStore((s) => s.addSpeedRegion);
  const updateSpeedRegion = useStore((s) => s.updateSpeedRegion);
  const addZoomRegion = useStore((s) => s.addZoomRegion);
  const updateZoomRegion = useStore((s) => s.updateZoomRegion);

  const total = editDuration(edit.ranges);
  const lanes = useRef<Record<Lane, HTMLDivElement | null>>({ speed: null, zoom: null });
  const [drag, setDrag] = useState<Drag | null>(null);

  const pct = (t: number) => `${total ? (t / total) * 100 : 0}%`;

  const timeAt = useCallback(
    (clientX: number, el: HTMLElement) => {
      const r = el.getBoundingClientRect();
      const f = Math.min(1, Math.max(0, (clientX - r.left) / r.width));
      return f * total;
    },
    [total],
  );

  // O movimento e escutado na janela toda: assim arrastar para fora da faixa
  // continua funcionando em vez de travar na borda.
  useEffect(() => {
    if (!drag) return;

    const move = (e: PointerEvent) => {
      const el = drag.kind === 'scrub' ? lanes.current.speed : lanes.current[drag.lane];
      if (!el) return;
      const t = timeAt(e.clientX, el);

      if (drag.kind === 'scrub') return onSeek(t);
      if (drag.kind === 'new') return setDrag({ ...drag, to: t });

      const atualizar = drag.lane === 'speed' ? updateSpeedRegion : updateZoomRegion;
      const lista = drag.lane === 'speed' ? edit.speedRegions : edit.zoomRegions;

      if (drag.kind === 'move') {
        const start = Math.min(Math.max(0, t - drag.grabOffset), total - drag.width);
        atualizar(drag.id, { start, end: start + drag.width });
      } else {
        const r = lista.find((x) => x.id === drag.id);
        if (!r) return;
        if (drag.edge === 'l') atualizar(drag.id, { start: Math.min(t, r.end - 0.1) });
        else atualizar(drag.id, { end: Math.max(t, r.start + 0.1) });
      }
    };

    const up = () => {
      if (drag.kind === 'new') {
        const [a, b] = [drag.from, drag.to].sort((x, y) => x - y);
        // arrasto muito curto costuma ser clique errado, nao uma faixa
        if (b - a > 0.25) {
          if (drag.lane === 'speed') addSpeedRegion({ start: a, end: b, speed: 0.5 });
          else setSelectedZoom(addZoomRegion({ start: a, end: b, zoom: 1.8, cx: 0.5, cy: 0.45, ramp: 0.4 }));
        }
      }
      setDrag(null);
    };

    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
    return () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };
  }, [
    drag,
    total,
    edit.speedRegions,
    edit.zoomRegions,
    timeAt,
    onSeek,
    addSpeedRegion,
    updateSpeedRegion,
    addZoomRegion,
    updateZoomRegion,
    setSelectedZoom,
  ]);

  const step = tickStep(total);
  const ticks: number[] = [];
  for (let t = 0; t <= total + 1e-6; t += step) ticks.push(t);

  // fronteiras entre trechos cortados, em tempo de edicao
  const cuts: number[] = [];
  let acc = 0;
  for (const r of edit.ranges.slice(0, -1)) {
    acc += r.end - r.start;
    cuts.push(acc);
  }

  /** Uma faixa arrastavel, com alcas nas duas pontas. */
  const faixa = (
    lane: Lane,
    r: { id: string; start: number; end: number },
    rotulo: string,
    selecionado: boolean,
    selecionar: (id: string) => void,
  ) => (
    <div
      key={r.id}
      className={`region${lane === 'zoom' ? ' zoomregion' : ''}${selecionado ? ' sel' : ''}`}
      style={{ left: pct(r.start), width: pct(r.end - r.start) }}
      onPointerDown={(e) => {
        e.stopPropagation();
        selecionar(r.id);
        const el = lanes.current[lane];
        if (!el) return;
        setDrag({
          kind: 'move',
          lane,
          id: r.id,
          grabOffset: timeAt(e.clientX, el) - r.start,
          width: r.end - r.start,
        });
      }}
    >
      {rotulo}
      {(['l', 'r'] as const).map((edge) => (
        <div
          key={edge}
          className={`handle ${edge}`}
          onPointerDown={(e) => {
            e.stopPropagation();
            selecionar(r.id);
            setDrag({ kind: 'edge', lane, id: r.id, edge });
          }}
        />
      ))}
    </div>
  );

  /** Fundo de uma faixa: cria por arrasto e limpa a selecao ao clicar no vazio. */
  const fundoDaFaixa = (lane: Lane) => (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.target !== e.currentTarget) return;
    const t = timeAt(e.clientX, e.currentTarget);
    if (lane === 'speed') setSelectedRegion(null);
    else setSelectedZoom(null);
    setDrag({ kind: 'new', lane, from: t, to: t });
  };

  return (
    <div className="timeline">
      <div
        className="lane ruler"
        onPointerDown={(e) => {
          onSeek(timeAt(e.clientX, e.currentTarget));
          setDrag({ kind: 'scrub' });
        }}
      >
        {ticks.map((t) => (
          <div key={t} className="tick" style={{ left: pct(t) }}>
            <span>{formatTime(t)}</span>
          </div>
        ))}
        <div className="playhead" style={{ left: pct(editTime) }} />
      </div>

      <div className="lane-label">Câmera lenta — arraste na faixa para criar um trecho</div>
      <div
        ref={(el) => {
          lanes.current.speed = el;
        }}
        className="lane"
        onPointerDown={fundoDaFaixa('speed')}
      >
        {cuts.map((t, i) => (
          <div key={i} className="cutline" style={{ left: pct(t) }} />
        ))}
        {edit.speedRegions.map((r) => faixa('speed', r, `${r.speed}×`, selectedRegion === r.id, setSelectedRegion))}
        {drag?.kind === 'new' && drag.lane === 'speed' && (
          <div
            className="region"
            style={{
              left: pct(Math.min(drag.from, drag.to)),
              width: pct(Math.abs(drag.to - drag.from)),
              opacity: 0.6,
            }}
          />
        )}
        <div className="playhead" style={{ left: pct(editTime) }} />
      </div>

      <div className="lane-label">
        Zoom — arraste aqui para criar um trecho; depois role o scroll no vídeo para enquadrar
      </div>
      <div
        ref={(el) => {
          lanes.current.zoom = el;
        }}
        className="lane"
        onPointerDown={fundoDaFaixa('zoom')}
      >
        {edit.zoomRegions.map((r) => faixa('zoom', r, `${r.zoom.toFixed(1)}×`, selectedZoom === r.id, setSelectedZoom))}
        {drag?.kind === 'new' && drag.lane === 'zoom' && (
          <div
            className="region zoomregion"
            style={{
              left: pct(Math.min(drag.from, drag.to)),
              width: pct(Math.abs(drag.to - drag.from)),
              opacity: 0.6,
            }}
          />
        )}
        <div className="playhead" style={{ left: pct(editTime) }} />
      </div>
    </div>
  );
}
