import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { ArrowLeft, Loader2 } from "lucide-react";
import { ApiError, api } from "@/lib/api";
import type { EmployeeView, EventType } from "@/lib/types";
import { eventTypeDb } from "@/lib/types";
import { ScreenShell } from "@/components/ScreenShell";
import { AvatarInitials } from "@/components/AvatarInitials";
import { PinPad } from "@/components/PinPad";
import { SuccessOverlay } from "@/components/SuccessOverlay";
import { EventTypePicker } from "@/components/EventTypePicker";
import { suggestEventType } from "@/lib/eventSuggestion";
import { beepError, beepSuccess, vibrate } from "@/lib/feedback";

const SUCCESS_REDIRECT_MS = 2800;

const fmtTime = new Intl.DateTimeFormat("es-MX", {
  hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "America/Mexico_City",
});

const TYPE_LABEL: Record<EventType, string> = {
  CHECK_IN: "Entrada",
  CHECK_OUT: "Salida",
  LUNCH_OUT: "Sale a comer",
  LUNCH_IN: "Vuelve de comer",
};

export function NfcEntry() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const uid = params.get("u") ?? "";

  const [employee, setEmployee] = useState<EmployeeView | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [pinError, setPinError] = useState(false);
  const [pinErrorMsg, setPinErrorMsg] = useState<string | null>(null);
  const [success, setSuccess] = useState<{ name: string; time: string; type: EventType } | null>(null);
  const [selectedType, setSelectedType] = useState<EventType | null>(null);

  const suggestion = useMemo(() => suggestEventType(), []);

  useEffect(() => {
    if (!uid) {
      navigate("/k/error?reason=missing-uid", { replace: true });
      return;
    }
    // El UID de tarjeta es una credencial (equivalente a un PAN corto) y NO
    // debe quedar en el historial del browser/logs del navegador más tiempo
    // del necesario para leerlo una vez. Lo limpiamos de la URL visible sin
    // disparar otra navegación (replaceState no re-renderiza la ruta).
    window.history.replaceState(null, "", "/k");
    api.employeeByCard(uid)
      .then(setEmployee)
      .catch((e: ApiError) => {
        if (e.code === "CARD_NOT_FOUND") {
          navigate(`/k/error?reason=card-not-found&uid=${encodeURIComponent(uid)}`, { replace: true });
        } else {
          setLoadError(e.message);
        }
      });
  }, [uid, navigate]);

  async function submitPin(pin: string) {
    if (!employee || !selectedType) return;
    setSubmitting(true);
    setPinError(false);
    setPinErrorMsg(null);
    try {
      await api.createEvent({
        cardUid: uid,
        eventType: eventTypeDb[selectedType],
        captureMethod: "pin",
        pin,
        deviceTime: new Date().toISOString(),
      });
      beepSuccess();
      vibrate([60, 40, 60]);
      setSuccess({ name: employee.fullName, time: fmtTime.format(new Date()), type: selectedType });
      setTimeout(() => navigate("/k", { replace: true }), SUCCESS_REDIRECT_MS);
    } catch (e) {
      beepError();
      vibrate(160);
      setPinError(true);
      const err = e instanceof ApiError ? e : null;
      if (err?.code === "NFC_BLOCKED" || err?.code === "NFC_LOST") {
        navigate(`/k/error?reason=${err.code.toLowerCase()}&detail=${encodeURIComponent(err.message)}`, {
          replace: true,
        });
        return;
      }
      setPinErrorMsg(err?.message ?? "Error al registrar");
      setSubmitting(false);
      setTimeout(() => setPinError(false), 500);
    }
  }

  if (success) {
    return <SuccessOverlay name={`${success.name} · ${TYPE_LABEL[success.type]}`} time={success.time} />;
  }

  if (loadError) {
    return (
      <ScreenShell>
        <div className="text-center text-destructive">
          <div className="text-xl">No pudimos buscar la tarjeta</div>
          <div className="mt-2 text-sm text-muted-foreground">{loadError}</div>
          <button
            onClick={() => navigate("/k", { replace: true })}
            className="mt-6 rounded-xl bg-secondary px-5 py-2"
          >
            Volver
          </button>
        </div>
      </ScreenShell>
    );
  }

  if (!employee) {
    return (
      <ScreenShell>
        <Loader2 className="h-10 w-10 animate-spin text-muted-foreground" />
        <div className="mt-3 text-sm text-muted-foreground">Buscando tarjeta…</div>
      </ScreenShell>
    );
  }

  return (
    <ScreenShell>
      <button
        onClick={() => navigate("/k", { replace: true })}
        className="absolute left-4 top-16 flex items-center gap-1 text-sm text-muted-foreground"
      >
        <ArrowLeft className="h-4 w-4" /> Cancelar
      </button>

      <div className="flex w-full max-w-sm flex-col items-center gap-6 animate-fade-in">
        <AvatarInitials name={employee.fullName} size="lg" />
        <div className="text-center">
          <div className="text-xl font-semibold leading-tight">{employee.fullName}</div>
          <div className="mt-1 text-xs text-muted-foreground">{employee.employeeNumber}</div>
        </div>

        {!selectedType ? (
          <>
            <div className="text-sm font-medium text-muted-foreground">¿Qué vas a registrar?</div>
            <EventTypePicker
              selected={null}
              suggestion={suggestion}
              onSelect={setSelectedType}
            />
          </>
        ) : (
          <>
            <button
              type="button"
              onClick={() => setSelectedType(null)}
              className="rounded-full border border-border bg-card px-4 py-1.5 text-xs font-medium text-card-foreground"
            >
              {TYPE_LABEL[selectedType]} · cambiar
            </button>
            <div className="flex flex-col items-center gap-2">
              <div className="text-sm text-muted-foreground">Ingresa tu PIN</div>
              <PinPad onComplete={submitPin} disabled={submitting} hasError={pinError} />
              {pinErrorMsg && <div className="text-sm text-destructive">{pinErrorMsg}</div>}
            </div>
          </>
        )}
      </div>
    </ScreenShell>
  );
}
