export type SupportedLanguage = 'en' | 'hi' | 'mr' | 'bn' | 'gu' | 'ta' | 'te' | 'kn';

export interface PaymentSpeakOptions {
  amountPaise: number;
  payerName?: string | null;
  appName?: string | null;
  language?: SupportedLanguage | string;
}
