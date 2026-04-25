import { registerPlugin } from '@capacitor/core';

import type { CapUpdatePlugin } from './definitions';

const CapUpdate = registerPlugin<CapUpdatePlugin>('CapUpdate', {
  web: () => import('./web').then((m) => new m.CapUpdateWeb()),
});

export * from './definitions';
export { CapUpdate };
