/**
 * SlaConfigForm — submits values to the caller (Iteration 4.2 PR6).
 *
 * Locks that the underlying `ProForm` fires `onSubmit` with the
 * canonical shape (severity / level / responseMinutes / resolveMinutes /
 * escalateToLevel) when the user clicks the form's submit button.
 *
 * Implementation notes:
 *   - The full ProForm lifecycle (rules / hidden labels / async submit
 *     spinners) is exercised end-to-end in the FE integration flow.
 *     Here we lock only the wired-up shape.
 *   - ProForm's submit button label is "保 存" (with an auto-inserted
 *     U+00A0 non-breaking space between CJK glyphs); we match by regex
 *     to stay version-resistant.
 *   - jsdom does not implement window.matchMedia; antd's responsive
 *     observer fires inside useLayoutEffect and would otherwise throw.
 *     We polyfill it once per test (matching the App.smoke.test pattern).
 */

import { describe, it, expect, vi, beforeAll } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import SlaConfigForm from '@/components/cs/SlaConfigForm';

beforeAll(() => {
  if (typeof window !== 'undefined' && !window.matchMedia) {
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      value: vi.fn().mockImplementation((query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      })),
    });
  }
});

describe('SlaConfigForm', () => {
  it('calls onSubmit with the form values when submitted', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();

    render(
      <SlaConfigForm
        initialValues={{
          severity: 'HIGH',
          level: 'L1',
          responseMinutes: 30,
          resolveMinutes: 240,
          escalateToLevel: 'L2',
        }}
        onSubmit={onSubmit}
      />,
    );

    // ProForm renders a submit button with the label "提交" (Antd inserts
    // an automatic space between CJK glyphs — match by regex). There is
    // also a "重置" reset button; use getAllByRole and pick the submit.
    const buttons = screen.getAllByRole('button');
    const submit = buttons.find((b) => /提.?交/.test(b.textContent ?? ''));
    expect(submit).toBeDefined();
    if (!submit) return;
    await user.click(submit);

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    // ProForm forwards the values object — assert the shape survives.
    const [values] = onSubmit.mock.calls[0];
    expect(values).toEqual({
      severity: 'HIGH',
      level: 'L1',
      responseMinutes: 30,
      resolveMinutes: 240,
      escalateToLevel: 'L2',
    });
  });
});