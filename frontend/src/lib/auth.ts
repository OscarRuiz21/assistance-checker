// Sesión del admin: el access token SOLO vive en memoria (nunca localStorage
// ni sessionStorage) para que no sea robable por un XSS que lea storage. El
// refresh token vive en una cookie httpOnly+Secure+SameSite=Strict que este
// módulo nunca toca directamente — solo dispara /api/auth/refresh con
// credentials:"include" y el browser la manda sola.
import type { AuthResponse, AuthUser } from "./types";

let accessToken: string | null = null;
let accessTokenExpiresAt = 0; // epoch ms
let currentUser: AuthUser | null = null;
let refreshInFlight: Promise<boolean> | null = null;

const listeners = new Set<() => void>();

function notify() {
  listeners.forEach((l) => l());
}

export function subscribeAuth(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getAccessToken(): string | null {
  return accessToken;
}

export function getCurrentUser(): AuthUser | null {
  return currentUser;
}

export function isAuthenticated(): boolean {
  return accessToken != null && Date.now() < accessTokenExpiresAt;
}

function applySession(resp: AuthResponse) {
  accessToken = resp.accessToken;
  accessTokenExpiresAt = Date.now() + resp.expiresIn * 1000;
  currentUser = resp.user;
  notify();
}

function clearSession() {
  accessToken = null;
  accessTokenExpiresAt = 0;
  currentUser = null;
  notify();
}

async function parseAuthResponse(res: Response): Promise<AuthResponse> {
  if (!res.ok) {
    let message = res.statusText;
    try {
      const body = await res.json();
      message = body.message ?? message;
    } catch {
      /* sin body */
    }
    throw new Error(message);
  }
  return (await res.json()) as AuthResponse;
}

export async function login(email: string, password: string): Promise<AuthUser> {
  const res = await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ email, password }),
  });
  const data = await parseAuthResponse(res);
  applySession(data);
  return data.user;
}

export async function logout(): Promise<void> {
  try {
    await fetch("/api/auth/logout", { method: "POST", credentials: "include" });
  } finally {
    clearSession();
  }
}

/** Intenta refrescar el access token usando la cookie httpOnly. */
export async function refreshSilently(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight;
  refreshInFlight = (async () => {
    try {
      const res = await fetch("/api/auth/refresh", { method: "POST", credentials: "include" });
      if (!res.ok) {
        clearSession();
        return false;
      }
      const data = await parseAuthResponse(res);
      applySession(data);
      return true;
    } catch {
      clearSession();
      return false;
    } finally {
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

/** Devuelve un access token fresco, refrescando primero si está por expirar. */
export async function ensureFreshToken(): Promise<string | null> {
  if (accessToken && Date.now() < accessTokenExpiresAt - 5_000) {
    return accessToken;
  }
  const ok = await refreshSilently();
  return ok ? accessToken : null;
}
