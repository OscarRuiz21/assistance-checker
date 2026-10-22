import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { AlertTriangle, XCircle } from "lucide-react";
import { ScreenShell } from "@/components/ScreenShell";

const REASONS: Record<string, { title: string; subtitle: string }> = {
  "card-not-found": {
    title: "Tarjeta no registrada",
    subtitle: "Esta tarjeta no está asignada a ningún empleado activo. Acude con tu supervisor.",
  },
  "nfc_blocked": {
    title: "Tarjeta bloqueada",
    subtitle: "Esta tarjeta fue reportada y ya no puede usarse.",
  },
  "nfc_lost": {
    title: "Tarjeta reportada perdida",
    subtitle: "Acude con tu supervisor para obtener un reemplazo.",
  },
  "missing-uid": {
    title: "URL inválida",
    subtitle: "No se recibió el identificador de la tarjeta.",
  },
};

const REDIRECT_SECONDS = 6;

export function ErrorScreen() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const reason = params.get("reason") ?? "unknown";
  const detail = params.get("detail");
  const info = REASONS[reason] ?? {
    title: "Algo salió mal",
    subtitle: detail ?? "Inténtalo de nuevo o pide ayuda al supervisor.",
  };

  const [secs, setSecs] = useState(REDIRECT_SECONDS);
  useEffect(() => {
    if (secs <= 0) {
      navigate("/k", { replace: true });
      return;
    }
    const id = setTimeout(() => setSecs((s) => s - 1), 1000);
    return () => clearTimeout(id);
  }, [secs, navigate]);

  return (
    <ScreenShell>
      <div className="flex max-w-sm flex-col items-center gap-6 text-center animate-fade-in">
        <div className="flex h-24 w-24 items-center justify-center rounded-full bg-destructive/10 ring-4 ring-destructive/20">
          {reason === "card-not-found" || reason === "missing-uid" ? (
            <AlertTriangle className="h-12 w-12 text-destructive" />
          ) : (
            <XCircle className="h-12 w-12 text-destructive" />
          )}
        </div>
        <div>
          <div className="text-2xl font-semibold">{info.title}</div>
          <div className="mt-2 text-base text-muted-foreground">{info.subtitle}</div>
          {detail && reason !== "nfc_blocked" && reason !== "nfc_lost" && (
            <div className="mt-3 text-xs text-muted-foreground/70">{detail}</div>
          )}
        </div>
        <button
          onClick={() => navigate("/k", { replace: true })}
          className="touch-target rounded-2xl bg-secondary px-6 py-3 font-medium text-secondary-foreground"
        >
          Volver al inicio · {secs}s
        </button>
      </div>
    </ScreenShell>
  );
}
