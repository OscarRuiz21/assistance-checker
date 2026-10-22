import { useEffect, useState } from "react";

const fmtTime = new Intl.DateTimeFormat("es-MX", {
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hour12: false,
  timeZone: "America/Mexico_City",
});

const fmtDate = new Intl.DateTimeFormat("es-MX", {
  weekday: "long",
  day: "numeric",
  month: "long",
  timeZone: "America/Mexico_City",
});

export function Clock() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(id);
  }, []);
  return (
    <div className="text-center">
      <div className="text-7xl font-light tracking-tight tabular-nums sm:text-8xl">
        {fmtTime.format(now)}
      </div>
      <div className="mt-2 text-base capitalize text-muted-foreground">
        {fmtDate.format(now)}
      </div>
    </div>
  );
}
