'use client';
import { useCallback, useEffect, useState } from 'react';
import { call, errorText, Org } from '../lib/api';
import ApisPanel from './ApisPanel';
import TeamPanel from './TeamPanel';
import AnalyticsPanel from './AnalyticsPanel';

type Tab = 'apis' | 'team' | 'analytics';

export default function Dashboard({ token }: { token: string }) {
  const [orgs, setOrgs] = useState<Org[]>([]);
  const [orgId, setOrgId] = useState('');
  const [tab, setTab] = useState<Tab>('apis');
  const [newOrg, setNewOrg] = useState('');
  const [inviteToken, setInviteToken] = useState('');
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  const load = useCallback(async (select?: string) => {
    try {
      const list = await call<Org[]>(token, '/organizations');
      setOrgs(list);
      setOrgId(current => select || current || list[0]?.id || '');
    } catch (e) {
      setError(errorText(e));
    }
  }, [token]);

  useEffect(() => { load(); }, [load]);

  async function createOrg() {
    setError('');
    try {
      const org = await call<Org>(token, '/organizations', 'POST', { name: newOrg });
      setNewOrg('');
      await load(org.id);
    } catch (e) { setError(errorText(e)); }
  }

  async function acceptInvite() {
    setError(''); setMessage('');
    try {
      const accepted = await call<{ organizationId: string; role: string }>(token, '/invitations/accept', 'POST', { token: inviteToken.trim() });
      setInviteToken('');
      setMessage(`Joined as ${accepted.role}.`);
      await load(accepted.organizationId);
    } catch (e) { setError(errorText(e)); }
  }

  const org = orgs.find(o => o.id === orgId);

  return (
    <div className="stack">
      {error && <div className="error">{error}</div>}
      {message && <div className="notice">{message}</div>}
      <section className="panel stack">
        <div className="row">
          <div>
            <label>Organization</label>
            <select value={orgId} onChange={e => setOrgId(e.target.value)} style={{ width: '100%' }}>
              {orgs.map(o => <option key={o.id} value={o.id}>{o.name} ({o.role})</option>)}
            </select>
          </div>
          <div><label>New organization</label><input value={newOrg} onChange={e => setNewOrg(e.target.value)} /></div>
          <button onClick={createOrg} disabled={!newOrg.trim()}>Create</button>
        </div>
        <div className="row">
          <div><label>Have an invitation token?</label><input value={inviteToken} onChange={e => setInviteToken(e.target.value)} placeholder="inv_..." /></div>
          <button className="secondary" onClick={acceptInvite} disabled={!inviteToken.trim()}>Join organization</button>
        </div>
      </section>
      {org ? (
        <>
          <div className="tabs">
            {(['apis', 'team', 'analytics'] as Tab[]).map(t => (
              <button key={t} className={tab === t ? 'active' : ''} onClick={() => setTab(t)}>
                {t === 'apis' ? 'APIs & keys' : t === 'team' ? 'Team' : 'Analytics'}
              </button>
            ))}
          </div>
          {tab === 'apis' && <ApisPanel token={token} org={org} />}
          {tab === 'team' && <TeamPanel token={token} org={org} />}
          {tab === 'analytics' && <AnalyticsPanel token={token} org={org} />}
        </>
      ) : <p className="muted">Create an organization to get started.</p>}
    </div>
  );
}
