'use client';
import { useCallback, useEffect, useState } from 'react';
import { Api, ApiRow, call, errorText, Org, Point, Summary } from '../lib/api';

export default function AnalyticsPanel({ token, org }: { token: string; org: Org }) {
  const [hours, setHours] = useState(24);
  const [apiId, setApiId] = useState('');
  const [apis, setApis] = useState<Api[]>([]);
  const [summary, setSummary] = useState<Summary | null>(null);
  const [series, setSeries] = useState<Point[]>([]);
  const [rows, setRows] = useState<ApiRow[]>([]);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setError('');
    const q = `hours=${hours}${apiId ? `&apiId=${apiId}` : ''}`;
    const base = `/organizations/${org.id}`;
    try {
      const [list, s, t, r] = await Promise.all([
        call<Api[]>(token, `${base}/apis`),
        call<Summary>(token, `${base}/analytics/summary?${q}`),
        call<Point[]>(token, `${base}/analytics/timeseries?${q}`),
        call<ApiRow[]>(token, `${base}/analytics/by-api?hours=${hours}`),
      ]);
      setApis(list); setSummary(s); setSeries(t); setRows(r);
    } catch (e) { setError(errorText(e)); }
  }, [token, org.id, hours, apiId]);
  useEffect(() => { load(); }, [load]);

  const pct = (v: number) => `${(v * 100).toFixed(1)}%`;
  const cards: [string, string][] = summary ? [
    ['Requests', summary.requests.toLocaleString()], ['Rejected (limit/quota/scope)', pct(summary.rejectedRate)],
    ['Server error rate', pct(summary.errorRate)], ['Avg latency', `${summary.avgLatencyMs.toFixed(0)} ms`],
    ['Max latency', `${summary.maxLatencyMs} ms`], ['Rate limited', summary.rateLimited.toLocaleString()],
    ['Quota exceeded', summary.quotaExceeded.toLocaleString()], ['Scope denied', summary.scopeDenied.toLocaleString()],
  ] : [];

  return (
    <div className="stack">
      {error && <div className="error">{error}</div>}
      <section className="panel stack">
        <div className="row">
          <div><label>Period</label>
            <select value={hours} onChange={e => setHours(Number(e.target.value))} style={{ width: '100%' }}>
              <option value={24}>Last 24 hours</option><option value={72}>Last 3 days</option><option value={168}>Last 7 days</option><option value={720}>Last 30 days</option>
            </select></div>
          <div><label>API</label>
            <select value={apiId} onChange={e => setApiId(e.target.value)} style={{ width: '100%' }}>
              <option value="">All APIs</option>{apis.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select></div>
          <button className="secondary" onClick={load}>Refresh</button>
        </div>
        <div className="cards">{cards.map(([label, value]) => <div className="card" key={label}><span className="muted">{label}</span><b>{value}</b></div>)}</div>
      </section>
      <section className="panel stack">
        <h2 className="text-lg font-semibold">Requests per hour</h2>
        <Bars points={series} />
        <p className="muted">Blue = served, amber = rejected (rate limit or quota), red = server errors. Numbers arrive within a few seconds via Redis Streams.</p>
      </section>
      <section className="panel">
        <h2 className="text-lg font-semibold mb-2">By API</h2>
        <table>
          <thead><tr><th>API</th><th>Requests</th><th>Rate limited</th><th>Quota exceeded</th><th>Server errors</th><th>Avg latency</th></tr></thead>
          <tbody>{rows.map(r => <tr key={r.apiId}><td>{r.name}</td><td>{r.requests}</td><td>{r.rateLimited}</td><td>{r.quotaExceeded}</td><td>{r.serverErrors}</td><td>{r.avgLatencyMs.toFixed(0)} ms</td></tr>)}</tbody>
        </table>
        {rows.length === 0 && <p className="muted">No traffic recorded in this period.</p>}
      </section>
    </div>
  );
}

/** Stacked bars drawn as plain SVG: no chart library needed. */
function Bars({ points }: { points: Point[] }) {
  if (points.length === 0) return <p className="muted">No data yet. Send some requests through the gateway.</p>;
  const width = 720, height = 180, gap = 4;
  const max = Math.max(...points.map(p => p.requests), 1);
  const barWidth = Math.max(4, width / points.length - gap);
  return (
    <svg viewBox={`0 0 ${width} ${height + 20}`} style={{ width: '100%', maxHeight: 260 }} role="img" aria-label="Requests per hour">
      {points.map((p, i) => {
        const rejected = p.rateLimited + p.quotaExceeded;
        const scale = (v: number) => (v / max) * height;
        const x = i * (barWidth + gap);
        const served = scale(p.requests - rejected - p.serverErrors), rej = scale(rejected), err = scale(p.serverErrors);
        return (
          <g key={p.hour}>
            <title>{new Date(p.hour).toLocaleString()}: {p.requests} requests, {rejected} rejected, {p.serverErrors} errors</title>
            <rect x={x} y={height - served} width={barWidth} height={served} fill="#6366f1" />
            <rect x={x} y={height - served - rej} width={barWidth} height={rej} fill="#f59e0b" />
            <rect x={x} y={height - served - rej - err} width={barWidth} height={err} fill="#ef4444" />
          </g>
        );
      })}
      <text x={0} y={height + 16} fill="#9baecb" fontSize="11">{new Date(points[0].hour).toLocaleString()}</text>
      <text x={width} y={height + 16} fill="#9baecb" fontSize="11" textAnchor="end">{new Date(points[points.length - 1].hour).toLocaleString()}</text>
    </svg>
  );
}
