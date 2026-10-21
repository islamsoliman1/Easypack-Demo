import type { Machine } from './catalog';
import type { Order } from './OrdersPage';

export type MachineOverview = { machine: Machine; orders: Order[]; currentOrder: Order | undefined };

export function assemblyPercent(order: Order | undefined): number {
  if (!order || order.status === 'DRAFT' || order.status === 'CANCELLED' || order.requiredCount <= 0) return 0;
  return Math.max(0, Math.min(100, Math.floor(order.installedCount * 100 / order.requiredCount)));
}

export function machineOverviews(machines: Machine[], orders: Order[]): MachineOverview[] {
  const byMachine = new Map<string, Order[]>();
  for (const order of orders) {
    const group = byMachine.get(order.machineId) ?? [];
    group.push(order);
    byMachine.set(order.machineId, group);
  }
  for (const group of byMachine.values()) {
    group.sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt) || a.id.localeCompare(b.id));
  }
  return machines.map(machine => {
    const history = byMachine.get(machine.id) ?? [];
    return { machine, orders: history, currentOrder: history.find(order => order.status !== 'CANCELLED') ?? history[0] };
  });
}

export function isWorking(order: Order | undefined): boolean {
  return !!order && !['DRAFT', 'CANCELLED', 'COMPLETED'].includes(order.status);
}

export function isOverdue(order: Order | undefined, now: number): boolean {
  return !!order && order.status !== 'CANCELLED' && !order.deliveredAt && Date.parse(order.deliveryAt) < now;
}

