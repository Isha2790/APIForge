'use client';
import { useCallback, useEffect, useState } from 'react';
import { Api, call, errorText, GATEWAY, IssuedKey, KeyUsage, KeyView, Org } from '../lib/api';

type Props = { token: string; org: Org };

export default function ApisPanel({ token, org }: Props) {
  const [apis, setApis] = useState<Api[]>([]);
  const [form, setForm] = useState({ name: '', upstreamUrl: '', requestsPerMinute: 60 });
  const [editing, setEditing] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [issued, setIssued] = useState<{ apiId: string; key: IssuedKey } | null>(null);
  const canManage = org.role !== 'MEMBER';

  const load = useCallback(async () => {
    try { setApis(await call<Api[]>(token, `/organizations/${org.id}/apis`)); } catch (e) { setError(errorText(e)); }
  }, [token, org.id]);
  useEffect(() => { setIssued(null); load(); }, [load]);

  async function save() {
    setError('');
    try {
      const path = `/organizations/${org.id}/apis${editing ? '/' + editing : ''}`;
      await call(token, path, editing ? 'PUT' : 'POST', form);
      setForm({ name: '', upstreamUrl: '', requestsPerMinute: 60 });
      setEditing(null);
      await load();
    } catch (e) { setError(errorText(e)); }
  }

  async function remove(id: string) {
    if (!confirm('Delete this API and all of its keys?')) return;
    try { await call(token, `/organizations/${org.id}/apis/${id}`, 'DELETE'); await load(); } catch (e) { setError(errorText(e)); }
  }

  return (
    <div className="stack">
      {error && <div className="error">{error}</div>}
      {issued && (
        <div className="notice">
          <b>Copy this key now, it will not be shown again:</b><br /><code>{issued.key.key}</code><br />
          <span className="muted">Try it: curl -H &quot;X-API-Key: …&quot; {GATEWAY}/proxy/{issued.apiId}/</span>
          <br /><button className="secondary small" onClick={() => setIssued(null)}>Dismiss</button>
        </div>
      )}
      {canManage && (
        <section className="panel stack">
          <h2 className="text-lg font-semibold">{editing ? 'Edit API' : 'Register an API'}</h2>
          <div className="row">
            <div><label>Name</label><input value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></div>
            <div><label>Upstream URL (public http/https)</label><input value={form.upstreamUrl} onChange={e => setForm({ ...form, upstreamUrl: e.target.value })} placeholder="https://api.example.com" /></div>
            <div><label>Default requests/minute</label><input type="number" min={1} value={form.requestsPerMinute} onChange={e => setForm({ ...form, requestsPerMinute: Number(e.target.value) })} /></div>
          </div>
          <div className="row">
            <button onClick={save} disabled={!form.name.trim() || !form.upstreamUrl.trim()}>{editing ? 'Save changes' : 'Register API'}</button>
            {editing && <button className="secondary" onClick={() => { setEditing(null); setForm({ name: '', upstreamUrl: '', requestsPerMinute: 60 }); }}>Cancel</button>}
          </div>
        </section>
      )}
      {apis.length === 0 && <p className="muted">No APIs yet.</p>}
      {apis.map(api => (
        <section key={api.id} className="panel stack">
          <div className="row">
            <div><b>{api.name}</b><br /><span className="muted">{api.upstreamUrl}</span></div>
            <div><span className="pill">{api.requestsPerMinute} req/min default</span> <code>{api.id}</code></div>
            {canManage && (
              <div>
                <button className="secondary small" onClick={() => { setEditing(api.id); setForm({ name: api.name, upstreamUrl: api.upstreamUrl, requestsPerMinute: api.requestsPerMinute }); }}>Edit</button>{' '}
                <button className="danger small" onClick={() => remove(api.id)}>Delete</button>
              </div>
            )}
          </div>
          <Keys token={token} api={api} canManage={canManage} onIssued={key => setIssued({ apiId: api.id, key })} />
        </section>
      ))}
    </div>
  );
}

const blankKey = { name: '', requestsPerMinute: '', dailyQuota: '', monthlyQuota: '', allowedMethods: '', pathPrefix: '', expiresInDays: '' };

