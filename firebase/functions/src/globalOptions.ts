import { setGlobalOptions } from 'firebase-functions/v2';

/**
 * Caps how far each function scales out, and sizes each instance like 1st-gen functions (a sixth of
 * a vCPU at 256 MiB, one request at a time). Every function here is short and I/O-bound, so this is
 * plenty; it keeps a runaway bill impossible and keeps the project inside the small regional Cloud
 * Run CPU quota that new projects start with. Imported first in index.ts so it applies everywhere.
 */
export const MAX_INSTANCES = 10;

setGlobalOptions({ maxInstances: MAX_INSTANCES, cpu: 'gcf_gen1', memory: '256MiB' });
