import type { ReactNode } from "react";
import { Activity, Building2, LayoutDashboard, Users, Wifi, WifiOff } from "lucide-react";
import { NavLink } from "react-router-dom";
import { cn } from "@/lib/cn";
import type { SseStatus } from "@/lib/sse";

const NAV = [
  { to: "/admin", label: "Dashboard", Icon: LayoutDashboard, end: true },
  { to: "/admin/empleados", label: "Empleados", Icon: Users, end: false },
];

export function AdminShell({
  children,
  sseStatus,
}: {
  children: ReactNode;
  sseStatus: SseStatus;
}) {
  return (
    <div className="min-h-full bg-background">
      <header className="sticky top-0 z-10 border-b border-border bg-background/80 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-3">
          <div className="flex items-center gap-6">
            <div className="flex items-center gap-3">
              <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-primary/15 text-primary">
                <Building2 className="h-5 w-5" />
              </div>
              <div>
                <div className="text-sm font-semibold leading-tight">Asistencia · Admin</div>
                <div className="text-xs text-muted-foreground">Empresa Demo · Planta Querétaro</div>
              </div>
            </div>
            <nav className="ml-2 flex items-center gap-1">
              {NAV.map(({ to, label, Icon, end }) => (
                <NavLink
                  key={to}
                  to={to}
                  end={end}
                  className={({ isActive }) =>
                    cn(
                      "flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium transition",
                      isActive
                        ? "bg-secondary text-foreground"
                        : "text-muted-foreground hover:text-foreground",
                    )
                  }
                >
                  <Icon className="h-4 w-4" />
                  {label}
                </NavLink>
              ))}
            </nav>
          </div>
          <div
            className={cn(
              "flex items-center gap-2 rounded-full border px-3 py-1 text-xs",
              sseStatus === "open"
                ? "border-success/30 bg-success/10 text-emerald-300"
                : "border-amber-500/30 bg-amber-500/10 text-amber-300",
            )}
            title={sseStatus === "open" ? "Conectado en tiempo real" : "Reconectando…"}
          >
            {sseStatus === "open" ? (
              <Activity className="h-3.5 w-3.5 animate-pulse" />
            ) : sseStatus === "connecting" ? (
              <Wifi className="h-3.5 w-3.5" />
            ) : (
              <WifiOff className="h-3.5 w-3.5" />
            )}
            {sseStatus === "open" ? "En vivo" : sseStatus === "connecting" ? "Conectando…" : "Reconectando"}
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-6 py-8">{children}</main>
    </div>
  );
}
