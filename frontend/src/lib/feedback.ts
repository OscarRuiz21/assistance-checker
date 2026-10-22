// Sonido + vibración para feedback de captura.
// Web Audio (sin assets) + Vibration API (Safari iOS no la soporta, falla silenciosa).

let audioCtx: AudioContext | null = null;

function ctx(): AudioContext {
  if (!audioCtx) {
    const Ctor = (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
      ?? window.AudioContext;
    audioCtx = new Ctor();
  }
  return audioCtx;
}

export function beepSuccess() {
  try {
    const c = ctx();
    if (c.state === "suspended") void c.resume();
    const t = c.currentTime;
    const osc = c.createOscillator();
    const gain = c.createGain();
    osc.frequency.setValueAtTime(880, t);
    osc.frequency.linearRampToValueAtTime(1320, t + 0.12);
    gain.gain.setValueAtTime(0.0001, t);
    gain.gain.exponentialRampToValueAtTime(0.25, t + 0.01);
    gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.22);
    osc.connect(gain).connect(c.destination);
    osc.start(t);
    osc.stop(t + 0.25);
  } catch {
    /* navigator sin audio, ignoramos */
  }
}

export function beepError() {
  try {
    const c = ctx();
    if (c.state === "suspended") void c.resume();
    const t = c.currentTime;
    const osc = c.createOscillator();
    const gain = c.createGain();
    osc.type = "square";
    osc.frequency.setValueAtTime(220, t);
    gain.gain.setValueAtTime(0.0001, t);
    gain.gain.exponentialRampToValueAtTime(0.18, t + 0.01);
    gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.3);
    osc.connect(gain).connect(c.destination);
    osc.start(t);
    osc.stop(t + 0.32);
  } catch {
    /* */
  }
}

export function vibrate(pattern: number | number[] = 80) {
  if ("vibrate" in navigator) navigator.vibrate(pattern);
}
