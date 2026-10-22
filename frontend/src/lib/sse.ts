import { useEffect, useRef, useState } from "react";

export type SseStatus = "connecting" | "open" | "closed";

/**
 * Hook que mantiene un EventSource y dispara `onMessage` por cada evento
 * recibido en el canal `eventName`. Reconecta automáticamente si el browser
 * cierra la conexión.
 */
export function useEventStream<T>(url: string, eventName: string, onMessage: (data: T) => void) {
  const [status, setStatus] = useState<SseStatus>("connecting");
  const handlerRef = useRef(onMessage);
  handlerRef.current = onMessage;

  useEffect(() => {
    let es: EventSource | null = null;
    let cancelled = false;

    function connect() {
      if (cancelled) return;
      setStatus("connecting");
      es = new EventSource(url);
      es.onopen = () => setStatus("open");
      es.onerror = () => {
        setStatus("closed");
        es?.close();
        if (!cancelled) setTimeout(connect, 2000);
      };
      es.addEventListener(eventName, (ev) => {
        try {
          handlerRef.current(JSON.parse((ev as MessageEvent).data) as T);
        } catch {
          /* payload mal formado, ignoramos */
        }
      });
    }

    connect();
    return () => {
      cancelled = true;
      es?.close();
    };
  }, [url, eventName]);

  return status;
}
