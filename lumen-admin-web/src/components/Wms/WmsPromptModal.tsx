/**
 * Lumen Admin — WmsPromptModal (PR5-T5.3, Iteration 3.0a).
 *
 * Modal-based replacement for `window.prompt(...)`. Used for `reject reason`
 * and other short-text inputs in WMS detail pages.
 *
 * Design: a singleton service backed by a single hidden host <div> at
 * document.body. We render a stable <PromptHost /> root once, and use
 * module-level mutable state for the pending resolver + open flag. The host
 * re-renders when showPrompt() is called, so opening / closing is just a
 * state mutation — no DOM churn.
 *
 * Cancellation contract: returns the user's trimmed input on OK (string) or
 * `null` on Cancel / mask click. Callers should treat `null` as "user
 * cancelled — abort the action".
 */

import { App, Input, type InputRef, Modal } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';

const HOST_ID = 'lumen-wms-prompt-host';

export interface ShowPromptOptions {
  title?: string;
  placeholder?: string;
  maxLength?: number;
  required?: boolean;
  okText?: string;
  cancelText?: string;
  initialValue?: string;
  /** Use textarea instead of input. */
  textarea?: boolean;
}

interface Pending {
  options: ShowPromptOptions;
  resolve: (value: string | null) => void;
}

// Pending state lives on the module so any imported call site can pop the
// modal and any host re-render will see the latest request.
let pending: Pending | null = null;
let hostRoot: Root | null = null;

/**
 * Open the prompt modal and wait for the user to confirm or cancel.
 *
 * @example
 *   const reason = await showPrompt({ title: '驳回原因', required: true });
 *   if (reason === null) return;            // user cancelled
 *   await request.post(`...`, { reason });  // proceed
 */
export function showPrompt(options: ShowPromptOptions = {}): Promise<string | null> {
  return new Promise<string | null>((resolve) => {
    pending = {
      options: { initialValue: '', ...options },
      resolve,
    };
    ensureHost();
    hostRoot?.render(<PromptHost />);
  });
}

function ensureHost() {
  if (hostRoot) return;
  let host = document.getElementById(HOST_ID);
  if (!host) {
    host = document.createElement('div');
    host.id = HOST_ID;
    document.body.appendChild(host);
  }
  hostRoot = createRoot(host);
}

/**
 * Stable renderer mounted once at the bottom of <body>. Re-renders on every
 * `showPrompt` invocation; shows nothing when no request is pending.
 */
function PromptHost() {
  // Local state mirrors `pending` to trigger re-renders. We intentionally
  // ignore React's "don't mutate during render" here because the mutation
  // happens synchronously inside the click handler before render commits.
  const [tick, setTick] = useState(0);
  const inputRef = useRef<InputRef>(null);
  const { modal } = App.useApp();
  // Tracks the last `tick` value for which we scheduled a focus+select.
  // The effect only schedules when `tick` advances *and* `pending` is set,
  // so focus+select fires once per modal opening — not on every keystroke.
  // Without this guard, the 30ms `setTimeout` lands between human keystrokes
  // (>30 ms apart), selects all typed text, and the next character overwrites
  // the selection. Fast synthetic typing (userEvent.type) keeps cancelling
  // the timer so the bug stayed hidden in tests.
  const lastTickRef = useRef<number>(-1);

  useEffect(() => {
    if (pending && lastTickRef.current !== tick) {
      lastTickRef.current = tick;
      const t = setTimeout(() => {
        inputRef.current?.focus();
        const inner = inputRef.current?.input;
        if (inner && 'select' in inner && typeof (inner as HTMLInputElement).select === 'function') {
          (inner as HTMLInputElement).select();
        }
      }, 30);
      return () => clearTimeout(t);
    }
    return undefined;
  }, [tick]);

  if (!pending) return null;
  const { options, resolve } = pending;
  const value = options.initialValue ?? '';
  const isTextarea =
    options.textarea ??
    (options.maxLength === undefined || (options.maxLength ?? 0) > 80);

  const submit = () => {
    const trimmed = value.trim();
    if (options.required && trimmed === '') {
      modal.warning({ title: '请输入内容', okText: '知道了' });
      return;
    }
    const r = resolve;
    pending = null;
    r(value);
    setTick((t) => t + 1);
  };

  const cancel = () => {
    const r = resolve;
    pending = null;
    r(null);
    setTick((t) => t + 1);
  };

  // Re-render on every keystroke — cheap because the modal is hidden when
  // closed, and updates are at most user typing speed.
  const onChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    if (pending) pending.options.initialValue = e.target.value;
    setTick((t) => t + 1);
  };

  return (
    <App>
      <Modal
        open
        title={options.title ?? '请输入'}
        okText={options.okText ?? '确定'}
        cancelText={options.cancelText ?? '取消'}
        onOk={submit}
        onCancel={cancel}
        maskClosable={false}
        destroyOnHidden
      >
        {isTextarea ? (
          <Input.TextArea
            ref={inputRef}
            value={value}
            placeholder={options.placeholder}
            maxLength={options.maxLength}
            rows={4}
            autoFocus
            onChange={onChange}
            onPressEnter={(e) => {
              if (!e.shiftKey) {
                e.preventDefault();
                submit();
              }
            }}
          />
        ) : (
          <Input
            ref={inputRef}
            value={value}
            placeholder={options.placeholder}
            maxLength={options.maxLength}
            autoFocus
            onChange={onChange}
            onPressEnter={submit}
          />
        )}
      </Modal>
    </App>
  );
}

/**
 * Hook variant for callers that prefer the hook form. Returns the same
 * `showPrompt` function as the module-level export; cheap and stable.
 */
export function usePrompt(): { showPrompt: typeof showPromptFn } {
  return { showPrompt: showPromptFn };
}

// `showPrompt` is hoisted above this declaration by `function`, so the
// reference here is valid. The export alias keeps the hook signature
// straightforward.
function showPromptFn(options?: ShowPromptOptions) {
  return showPrompt(options);
}
