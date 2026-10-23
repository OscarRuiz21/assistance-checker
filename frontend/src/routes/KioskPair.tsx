import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Check, Nfc } from "lucide-react";
import { ScreenShell } from "@/components/ScreenShell";
import { setKioskToken } from "@/lib/kiosk";

/**
 * Pantalla de emparejamiento del dispositivo. Un tenant_admin genera un
 * token en el panel admin (POST /api/auth/kiosk/pair) y lo pega aquí una
 * sola vez; se guarda en localStorage como credencial del dispositivo física
 * (no de un usuario) y de ahí en adelante api.ts manda
 * {@code Authorization: Kiosk <token>} en las llamadas del kiosko.
 */
export function KioskPair() {
  const navigate = useNavigate();
  const [token, setToken] = useState("");
  const [saved, setSaved] = useState(false);

  function save() {
    if (!token.trim()) return;
    setKioskToken(token.trim());
    setSaved(true);
    setTimeout(() => navigate("/k", { replace: true }), 1500);
  }

  if (saved) {
    return (
      <ScreenShell>
        <div className="flex flex-col items-center gap-4 text-center">
          <div className="flex h-16 w-16 items-center justify-center rounded-full bg-success/15 text-emerald-400">
            <Check className="h-8 w-8" />
          </div>
          <div className="text-lg font-medium">Dispositivo emparejado</div>
        </div>
      </ScreenShell>
    );
  }

  return (
    <ScreenShell>
      <div className="flex w-full max-w-sm flex-col items-center gap-6">
        <div className="flex h-16 w-16 items-center justify-center rounded-full bg-primary/10 ring-4 ring-primary/20">
          <Nfc className="h-8 w-8 text-primary" />
        </div>
        <div className="text-center">
          <div className="text-xl font-medium">Emparejar este kiosko</div>
          <div className="mt-1 text-sm text-muted-foreground">
            Pega el token generado por un administrador en el panel admin
          </div>
        </div>
        <textarea
          value={token}
          onChange={(e) => setToken(e.target.value)}
          rows={3}
          placeholder="Token de pairing…"
          className="w-full resize-none rounded-2xl border border-border bg-card px-4 py-3 text-sm focus:border-primary focus:outline-none"
        />
        <button
          onClick={save}
          disabled={!token.trim()}
          className="touch-target w-full rounded-2xl bg-primary px-5 py-4 font-semibold text-primary-foreground transition active:scale-95 disabled:opacity-30"
        >
          Guardar y continuar
        </button>
      </div>
    </ScreenShell>
  );
}
