/**
 * Smoke test — verifies the Vitest + RTL stack works.
 * Real hook tests live in src/hooks/__tests__/ (Task 4.7).
 */
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';

describe('smoke', () => {
  it('renders a basic element', () => {
    render(<div data-testid="hello">hello world</div>);
    expect(screen.getByTestId('hello')).toBeInTheDocument();
    expect(screen.getByTestId('hello')).toHaveTextContent('hello world');
  });

  it('arithmetic works', () => {
    expect(2 + 2).toBe(4);
  });
});