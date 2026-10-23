import { Nfc, KeyRound } from "lucide-react";
import { Link } from "react-router-dom";
import { Clock } from "@/components/Clock";
import { ScreenShell } from "@/components/ScreenShell";

export function WaitScreen() {
  return (
    <ScreenShell>
      <div className="flex w-full max-w-sm flex-col items-center gap-12">
        <Clock />

        <div className="flex flex-col items-center gap-4 text-center">
          <div className="flex h-20 w-20 items-center justify-center rounded-full bg-primary/10 ring-4 ring-primary/20">
            <Nfc className="h-12 w-12 text-primary" />
          </div>
          <div className="text-xl font-medium text-foreground">Acerque su tarjeta</div>
          <div className="text-sm text-muted-foreground">o utilice captura manual</div>
        </div>

        <Link
          to="/k/manual"
          className="touch-target flex w-full items-center justify-center gap-3 rounded-2xl border border-border bg-card px-6 py-4 text-base font-medium text-card-foreground transition active:scale-95"
        >
          <KeyRound className="h-5 w-5" />
          Captura manual con número y PIN
        </Link>

        <Link to="/k/pair" className="text-xs text-muted-foreground/60 underline-offset-2 hover:underline">
          Emparejar este dispositivo
        </Link>
      </div>
    </ScreenShell>
  );
}
