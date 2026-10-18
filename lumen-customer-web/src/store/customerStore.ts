import { create } from 'zustand';
import type { PortalCustomerChoice } from '../types/portal';

/**
 * 4.5A.4-T4.2 — Customer-choice store.
 *
 * Holds the list of customers the current user can act as. Populated from the
 * PortalLoginResponse.choices payload after login or select-customer; consumed
 * by the SelectCustomer page (T4.3) to render the picker.
 */

interface CustomerState {
  choices: PortalCustomerChoice[];
  setChoices: (choices: PortalCustomerChoice[]) => void;
  clear: () => void;
}

export const useCustomerStore = create<CustomerState>()((set) => ({
  choices: [],
  setChoices: (choices) => set({ choices }),
  clear: () => set({ choices: [] }),
}));
