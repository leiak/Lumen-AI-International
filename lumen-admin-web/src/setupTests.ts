/**
 * Vitest global setup. Loaded once before each test file via vitest.config.ts.
 * Registers @testing-library/jest-dom matchers (e.g. toBeInTheDocument).
 */
import '@testing-library/jest-dom/vitest';