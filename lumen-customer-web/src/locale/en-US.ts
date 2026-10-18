/**
 * 4.5A.4-T4.3 — English (en-US) copy.
 *
 * 4.5A pages may hardcode Chinese labels; this file is the source of truth for
 * the English fallback and a future react-i18next integration. Key set must stay
 * in lock-step with zh-CN.ts.
 */

export const enUS = {
  common: {
    confirm: 'Confirm',
    cancel: 'Cancel',
    save: 'Save',
    submit: 'Submit',
    back: 'Back',
    refresh: 'Refresh',
    loading: 'Loading...',
    yes: 'Yes',
    no: 'No',
    detail: 'Detail',
  },
  menu: {
    track: 'Track',
    profile: 'Profile',
    logout: 'Logout',
  },
  login: {
    title: 'Lumen Customer Portal',
    subtitle: 'Sign in to view your shipments and profile',
    username: 'Username',
    password: 'Password',
    submit: 'Sign in',
    welcomeBack: 'Welcome, {name}',
    invalidCredentials: 'Invalid credentials, please try again',
  },
  selectCustomer: {
    title: 'Choose a customer',
    subtitle: 'You belong to multiple customers; please pick one to continue',
    primaryBadge: 'Primary',
    choose: 'Choose',
  },
  track: {
    title: 'Delivery Order Tracking',
    column: {
      doNo: 'DO No.',
      status: 'Status',
      origin: 'Origin',
      destination: 'Destination',
      recipient: 'Recipient',
      phone: 'Phone',
      createdAt: 'Created',
      signedAt: 'Signed',
    },
    filter: {
      doNo: 'DO No.',
      status: 'Status',
    },
    detail: {
      title: 'Delivery Order Detail',
      base: 'Overview',
      timeline: 'Timeline',
      pod: 'Proof of Delivery',
      weight: 'Weight',
      volume: 'Volume',
      weightUnit: 'kg',
      volumeUnit: 'm³',
      emptyTimeline: 'No timeline events',
      podLoading: 'Loading POD...',
      podNone: 'No POD available',
      podPhoto: 'Photo POD',
      podSignature: 'Signature POD',
      signer: 'Signer',
      signedAtLabel: 'Signed at',
    },
  },
  status: {
    CREATED: 'Created',
    PENDING_DISPATCH: 'Pending Dispatch',
    DISPATCHED: 'Dispatched',
    IN_TRANSIT: 'In Transit',
    ARRIVED: 'Arrived',
    DELIVERED: 'Delivered',
    EXCEPTION: 'Exception',
    CANCELLED: 'Cancelled',
    UNKNOWN: 'Unknown',
  },
  profile: {
    title: 'My Profile',
    current: 'Current profile',
    update: 'Update profile',
    customerId: 'Customer ID',
    note: 'Note',
    phone: 'Phone',
    address: 'Address',
    password: 'New password (optional)',
    passwordPlaceholder: 'Leave blank to keep current',
    success: 'Saved',
    fail: 'Save failed',
  },
  notFound: {
    title: '404',
    description: 'Page not found',
    back: 'Back to home',
  },
};

export type Locale = typeof enUS;
