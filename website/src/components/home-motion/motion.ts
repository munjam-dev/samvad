/** One motion language for the site: short, ease-out, no overshoot. Only transform and opacity animate. */
export const EASE = [0.16, 1, 0.3, 1] as const;
export const T150 = { duration: 0.15, ease: EASE };
export const T200 = { duration: 0.2, ease: EASE };
export const T300 = { duration: 0.3, ease: EASE };
