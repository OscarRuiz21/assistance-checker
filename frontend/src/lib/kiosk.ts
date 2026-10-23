// Credencial del kiosko: un token opaco guardado en localStorage del
// dispositivo (no es secreto de un usuario humano, es la credencial del
// aparato físico — equivalente a cómo un POS guarda su propia llave). Se
// obtiene una vez en /k/pair pegando el token que generó un admin en
// /api/auth/kiosk/pair.
const STORAGE_KEY = "kiosk_device_token";

export function getKioskToken(): string | null {
  return localStorage.getItem(STORAGE_KEY);
}

export function setKioskToken(token: string): void {
  localStorage.setItem(STORAGE_KEY, token);
}

export function clearKioskToken(): void {
  localStorage.removeItem(STORAGE_KEY);
}

export function isKioskPaired(): boolean {
  return getKioskToken() != null;
}
