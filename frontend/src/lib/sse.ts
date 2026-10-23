import { useEffect, useRef, useState } from "react";
import { ensureFreshToken } from "./auth";

export type SseStatus = "connecting" | "open" | "closed";

/**
 * SSE autenticado. El {@code EventSource} nativo del browser no permite
 * mandar headers, así que no podríamos mandar
 * {@code Authorization: Bearer <jwt>}. En vez de inventar un esquema de
 * ticket de un solo uso en el backend, leemos el stream con {@code fetch} +
 * {@code ReadableStream} y parseamos manualmente el formato
 * {@code event: <name>\ndata: <json>\n\n} — así el endpoint
 * {@code /api/events/stream} queda protegido exactamente igual que cualquier
 * otro GET de admin (ver EventStreamController).
 */
export function useEventStream<T>(url: string, eventName: string, onMessage: (data: T) => void) {
  const [status, setStatus] = useState<SseStatus>("connecting");
  const handlerRef = useRef(onMessage);
  // No se debe mutar un ref durante el render; se actualiza en un efecto sin
  // dependencias (corre después de cada render) para que el stream siempre
  // llame a la versión más reciente de onMessage sin tener que reconectar.
  useEffect(() => {
    handlerRef.current = onMessage;
  });

  useEffect(() => {
    let cancelled = false;
    let abort: AbortController | null = null;

    async function connect() {
      if (cancelled) return;
      setStatus("connecting");
      abort = new AbortController();
      try {
        const token = await ensureFreshToken();
        const res = await fetch(url, {
          headers: {
            Accept: "text/event-stream",
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
          },
          signal: abort.signal,
        });
        if (!res.ok || !res.body) {
          throw new Error(`SSE HTTP ${res.status}`);
        }
        setStatus("open");

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        while (!cancelled) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });

          let sepIndex: number;
          while ((sepIndex = buffer.indexOf("\n\n")) !== -1) {
            const rawEvent = buffer.slice(0, sepIndex);
            buffer = buffer.slice(sepIndex + 2);
            processEvent(rawEvent);
          }
        }
        if (!cancelled) {
          setStatus("closed");
          scheduleReconnect();
        }
      } catch {
        if (!cancelled) {
          setStatus("closed");
          scheduleReconnect();
        }
      }
    }

    function processEvent(rawEvent: string) {
      let name: string | null = null;
      const dataLines: string[] = [];
      for (const line of rawEvent.split("\n")) {
        if (line.startsWith("event:")) name = line.slice(6).trim();
        else if (line.startsWith("data:")) dataLines.push(line.slice(5).trim());
      }
      if (name !== eventName || dataLines.length === 0) return;
      try {
        handlerRef.current(JSON.parse(dataLines.join("\n")) as T);
      } catch {
        /* payload mal formado, ignoramos */
      }
    }

    function scheduleReconnect() {
      if (!cancelled) setTimeout(connect, 2000);
    }

    connect();
    return () => {
      cancelled = true;
      abort?.abort();
    };
  }, [url, eventName]);

  return status;
}
