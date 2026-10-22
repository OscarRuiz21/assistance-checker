import { CheckCircle2 } from "lucide-react";

export function SuccessOverlay({ name, time }: { name: string; time: string }) {
  return (
    <div className="fixed inset-0 z-50 flex flex-col items-center justify-center bg-success/95 text-success-foreground animate-fade-in">
      <CheckCircle2 className="mb-4 h-32 w-32 animate-scale-in" strokeWidth={1.5} />
      <div className="text-3xl font-semibold">¡Bienvenida!</div>
      <div className="mt-2 max-w-xs px-6 text-center text-xl">{name}</div>
      <div className="mt-6 text-2xl font-light tabular-nums">{time}</div>
    </div>
  );
}
