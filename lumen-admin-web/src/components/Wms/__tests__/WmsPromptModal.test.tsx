/**
 * WmsPromptModal — smoke + interaction tests (PR5-T5.3).
 *
 * Covers:
 *   1. showPrompt opens the modal; clicking OK returns the input value.
 *   2. Clicking Cancel returns null.
 *
 * Notes:
 *   - AntD's <Button> inserts an auto-space between CJK glyphs (visible
 *     "确定" vs DOM "确 定"). Tests match by role with a regex name
 *     to stay version-resistant.
 *   - The singleton's host node lives on document.body. RTL's cleanup()
 *     unmounts React trees but keeps document.body — we don't nuke
 *     `body.innerHTML` between tests, otherwise the singleton's cached
 *     root renders to a detached <div> and queries return empty.
 */

import { describe, it, expect, afterEach } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { showPrompt } from '@/components/Wms/WmsPromptModal';

describe('WmsPromptModal', () => {
  afterEach(() => {
    // RTL unmounts anything it rendered. The singleton host must remain
    // connected for the next test, so we do NOT clear document.body here.
    cleanup();
    // Ensure subsequent tests can mount a fresh modal if the prior one
    // was confirmed — flush any pending state by clearing localStorage
    // and resetting the document scroll lock antd's portal may have set.
    document.documentElement.scrollTop = 0;
    document.body.style.cssText = '';
  });

  it('opens the modal, OK returns the input', async () => {
    render(<div />);

    const user = userEvent.setup();
    const promise = showPrompt({ title: '驳回原因', maxLength: 255 });

    expect(await screen.findByText('驳回原因')).toBeInTheDocument();
    const input = (await screen.findByRole('textbox')) as HTMLInputElement;
    await user.type(input, 'something');
    await user.click(screen.getByRole('button', { name: /确.?定/ }));

    await waitFor(() => expect(promise).resolves.toBe('something'));
  });

  it('Cancel returns null', async () => {
    render(<div />);
    const user = userEvent.setup();
    const promise = showPrompt({ title: '收货数量', placeholder: '请输入数量' });

    expect(await screen.findByText('收货数量')).toBeInTheDocument();
    const input = (await screen.findByRole('textbox')) as HTMLInputElement;
    await user.type(input, '9');
    await user.click(screen.getByRole('button', { name: /取.?消/ }));

    await waitFor(() => expect(promise).resolves.toBeNull());
  });
});
