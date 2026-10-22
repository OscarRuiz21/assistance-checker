// Tipos compartidos con backend Spring Modulith.
// Mirror manual de los records públicos en com.assistance.{module}.api.*

export type EventType = "CHECK_IN" | "CHECK_OUT" | "LUNCH_OUT" | "LUNCH_IN";
export type CaptureMethod = "NFC" | "PIN" | "MANUAL";
export type EmployeeStatus = "ACTIVE" | "ON_LEAVE" | "TERMINATED";
export type CardStatus = "ACTIVE" | "BLOCKED" | "LOST";

export interface EmployeeView {
  id: string;
  tenantId: string;
  employeeNumber: string;
  fullName: string;
  defaultPlantId: string | null;
  status: EmployeeStatus;
}

export interface EventView {
  id: string;
  employeeId: string;
  employeeName: string;
  employeeNumber: string;
  eventType: EventType;
  captureMethod: CaptureMethod;
  eventTime: string; // ISO instant
  receivedAt: string;
  valid: boolean;
}

export interface DashboardKpis {
  totalEntries: number;
  uniqueEmployees: number;
  lateCount: number;
  from: string;
  to: string;
}

export interface EmployeeWorkSummary {
  employeeId: string;
  employeeName: string;
  employeeNumber: string;
  daysWorked: number;
  totalEvents: number;
  secondsInOffice: number;
  secondsLunch: number;
  secondsWorked: number;
  lastCheckIn: string | null;
}

export interface DailyBreakdown {
  businessDate: string;        // YYYY-MM-DD
  checkIn: string | null;
  lunchOut: string | null;
  lunchIn: string | null;
  checkOut: string | null;
  secondsInOffice: number;
  secondsLunch: number;
  secondsWorked: number;
  isComplete: boolean;
}

export interface CreateEventDto {
  employeeId?: string;
  cardUid?: string;
  employeeNumber?: string;
  eventType: string; // db value: "check_in", etc.
  captureMethod: string;
  pin?: string;
  deviceTime: string;
  kioskoId?: string;
}

export interface ApiErrorResponse {
  code: string;
  message: string;
  timestamp: string;
  errors: Array<{ field: string; message: string }> | null;
}

export const eventTypeDb: Record<EventType, string> = {
  CHECK_IN: "check_in",
  CHECK_OUT: "check_out",
  LUNCH_OUT: "lunch_out",
  LUNCH_IN: "lunch_in",
};

export const captureMethodDb: Record<CaptureMethod, string> = {
  NFC: "nfc",
  PIN: "pin",
  MANUAL: "manual",
};
