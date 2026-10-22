import { useMemo, useState } from "react";
import { ArrowLeft, Loader2 } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { ApiError, api } from "@/lib/api";
import { ScreenShell } from "@/components/ScreenShell";
import { PinPad } from "@/components/PinPad";
import { SuccessOverlay } from "@/components/SuccessOverlay";
import { EventTypePicker, suggestEventType } from "@/components/EventTypePicker";
import { beepError, beepSuccess, vibrate } from "@/lib/feedback";
import type { EventType } from "@/lib/types";
import { eventTypeDb } from "@/lib/types";

const fmtTime = new Intl.DateTimeFormat("es-MX", {
  hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "America/Mexico_City",
});

const TYPE_LABEL: Record<EventType, string> = {
  CHECK_IN: "Entrada",
  CHECK_OUT: "Salida",
  LUNCH_OUT: "Sale a comer",
  LUNCH_IN: "Vuelve de comer",
};

type Stage = "number" | "type" | "pin";

export function ManualEntry() {
  const navigate = useNavigate();
  const [employeeNumber, setEmployeeNumber] = useState("");
  const [selectedType, setSelectedType] = useState<EventType | null>(null);
  const [stage, setStage] = useState<Stage>("number");
  const [submitting, setSubmitting] = useState(false);
  const [pinError, setPinError] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [success, setSuccess] = useState<{ name: string; time: string; type: EventType } | null>(null);

  const suggestion = useMemo(suggestEventType, []);

  async function submitPin(pin: string) {
    if (!selectedType) return;
    setSubmitting(true);
    setPinError(false);
    setErrorMsg(null);
    try {
      const ev = await api.createEvent({
        employeeNumber: employeeNumber.trim(),
        eventType: eventTypeDb[selectedType],
        captureMethod: "pin",
        pin,
        deviceTime: new Date().toISOString(),
      });
      beepSuccess();
      vibrate([60, 40, 60]);
      setSuccess({ name: ev.employeeName, time: fmtTime.format(new Date()), type: selectedType });
      setTimeout(() => navigate("/k", { replace: true }), 2800);
    } catch (e) {
      beepError();
      vibrate(160);
      setPinError(true);
      const err = e instanceof ApiError ? e : null;
      setErrorMsg(err?.message ?? "Error al registrar");
      setSubmitting(false);
      setTimeout(() => setPinError(false), 500);
    }
  }

  if (success) {
    return <SuccessOverlay name={`${success.name} · ${TYPE_LABEL[success.type]}`} time={success.time} />;
  }

  return (
    <ScreenShell>
      <button
        onClick={() => navigate("/k", { replace: true })}
        className="absolute left-4 top-16 flex items-center gap-1 text-sm text-muted-foreground"
      >
        <ArrowLeft className="h-4 w-4" /> Volver
      </button>

      {stage === "number" && (
        <div className="flex w-full max-w-sm flex-col items-center gap-6 animate-fade-in">
          <div className="text-xl font-medium">Número de empleado</div>
          <input
            value={employeeNumber}
            onChange={(e) => setEmployeeNumber(e.target.value.toUpperCase())}
            placeholder="EMP-00001"
            autoFocus
            className="w-full rounded-2xl border border-border bg-card px-5 py-4 text-center text-2xl tracking-wider focus:border-primary focus:outline-none"
          />
          <button
            disabled={!employeeNumber.trim()}
            onClick={() => setStage("type")}
            className="touch-target w-full rounded-2xl bg-primary px-5 py-4 font-semibold text-primary-foreground transition active:scale-95 disabled:opacity-30"
          >
            Continuar
          </button>
        </div>
      )}

      {stage === "type" && (
        <div className="flex w-full max-w-sm flex-col items-center gap-6 animate-fade-in">
          <div className="text-center">
            <div className="text-xs text-muted-foreground">Empleado</div>
            <div className="mt-1 text-xl font-medium">{employeeNumber}</div>
          </div>
          <div className="text-sm font-medium text-muted-foreground">¿Qué vas a registrar?</div>
          <EventTypePicker
            selected={null}
            suggestion={suggestion}
            onSelect={(t) => {
              setSelectedType(t);
              setStage("pin");
            }}
          />
        </div>
      )}

      {stage === "pin" && selectedType && (
        <div className="flex w-full max-w-sm flex-col items-center gap-6 animate-fade-in">
          <div className="text-center">
            <div className="text-xs text-muted-foreground">{employeeNumber}</div>
            <button
              type="button"
              onClick={() => setStage("type")}
              className="mt-2 rounded-full border border-border bg-card px-4 py-1.5 text-xs font-medium text-card-foreground"
            >
              {TYPE_LABEL[selectedType]} · cambiar
            </button>
          </div>
          {submitting ? (
            <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
          ) : (
            <PinPad onComplete={submitPin} disabled={submitting} hasError={pinError} />
          )}
          {errorMsg && <div className="text-sm text-destructive">{errorMsg}</div>}
        </div>
      )}
    </ScreenShell>
  );
}
