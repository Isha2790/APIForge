export const BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8081';
export const GATEWAY = process.env.NEXT_PUBLIC_GATEWAY_URL || 'http://localhost:8080';

export type Role = 'OWNER' | 'ADMIN' | 'MEMBER';
export type Org = { id: string; name: string; role: Role };
export type Api = { id: string; organizationId: string; name: string; upstreamUrl: string; requestsPerMinute: number };
export type KeyView = {
  id: string; name: string; prefix: string; revoked: boolean; requestsPerMinute: number | null; dailyQuota: number;
  monthlyQuota: number; allowedMethods: string; pathPrefix: string; expiresAt: string | null; previousKeyValidUntil: string | null;
};
export type IssuedKey = { id: string; key: string; details: KeyView; note: string };
export type Member = { userId: string; email: string; role: Role };
export type Invitation = { id: string; email: string; role: Role; expiresAt: string };
export type Summary = {
  requests: number; success: number; clientErrors: number; serverErrors: number; rateLimited: number; quotaExceeded: number;
  scopeDenied: number; avgLatencyMs: number; maxLatencyMs: number; errorRate: number; rejectedRate: number;
};
export type Point = { hour: string; requests: number; success: number; clientErrors: number; serverErrors: number; rateLimited: number; quotaExceeded: number; avgLatencyMs: number };
export type ApiRow = { apiId: string; name: string; requests: number; rateLimited: number; quotaExceeded: number; serverErrors: number; avgLatencyMs: number };
export type KeyUsage = { dailyUsed: number; dailyLimit: number; monthlyUsed: number; monthlyLimit: number };

/** Calls the management API. Throws an Error whose message is the server's text. */
export async function call<T = unknown>(token: string | null, path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  if (!response.ok) {
    let message = text;
    try { message = JSON.parse(text).message || JSON.parse(text).error || text; } catch { /* plain text */ }
    throw new Error(`${response.status}: ${message || response.statusText}`);
  }
  return (text ? JSON.parse(text) : undefined) as T;
}

export const errorText = (e: unknown) => (e instanceof Error ? e.message : String(e));
