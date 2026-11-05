'use client';
import { useEffect, useState } from 'react';
import { call, errorText } from '../lib/api';

/** Landing page for the emailed link: /reset-password?token=... */
export default function ResetPassword() {
  const [token, setToken] = useState('');
  const [password, setPassword] = useState('');
  const [again, setAgain] = useState('');
  const [error, setError] = useState('');
  const [done, setDone] = useState('');

  useEffect(() => {
    const found = new URLSearchParams(window.location.search).get('token') || '';
    setToken(found);
    // keep the secret out of the address bar, history and any later screenshot
    if (found) window.history.replaceState(null, '', window.location.pathname);
  }, []);

  async function submit() {
    setError('');
    if (password !== again) { setError('The two passwords do not match.'); return; }
    try {
      const result = await call<{ message: string }>(null, '/auth/reset-password', 'POST', { token, newPassword: password });
      setDone(result.message);
      setPassword(''); setAgain('');
    } catch (e) {
      setError(errorText(e));
    }
  }

  return (
    <main className="max-w-6xl mx-auto p-6">
      <header className="flex items-center justify-between mb-6">
        <h1 className="text-3xl font-bold">APIForge</h1>
      </header>
      <section className="panel stack max-w-md">
        <h2 className="text-xl font-semibold">Choose a new password</h2>
        {done ? (
          <>
            <div className="notice">{done}</div>
            <a href="/"><button>Go to sign in</button></a>
          </>
        ) : !token ? (
          <>
            <div className="error">This page needs the link from your reset email. Request a new one from the sign-in page.</div>
            <a href="/"><button className="secondary">Back to sign in</button></a>
          </>
        ) : (
          <>
            <p className="muted">At least 10 characters. The link works once.</p>
            <div><label>New password</label><input value={password} onChange={e => setPassword(e.target.value)} type="password" /></div>
            <div><label>Repeat new password</label><input value={again} onChange={e => setAgain(e.target.value)} type="password" /></div>
            {error && <div className="error">{error}</div>}
            <button onClick={submit} disabled={!password || !again}>Save new password</button>
          </>
        )}
      </section>
    </main>
  );
}
