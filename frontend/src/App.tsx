import { lazy, Suspense } from "react";
import { Loader2 } from "lucide-react";
import { BrowserRouter, Navigate, Route, Routes, useSearchParams } from "react-router-dom";
import { WaitScreen } from "@/routes/WaitScreen";
import { NfcEntry } from "@/routes/NfcEntry";
import { ManualEntry } from "@/routes/ManualEntry";
import { ErrorScreen } from "@/routes/ErrorScreen";

// Lazy-load admin para no inflar el bundle del kiosko (Recharts ~200KB).
const AdminDashboard = lazy(() => import("@/routes/admin/Dashboard"));
const AdminEmployees = lazy(() => import("@/routes/admin/Employees"));
const AdminEmployeeDetail = lazy(() => import("@/routes/admin/EmployeeDetail"));

function KioskoRoot() {
  const [params] = useSearchParams();
  return params.has("u") ? <NfcEntry /> : <WaitScreen />;
}

function AdminLoader() {
  return (
    <div className="flex h-full min-h-screen items-center justify-center bg-background">
      <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
    </div>
  );
}

function lazyAdmin(Component: React.LazyExoticComponent<React.ComponentType>) {
  return (
    <Suspense fallback={<AdminLoader />}>
      <Component />
    </Suspense>
  );
}

export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Navigate to="/k" replace />} />

        {/* Kiosko PWA — bundle base */}
        <Route path="/k" element={<KioskoRoot />} />
        <Route path="/k/manual" element={<ManualEntry />} />
        <Route path="/k/error" element={<ErrorScreen />} />

        {/* Admin — chunk separado, carga on-demand */}
        <Route path="/admin" element={lazyAdmin(AdminDashboard)} />
        <Route path="/admin/empleados" element={lazyAdmin(AdminEmployees)} />
        <Route path="/admin/empleados/:id" element={lazyAdmin(AdminEmployeeDetail)} />

        <Route path="*" element={<Navigate to="/k" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
