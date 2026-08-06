import { useEffect, useState } from 'react';
import type { UpdateStatus } from '../shared/types';

export function UpdateBanner() {
  const [status, setStatus] = useState<UpdateStatus | null>(null);

  useEffect(() => window.api.onUpdateStatus((s) => setStatus(s.state === 'error' ? null : s)), []);

  if (!status) return null;

  return (
    <div className="update-banner">
      {status.state === 'available' && <span>Nova versão {status.version} encontrada, baixando…</span>}

      {status.state === 'downloading' && (
        <>
          <span>Baixando atualização… {Math.round(status.percent)}%</span>
          <div className="bar update-bar">
            <i style={{ width: `${status.percent}%` }} />
          </div>
        </>
      )}

      {status.state === 'downloaded' && (
        <>
          <span>Versão {status.version} pronta para instalar.</span>
          <button className="primary" onClick={() => window.api.installUpdate()}>
            Reiniciar e instalar
          </button>
        </>
      )}
    </div>
  );
}
