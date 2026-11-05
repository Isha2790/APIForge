'use client';
import { useState } from 'react';
import Auth from './components/Auth';
import Dashboard from './components/Dashboard';

export default function Home() {
  const [token, setToken] = useState<string | null>(null);   // kept in memory only: a refresh signs you out
  return (
    <main className="max-w-6xl mx-auto p-6">
      <header className="flex items-center justify-between mb-6">
        <h1 className="text-3xl font-bold">APIForge</h1>
        {token && <button className="secondary" onClick={() => setToken(null)}>Sign out</button>}
      </header>
      {token ? <Dashboard token={token} /> : <Auth onToken={setToken} />}
    </main>
  );
}