function Keys({ token, api, canManage, onIssued }: { token: string; api: Api; canManage: boolean; onIssued: (key: IssuedKey) => void }) {
  const [keys, setKeys] = useState<KeyView[]>([]);
  const [usage, setUsage] = useState<Record<string, KeyUsage>>({});
  const [form, setForm] = useState(blankKey);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try { setKeys(await call<KeyView[]>(token, `/apis/${api.id}/keys`)); } catch (e) { setError(errorText(e)); }
  }, [token, api.id]);
  useEffect(() => { load(); }, [load]);

  const num = (v: string) => (v.trim() === '' ? undefined : Number(v));
  const options = () => ({
    name: form.name.trim() || undefined, requestsPerMinute: num(form.requestsPerMinute), dailyQuota: num(form.dailyQuota),
    monthlyQuota: num(form.monthlyQuota), allowedMethods: form.allowedMethods.trim() || undefined,
    pathPrefix: form.pathPrefix.trim() || undefined, expiresInDays: num(form.expiresInDays),
  });

  async function act(fn: () => Promise<void>) {
    setError('');
    try { await fn(); await load(); } catch (e) { setError(errorText(e)); }
  }

  const create = () => act(async () => {
    onIssued(await call<IssuedKey>(token, `/apis/${api.id}/keys`, 'POST', options()));
    setForm(blankKey); setOpen(false);
  });
  const rotate = (k: KeyView) => act(async () => {
    const grace = prompt('Minutes the OLD key keeps working (0 = cut off now)', '60');
    if (grace === null) return;
    onIssued(await call<IssuedKey>(token, `/apis/${api.id}/keys/${k.id}/rotate`, 'POST', { graceMinutes: Number(grace) }));
  });
  const revoke = (k: KeyView) => act(async () => {
    if (confirm(`Revoke "${k.name}"? It stops working immediately.`)) await call(token, `/apis/${api.id}/keys/${k.id}`, 'DELETE');
  });
  const showUsage = (k: KeyView) => act(async () => {
    const u = await call<KeyUsage>(token, `/apis/${api.id}/keys/${k.id}/usage`);
    setUsage(prev => ({ ...prev, [k.id]: u }));
  });

  return (
    <div className="stack">
      {error && <div className="error">{error}</div>}
      <table>
        <thead><tr><th>Key</th><th>Limit</th><th>Quota (day / month)</th><th>Scope</th><th>Status</th><th></th></tr></thead>
        <tbody>
          {keys.map(k => (
            <tr key={k.id}>
              <td>{k.name}<br /><code>{k.prefix}…</code></td>
              <td>{k.requestsPerMinute ?? api.requestsPerMinute}/min</td>
              <td>{k.dailyQuota || '∞'} / {k.monthlyQuota || '∞'}
                {usage[k.id] && <><br /><span className="muted">used {usage[k.id].dailyUsed} today, {usage[k.id].monthlyUsed} this month</span></>}</td>
              <td>{k.allowedMethods.split(',').length >= 7 ? 'all methods' : k.allowedMethods} <code>{k.pathPrefix}</code></td>
              <td>{k.revoked ? <span className="pill">revoked</span> : k.previousKeyValidUntil ? <span className="pill">rotating (old key until {new Date(k.previousKeyValidUntil).toLocaleTimeString()})</span> : <span className="pill">active</span>}</td>
              <td>
                <button className="secondary small" onClick={() => showUsage(k)}>Usage</button>{' '}
                {canManage && !k.revoked && <><button className="secondary small" onClick={() => rotate(k)}>Rotate</button>{' '}<button className="danger small" onClick={() => revoke(k)}>Revoke</button></>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {canManage && (open ? (
        <div className="stack">
          <div className="row">
            <div><label>Name</label><input value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></div>
            <div><label>Requests/min (blank = API default)</label><input type="number" value={form.requestsPerMinute} onChange={e => setForm({ ...form, requestsPerMinute: e.target.value })} /></div>
            <div><label>Daily quota (blank = none)</label><input type="number" value={form.dailyQuota} onChange={e => setForm({ ...form, dailyQuota: e.target.value })} /></div>
            <div><label>Monthly quota (blank = none)</label><input type="number" value={form.monthlyQuota} onChange={e => setForm({ ...form, monthlyQuota: e.target.value })} /></div>
          </div>
          <div className="row">
            <div><label>Allowed methods (e.g. GET,POST)</label><input value={form.allowedMethods} onChange={e => setForm({ ...form, allowedMethods: e.target.value })} placeholder="all" /></div>
            <div><label>Path prefix (e.g. /v1)</label><input value={form.pathPrefix} onChange={e => setForm({ ...form, pathPrefix: e.target.value })} placeholder="/" /></div>
            <div><label>Expires in days</label><input type="number" value={form.expiresInDays} onChange={e => setForm({ ...form, expiresInDays: e.target.value })} /></div>
          </div>
          <div className="row"><button onClick={create}>Create key</button><button className="secondary" onClick={() => setOpen(false)}>Cancel</button></div>
        </div>
      ) : <div><button className="secondary small" onClick={() => setOpen(true)}>New API key</button></div>)}
    </div>
  );
}
