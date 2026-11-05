'use client';
import { useCallback, useEffect, useState } from 'react';
import { call, errorText, Invitation, Member, Org } from '../lib/api';

export default function TeamPanel({ token, org }: { token: string; org: Org }) {
  const [members, setMembers] = useState<Member[]>([]);
  const [invitations, setInvitations] = useState<Invitation[]>([]);
  const [email, setEmail] = useState('');
  const [role, setRole] = useState('MEMBER');
  const [issuedToken, setIssuedToken] = useState('');
  const [error, setError] = useState('');
  const isOwner = org.role === 'OWNER';
  const canInvite = org.role !== 'MEMBER';

  const load = useCallback(async () => {
    try {
      setMembers(await call<Member[]>(token, `/organizations/${org.id}/members`));
      if (canInvite) setInvitations(await call<Invitation[]>(token, `/organizations/${org.id}/invitations`));
    } catch (e) { setError(errorText(e)); }
  }, [token, org.id, canInvite]);
  useEffect(() => { setIssuedToken(''); load(); }, [load]);

  async function act(fn: () => Promise<void>) {
    setError('');
    try { await fn(); await load(); } catch (e) { setError(errorText(e)); }
  }

  const invite = () => act(async () => {
    const created = await call<{ token: string }>(token, `/organizations/${org.id}/invitations`, 'POST', { email, role });
    setIssuedToken(created.token);
    setEmail('');
  });

  return (
    <div className="stack">
      {error && <div className="error">{error}</div>}
      <section className="panel stack">
        <h2 className="text-lg font-semibold">Members</h2>
        <table>
          <thead><tr><th>Email</th><th>Role</th><th></th></tr></thead>
          <tbody>
            {members.map(m => (
              <tr key={m.userId}>
                <td>{m.email}</td>
                <td>{isOwner && m.role !== 'OWNER'
                  ? <select value={m.role} onChange={e => act(async () => { await call(token, `/organizations/${org.id}/members/${m.userId}`, 'PATCH', { role: e.target.value }); })}>
                      <option>ADMIN</option><option>MEMBER</option></select>
                  : <span className="pill">{m.role}</span>}</td>
                <td>{isOwner && m.role !== 'OWNER' && <button className="danger small" onClick={() => act(async () => { await call(token, `/organizations/${org.id}/members/${m.userId}`, 'DELETE'); })}>Remove</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="muted">OWNER manages everything. ADMIN manages APIs and keys and can invite members. MEMBER is read-only.</p>
      </section>
      {canInvite && (
        <section className="panel stack">
          <h2 className="text-lg font-semibold">Invite someone</h2>
          {issuedToken && <div className="notice">The invitee has been emailed this token (it is bound to their email and shown here only once, in case you want to share it yourself):<br /><code>{issuedToken}</code></div>}
          <div className="row">
            <div><label>Email (must match their account)</label><input value={email} onChange={e => setEmail(e.target.value)} /></div>
            <div><label>Role</label>
              <select value={role} onChange={e => setRole(e.target.value)} style={{ width: '100%' }}>
                <option>MEMBER</option>{isOwner && <option>ADMIN</option>}
              </select></div>
            <button onClick={invite} disabled={!email.trim()}>Create invitation</button>
          </div>
          {invitations.length > 0 && (
            <table>
              <thead><tr><th>Pending invitation</th><th>Role</th><th>Expires</th><th></th></tr></thead>
              <tbody>{invitations.map(i => (
                <tr key={i.id}><td>{i.email}</td><td>{i.role}</td><td>{new Date(i.expiresAt).toLocaleDateString()}</td>
                  <td><button className="secondary small" onClick={() => act(async () => { await call(token, `/organizations/${org.id}/invitations/${i.id}`, 'DELETE'); })}>Revoke</button></td></tr>
              ))}</tbody>
            </table>
          )}
        </section>
      )}
    </div>
  );
}
