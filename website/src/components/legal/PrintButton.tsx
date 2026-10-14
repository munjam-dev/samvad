'use client';

/** Opens the browser's print dialog, where "Save as PDF" gives a clean copy of the document. */
const PrintButton = () => (
  <button
    type="button"
    onClick={() => window.print()}
    className="legal-no-print border-stroke-3 dark:border-stroke-7 text-tagline-2 text-secondary dark:text-accent hover:bg-background-3 dark:hover:bg-background-6 inline-flex min-h-11 items-center gap-2 rounded-full border px-5 py-2 transition-colors duration-300">
    <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M6 9V3h12v6M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2M6 14h12v7H6z" />
    </svg>
    Print or save as PDF
  </button>
);

export default PrintButton;
