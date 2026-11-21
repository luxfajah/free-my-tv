import React, { useState, useEffect, useRef, useCallback } from "react";
import { motion, AnimatePresence } from "framer-motion";

export type HudPhase = "idle" | "expanded" | "compact";
export type OvershootDir = "top" | "bottom" | null;

/**
 * Apple tvOS / iOS / visionOS Liquid Glass Vertical Volume HUD
 * Built with React, Tailwind CSS, and Framer Motion.
 */
export const AppleLiquidGlassVolume: React.FC = () => {
  const [volume, setVolume] = useState<number>(65); // 0 - 100
  const [isMuted, setIsMuted] = useState<boolean>(false);
  const [phase, setPhase] = useState<HudPhase>("expanded");
  const [overshoot, setOvershoot] = useState<OvershootDir>(null);

  const pressCountRef = useRef<number>(0);
  const compactTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const hideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const overshootTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const trackRef = useRef<HTMLDivElement | null>(null);
  const isDraggingRef = useRef<boolean>(false);

  const effectiveVolume = isMuted ? 0 : volume;

  const clearTimers = useCallback(() => {
    if (compactTimerRef.current) clearTimeout(compactTimerRef.current);
    if (hideTimerRef.current) clearTimeout(hideTimerRef.current);
  }, []);

  const scheduleLifecycleTimers = useCallback(() => {
    clearTimers();
    // Auto-narrow to compact state (36px) after 1.2s
    compactTimerRef.current = setTimeout(() => {
      if (!isDraggingRef.current) {
        setPhase((prev) => (prev === "expanded" ? "compact" : prev));
      }
    }, 1200);

    // Auto-hide to idle state after 2.5s of inactivity
    hideTimerRef.current = setTimeout(() => {
      if (!isDraggingRef.current) {
        setPhase("idle");
        pressCountRef.current = 0;
      }
    }, 2500);
  }, [clearTimers]);

  const triggerOvershoot = useCallback((dir: "top" | "bottom") => {
    if (overshootTimerRef.current) clearTimeout(overshootTimerRef.current);
    setOvershoot(dir);
    overshootTimerRef.current = setTimeout(() => {
      setOvershoot(null);
    }, 180);
  }, []);

  const adjustVolume = useCallback(
    (delta: number) => {
      const wasIdle = phase === "idle";
      pressCountRef.current = wasIdle ? 1 : pressCountRef.current + 1;

      // 1st click from idle -> Expanded (56px); 2nd+ consecutive adjustment -> Compact (28px)
      if (wasIdle || pressCountRef.current === 1) {
        setPhase("expanded");
      } else if (pressCountRef.current >= 2) {
        setPhase("compact");
      }

      setVolume((prev) => {
        const base = isMuted && delta > 0 ? prev : prev;
        const next = base + delta;

        if (next > 100) {
          triggerOvershoot("top");
          return 100;
        }
        if (next < 0) {
          triggerOvershoot("bottom");
          return 0;
        }
        return next;
      });

      if (isMuted && delta > 0) {
        setIsMuted(false);
      }

      scheduleLifecycleTimers();
    },
    [phase, isMuted, triggerOvershoot, scheduleLifecycleTimers]
  );

  const toggleMute = useCallback(() => {
    pressCountRef.current = 1;
    setPhase("expanded"); // Keep expanded on mute toggle so speaker glyph change is highlighted
    setIsMuted((prev) => !prev);
    scheduleLifecycleTimers();
  }, [scheduleLifecycleTimers]);

  // Keyboard (ArrowUp / ArrowDown / M) support
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "ArrowUp" || e.key === "+" || e.key === "=") {
        e.preventDefault();
        adjustVolume(6);
      } else if (e.key === "ArrowDown" || e.key === "-") {
        e.preventDefault();
        adjustVolume(-6);
      } else if (e.key.toLowerCase() === "m") {
        e.preventDefault();
        toggleMute();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [adjustVolume, toggleMute]);

  // Initial mount lifecycle timer
  useEffect(() => {
    scheduleLifecycleTimers();
    return () => clearTimers();
  }, [scheduleLifecycleTimers, clearTimers]);

  // Direct pointer scrubbing on the vertical capsule
  const handlePointerScrub = (clientY: number) => {
    if (!trackRef.current) return;
    const rect = trackRef.current.getBoundingClientRect();
    const rawRatio = 1 - (clientY - rect.top) / rect.height;

    if (rawRatio > 1) {
      setVolume(100);
      setIsMuted(false);
      setOvershoot("top");
    } else if (rawRatio < 0) {
      setVolume(0);
      setOvershoot("bottom");
    } else {
      setIsMuted(false);
      setOvershoot(null);
      setVolume(Math.round(rawRatio * 100));
    }
  };

  // Dynamic color inversion: flips to dark when the white fill passes over the bottom icon (> 18%)
  const isIconCoveredByWhite = effectiveVolume > 18;
  const isCompact = phase === "compact";
  const isVisible = phase !== "idle";

  return (
    <div
      className="relative flex min-h-[540px] w-full select-none items-center justify-center overflow-hidden rounded-3xl bg-[#060811] p-8 font-sans text-white shadow-2xl"
      onWheel={(e) => {
        e.preventDefault();
        adjustVolume(e.deltaY < 0 ? 5 : -5);
      }}
    >
      {/* 1. Ambient Blurred Colorful Shapes (to showcase visionOS / tvOS Liquid Glass refraction) */}
      <div className="pointer-events-none absolute inset-0 overflow-hidden">
        <div className="absolute -right-12 top-10 h-64 w-64 rounded-full bg-cyan-500/45 blur-[80px]" />
        <div className="absolute right-8 bottom-12 h-72 w-72 rounded-full bg-fuchsia-500/40 blur-[90px]" />
        <div className="absolute right-24 top-1/3 h-56 w-56 rounded-full bg-amber-400/35 blur-[75px]" />
        <div className="absolute left-12 top-16 h-72 w-72 rounded-full bg-blue-600/25 blur-[100px]" />
        <div className="absolute left-1/4 bottom-8 h-64 w-64 rounded-full bg-emerald-500/20 blur-[95px]" />
      </div>

      {/* 2. Center Interactive Apple TV Remote Simulator */}
      <div className="relative z-10 flex flex-col items-center gap-6">
        <div className="text-center">
          <span className="inline-block rounded-full border border-white/15 bg-white/10 px-3 py-1 text-[11px] font-semibold uppercase tracking-widest text-cyan-300 backdrop-blur-md">
            Apple tvOS / visionOS • Liquid Glass HUD
          </span>
          <h2 className="mt-2 text-2xl font-semibold tracking-tight text-white">
            Siri Remote Simulator
          </h2>
          <p className="mt-1 text-xs text-white/60">
            Use os botões abaixo, a roda do mouse (scroll) ou as setas ↑ / ↓ do teclado
          </p>
        </div>

        {/* Aluminum / Glass Siri Remote Pod */}
        <div className="flex flex-col items-center gap-4 rounded-[36px] border border-white/20 bg-white/10 p-6 shadow-[0_24px_60px_rgba(0,0,0,0.6),inset_0_1px_1px_rgba(255,255,255,0.3)] backdrop-blur-2xl">
          {/* Volume Rocker (+ / -) */}
          <div className="flex flex-col overflow-hidden rounded-full border border-white/25 bg-black/40 shadow-[inset_0_1px_2px_rgba(255,255,255,0.2)]">
            <button
              type="button"
              onClick={() => adjustVolume(8)}
              aria-label="Aumentar Volume"
              className="flex h-16 w-14 items-center justify-center text-2xl font-light text-white transition active:scale-95 active:bg-white/20 hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-cyan-400"
            >
              +
            </button>
            <div className="h-px w-full bg-white/15" />
            <button
              type="button"
              onClick={() => adjustVolume(-8)}
              aria-label="Diminuir Volume"
              className="flex h-16 w-14 items-center justify-center text-2xl font-light text-white transition active:scale-95 active:bg-white/20 hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-cyan-400"
            >
              −
            </button>
          </div>

          {/* Mute Button */}
          <button
            type="button"
            onClick={toggleMute}
            aria-label={isMuted ? "Ativar Som" : "Silenciar"}
            className={`flex h-14 w-14 items-center justify-center rounded-full border transition active:scale-90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-cyan-400 ${
              isMuted
                ? "border-white/50 bg-white/90 text-neutral-900 shadow-[0_0_20px_rgba(255,255,255,0.4)]"
                : "border-white/25 bg-black/40 text-white hover:bg-white/15"
            }`}
          >
            <SpeakerGlyph level={effectiveVolume} isMuted={isMuted} className="h-5 w-5" />
          </button>
        </div>

        {/* Live Telemetry Pill */}
        <div className="flex items-center gap-3 rounded-full border border-white/15 bg-black/35 px-4 py-1.5 text-xs text-white/80 backdrop-blur-xl">
          <span className="font-mono tabular-nums">
            Volume: <strong className="text-white">{effectiveVolume}%</strong>
          </span>
          <span className="text-white/25">•</span>
          <span>
            Estado:{" "}
            <strong className="uppercase text-cyan-300">
              {phase === "idle"
                ? "Oculto (Idle)"
                : phase === "expanded"
                ? "Expandido (56px)"
                : "Compacto (36px)"}
            </strong>
          </span>
        </div>
      </div>

      {/* 3. Native Apple Liquid Glass Vertical Volume Bar (Floating on the Right Edge) */}
      <div className=" absolute right-7 top-1/2 z-20 -translate-y-1/2">
        <AnimatePresence>
          {isVisible && (
            <motion.div
              ref={trackRef}
              key="apple-liquid-glass-hud"
              role="slider"
              aria-label="Volume"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={effectiveVolume}
              onPointerDown={(e) => {
                isDraggingRef.current = true;
                (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
                setPhase("expanded");
                clearTimers();
                handlePointerScrub(e.clientY);
              }}
              onPointerMove={(e) => {
                if (!isDraggingRef.current) return;
                handlePointerScrub(e.clientY);
              }}
              onPointerUp={() => {
                isDraggingRef.current = false;
                setOvershoot(null);
                scheduleLifecycleTimers();
              }}
              initial={{ opacity: 0, scale: 0.9, x: 22, width: 56 }}
              animate={{
                opacity: 1,
                x: 0,
                width: isCompact ? 36 : 56,
                scaleY: overshoot ? 1.06 : 1,
                scaleX: overshoot ? 0.95 : 1,
                y: overshoot === "top" ? -5 : overshoot === "bottom" ? 5 : 0,
              }}
              exit={{
                opacity: 0,
                scale: 0.9,
                x: 22,
                transition: { duration: 0.28, ease: [0.32, 0.72, 0, 1] },
              }}
              transition={{
                type: "spring",
                stiffness: 380,
                damping: 28,
                mass: 0.8,
              }}
              style={{
                height: 180,
                transformOrigin:
                  overshoot === "top"
                    ? "bottom center"
                    : overshoot === "bottom"
                    ? "top center"
                    : "center center",
              }}
              className="relative cursor-ns-resize overflow-hidden rounded-full border border-white/30 bg-white/15 backdrop-blur-2xl shadow-[0_18px_45px_rgba(0,0,0,0.45),inset_0_1px_1.5px_rgba(255,255,255,0.55),inset_0_-1px_2px_rgba(0,0,0,0.3)]"
            >
              {/* Pure White Internal Fill (bg-white/90) rising from bottom to top */}
              <motion.div
                className="pointer-events-none absolute inset-x-0 bottom-0 bg-white/90 shadow-[0_-1px_6px_rgba(255,255,255,0.5)]"
                initial={false}
                animate={{ height: `${effectiveVolume}%` }}
                transition={{
                  type: "spring",
                  stiffness: 420,
                  damping: 32,
                  mass: 0.7,
                }}
              />

              {/* Specular Cylindrical Glass Highlight Overlay */}
              <div className="pointer-events-none absolute inset-0 rounded-full bg-gradient-to-br from-white/25 via-transparent to-black/15" />

              {/* Dynamic Minimalist Speaker Icon at Bottom Base */}
              <motion.div
                initial={false}
                animate={{
                  scale: isCompact ? 0.8 : 1,
                  opacity: isCompact ? 0.9 : 1,
                }}
                transition={{ type: "spring", stiffness: 380, damping: 28 }}
                className={`pointer-events-none absolute inset-x-0 bottom-3.5 flex items-center justify-center transition-colors duration-150 ${
                  isIconCoveredByWhite ? "text-neutral-900" : "text-white/90"
                }`}
              >
                <SpeakerGlyph
                  level={effectiveVolume}
                  isMuted={isMuted || effectiveVolume === 0}
                  className="h-5 w-5"
                />
              </motion.div>
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </div>
  );
};

/**
 * Dynamic Apple SF Symbol Speaker Vector Icon (Mute, Low, Medium, High)
 */
const SpeakerGlyph: React.FC<{
  level: number;
  isMuted: boolean;
  className?: string;
}> = ({ level, isMuted, className = "h-5 w-5" }) => {
  const showMute = isMuted || level === 0;
  const showWave1 = !showMute && level > 0;
  const showWave2 = !showMute && level >= 34;
  const showWave3 = !showMute && level >= 67;

  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
       className={className}
      xmlns="http://www.w3.org/2000/svg"
    >
      {/* Speaker Cone */}
      <path
        d="M11.25 4.75L6.75 8.5H3.75C3.19772 8.5 2.75 8.94772 2.75 9.5V14.5C2.75 15.0523 3.19772 15.5 3.75 15.5H6.75L11.25 19.25C11.9 19.79 12.75 19.33 12.75 18.48V5.52C12.75 4.67 11.9 4.21 11.25 4.75Z"
        fill="currentColor"
      />
      {/* Mute Slash */}
      {showMute && (
        <path
          d="M4.25 4.25L19.75 19.75"
          stroke="currentColor"
          strokeWidth="2.1"
          strokeLinecap="round"
        />
      )}
      {/* Low Wave */}
      {showWave1 && (
        <path
          d="M15.5 9.5C16.6 10.6 16.6 13.4 15.5 14.5"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
        />
      )}
      {/* Medium Wave */}
      {showWave2 && (
        <path
          d="M18 7.25C20.1 9.45 20.1 14.55 18 16.75"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
        />
      )}
      {/* High Wave */}
      {showWave3 && (
        <path
          d="M20.5 5C23.6 8.3 23.6 15.7 20.5 19"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
        />
      )}
    </svg>
  );
};

export default AppleLiquidGlassVolume;
