export interface NotificationPayload {
  key: string;
  packageName: string;
  postedAt: number;
  title: string | null;
  text: string | null;
  bigText: string | null;
  combinedText: string;
}

export interface ParsedPayment {
  id?: number;
  amountPaise: number;
  isCredit: boolean;
  payerName: string | null;
  upiRef: string | null;
  sourcePackage: string;
  postedAt: number;
  rawText: string;
}

export interface FilterConfig {
  packageAllowlist?: string[];
  ignoreSummaries?: boolean;
  ignoreChatMessages?: boolean;
  ignoreOngoing?: boolean;
}
