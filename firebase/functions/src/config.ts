export const REGION = 'asia-south1';
export const MAX_AUTH_AGE_SECONDS = 300;
export const REVOKE_COOLDOWN_SECONDS = 60;

export interface CallableConfig {
  region: string;
  enforceAppCheck: boolean;
  consumeAppCheckToken: boolean;
}

/** App Check (with replay protection) is enforced everywhere except the Functions emulator. */
export function buildCallableOptions(env: NodeJS.ProcessEnv): CallableConfig {
  const enforce = env.FUNCTIONS_EMULATOR !== 'true';
  return { region: REGION, enforceAppCheck: enforce, consumeAppCheckToken: enforce };
}
