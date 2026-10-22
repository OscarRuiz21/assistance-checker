import { Delete } from "lucide-react";
import { useEffect, useState } from "react";
import { cn } from "@/lib/cn";

interface PinPadProps {
  length?: number;
  onComplete: (pin: string) => void;
  disabled?: boolean;
  hasError?: boolean;
}

export function PinPad({ length = 4, onComplete, disabled, hasError }: PinPadProps) {
  const [pin, setPin] = useState("");

  useEffect(() => {
    if (hasError) setPin("");
  }, [hasError]);

  useEffect(() => {
    if (pin.length === length) {
      onComplete(pin);
      setPin("");
    }
  }, [pin, length, onComplete]);

  const press = (digit: string) => {
    if (disabled) return;
    setPin((p) => (p.length < length ? p + digit : p));
  };

  const backspace = () => {
    if (disabled) return;
    setPin((p) => p.slice(0, -1));
  };

  const buttons: (string | "del")[] = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "del"];

  return (
    <div className={cn("flex flex-col items-center gap-6", hasError && "animate-shake")}>
      <div className="flex gap-3">
        {Array.from({ length }, (_, i) => (
          <div
            key={i}
            className={cn(
              "h-4 w-4 rounded-full transition-all",
              i < pin.length ? "bg-primary scale-110" : "bg-muted",
              hasError && i < pin.length && "bg-destructive",
            )}
          />
        ))}
      </div>
      <div className="grid grid-cols-3 gap-3">
        {buttons.map((b, idx) => {
          if (b === "") return <div key={idx} />;
          if (b === "del") {
            return (
              <button
                key={idx}
                type="button"
                onClick={backspace}
                disabled={disabled || pin.length === 0}
                className="digit-touch flex h-20 w-20 items-center justify-center rounded-2xl bg-secondary text-secondary-foreground transition active:scale-95 disabled:opacity-30"
                aria-label="Borrar"
              >
                <Delete className="h-7 w-7" />
              </button>
            );
          }
          return (
            <button
              key={idx}
              type="button"
              onClick={() => press(b)}
              disabled={disabled}
              className="digit-touch h-20 w-20 rounded-2xl bg-card text-3xl font-semibold text-card-foreground shadow transition active:scale-95 disabled:opacity-30"
            >
              {b}
            </button>
          );
        })}
      </div>
    </div>
  );
}
