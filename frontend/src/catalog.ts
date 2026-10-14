export type Model = { id: string; code: string; name: string; description: string; active: boolean; version: number };
export type Shape = 'STANDARD' | 'RECTANGULAR' | 'ROUND';
export type Part = Model & { kind: 'SERIALIZED_PIECE' | 'MEASURED_STOCK'; shape: Shape; stockUnit: 'PIECE' | 'LENGTH' | 'AREA'; lengthMm: number | null; widthMm: number | null; diameterMm: number | null; defaultInstallMinutes: number };
export type Machine = { id: string; serialNumber: string; modelId: string; modelCode: string; modelName: string; name: string; location: string; notes: string; active: boolean; version: number };
export type BomItem = { id: string; positionCode: string; partId: string; partCode: string; partName: string; shape: Shape; lengthMm: number | null; widthMm: number | null; diameterMm: number | null; quantity: number; expectedInstallMinutes: number; notes: string };
export type Bom = { id: string; modelId: string; machineId: string | null; revision: number; status: 'DRAFT' | 'PUBLISHED'; version: number; publishedAt: string | null; items: BomItem[] };
export const shapeNames = { STANDARD: 'قياسية', RECTANGULAR: 'مستطيلة', ROUND: 'دائرية' };
export function dimensions(p: Pick<Part, 'shape' | 'lengthMm' | 'widthMm' | 'diameterMm'>) {
  if (p.shape === 'STANDARD') return shapeNames.STANDARD;
  return p.shape === 'ROUND' ? `${p.lengthMm} × Ø ${p.diameterMm} mm` : `${p.lengthMm} × ${p.widthMm} mm`;
}
export const codePattern = '[A-Za-z0-9]([A-Za-z0-9._]|/|-){0,63}';
