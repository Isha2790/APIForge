'use client';
import { useState } from 'react';
import { call, errorText } from '../lib/api';

export default function Auth({ onToken }: { onToken: (token: string) => void }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [forgot, setForgot] = useState(false);
  const [sent, setSent] = useState('');

  async function submit(mode: 'login' | 'register') {
    setError('');
    try {
      const result = await call<{ token: string }>(null, `/auth/${mode}`, 'POST', { email, password });
      onToken(result.token);
    } catch (e) {
      setError(errorText(e));
    }
  }

  async function sendReset() {
    setError(''); setSent('');
    try {
      const result = await call<{ message: string }>(null, '/auth/forgot-password', 'POST', { email });
      setSent(result.message);
    } catch (e) {
      setError(errorText(e));
    }
  }

  if (forgot) {
    return (
      <section className="panel stack max-w-md">
        <h2 className="text-xl font-semibold">Reset your password</h2>
        <p className="muted">Enter the email you registered with. We will send a link that works once and expires in 30 minutes.</p>
        <div><label>Email</label><input value={email} onChange={e => setEmail(e.target.value)} type="email" /></div>
        {error && <div className="error">{error}</div>}
        {sent && <div className="notice">{sent}</div>}
        <div className="row">
          <button onClick={sendReset} disabled={!email.trim()}>Send reset link</button>
          <button className="secondary" onClick={() => { setForgot(false); setError(''); setSent(''); }}>Back to sign in</button>
        </div>
      </section>
    );
  }

  return (
    <section className="panel stack max-w-md">
      <h2 className="text-xl font-semibold">Sign in</h2>
      <p className="muted">Manage your APIs, keys, limits and traffic. Passwords need at least 10 characters.</p>
      <div><label>Email</label><input value={email} onChange={e => setEmail(e.target.value)} type="email" /></div>
      <div><label>Password</label><input value={password} onChange={e => setPassword(e.target.value)} type="password" /></div>
      {error && <div className="error">{error}</div>}
      <div className="row">
        <button onClick={() => submit('login')}>Log in</button>
        <button className="secondary" onClick={() => submit('register')}>Register</button>
      </div>
      <button className="secondary small" onClick={() => { setForgot(true); setError(''); }}>Forgot password?</button>
    </section>
  );
}
