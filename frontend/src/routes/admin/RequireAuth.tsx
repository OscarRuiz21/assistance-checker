import { type ReactNode, useEffect, useState } from "react";
import { Navigate } from "react-router-dom";
import { Loader2 } from "lucide-react";
import { isAuthenticated, refreshSilently } from "@/lib/auth";

/**
 * Guard de rutas admin. Al montar (p.ej. recarga de página) no hay access
 * token en memoria, así que intenta un refresh silencioso contra la cookie
 * httpOnly antes de decidir si redirige a /admin/login.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  // Si ya hay un access token vigente en memoria al montar, el estado
  // inicial ya refleja eso — no hace falta volver a setearlo en el efecto
  // (evita un setState síncrono dentro del efecto).
  const [authenticatedAtMount] = useState(isAuthenticated);
  const [checked, setChecked] = useState(authenticatedAtMount);
  const [ok, setOk] = useState(authenticatedAtMount);

  useEffect(() => {
    if (authenticatedAtMount) return;
    refreshSilently().then((success) => {
      setOk(success);
      setChecked(true);
    });
  }, [authenticatedAtMount]);

  if (!checked) {
    return (
      <div className="flex h-full min-h-screen items-center justify-center bg-background">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (!ok) {
    return <Navigate to="/admin/login" replace />;
  }

  return <>{children}</>;
}
