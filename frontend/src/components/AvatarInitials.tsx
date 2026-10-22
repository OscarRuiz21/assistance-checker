import { cn } from "@/lib/cn";

const COLORS = [
  "bg-blue-500", "bg-emerald-500", "bg-amber-500", "bg-rose-500",
  "bg-violet-500", "bg-pink-500", "bg-cyan-500", "bg-orange-500",
  "bg-teal-500", "bg-indigo-500",
];

function colorFromName(name: string) {
  let hash = 0;
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) | 0;
  return COLORS[Math.abs(hash) % COLORS.length];
}

function initials(name: string) {
  const parts = name.trim().split(/\s+/);
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

export function AvatarInitials({ name, size = "lg", className }: {
  name: string;
  size?: "md" | "lg" | "xl";
  className?: string;
}) {
  const sizes = { md: "h-14 w-14 text-lg", lg: "h-20 w-20 text-2xl", xl: "h-28 w-28 text-4xl" };
  return (
    <div
      className={cn(
        "flex items-center justify-center rounded-full font-semibold text-white shadow-lg ring-2 ring-white/20",
        sizes[size],
        colorFromName(name),
        className,
      )}
    >
      {initials(name)}
    </div>
  );
}
