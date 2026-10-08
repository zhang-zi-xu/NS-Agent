export type FieldProfile = {
  id: string;
  name: string;
  crop: string;
  variety?: string;
  sowDate: string; // YYYY-MM-DD
  areaMu?: number;
  notes?: string;
  records?: Array<{ date: string; note: string }>;
};
