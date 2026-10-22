import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

export function ScreenShell({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={cn("flex min-h-full flex-col bg-background", className)}>
      <header className="flex items-center justify-between px-6 pt-6">
        <div className="text-sm font-medium tracking-wide text-muted-foreground">
          Empresa Demo SA · Planta Querétaro
        </div>
        <div className="text-xs text-muted-foreground/70">v0.1</div>
      </header>
      <main className="flex flex-1 flex-col items-center justify-center px-6 py-8">{children}</main>
    </div>
  );
}
