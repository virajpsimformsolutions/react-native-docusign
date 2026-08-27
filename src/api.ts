import {
  CaptiveSigningParams,
  CaptiveSigningUrlParams,
  DocuSignAccountInfo,
  DocuSignAuthParams,
  DocuSignConfig,
  LoginAttemptEvent,
  SigningCancelledEvent,
  SigningCompleteEvent,
  SigningErrorEvent,
  SigningResult,
} from './DocuSign.types';
import DocuSignModule from './DocuSignModule';

export type DocuSignSubscription = {
  remove(): void;
};

export function initialize(config: DocuSignConfig): Promise<void> {
  return DocuSignModule.initialize(config);
}

export function loginWithAccessToken(
  params: DocuSignAuthParams,
): Promise<DocuSignAccountInfo> {
  return DocuSignModule.loginWithAccessToken(params);
}

export function presentCaptiveSigning(
  params: CaptiveSigningParams,
): Promise<SigningResult> {
  return DocuSignModule.presentCaptiveSigning(params);
}

/**
 * Present captive signing from a pre-minted DocuSign recipient-view URL.
 *
 * Does NOT require a prior {@link loginWithAccessToken} call. The URL itself
 * encodes recipient identity via a short-lived token. {@link initialize} is
 * still required.
 *
 * Supported on iOS and Android.
 */
export function presentCaptiveSigningWithUrl(
  params: CaptiveSigningUrlParams,
): Promise<SigningResult> {
  return DocuSignModule.presentCaptiveSigningWithUrl(params);
}

export function logout(): Promise<void> {
  return DocuSignModule.logout();
}

export function isLoggedIn(): Promise<boolean> {
  return DocuSignModule.isLoggedIn();
}

/**
 * Tears down any in-flight signing session and the underlying DocuSign SDK
 * auth state. Call this between captive signing flows so the next
 * `loginWithAccessToken` + `presentCaptiveSigning` pair starts from a clean
 * slate. Safe to call when no session is active.
 *
 * Fixes an iOS captive signing hang that occurred on the second open within
 * a session: the SDK's implicit teardown raced with `DSMManager.login`,
 * leaving the WebView stuck on a spinner. The `useDocuSignSigning` hook
 * calls this from `reset()` automatically.
 */
export function endSigningSession(): Promise<void> {
  return DocuSignModule.endSigningSession();
}

/**
 * Full SDK teardown. Resolves any in-flight signing promise as cancelled,
 * wipes WebKit data (iOS), calls `logout()`, removes notification observers
 * (iOS), and flips the internal `isInitialized` flag to `false` so the next
 * `initialize()` call re-runs the underlying SDK setup against a fresh state.
 *
 * Use this when you want a hard reset between flows (error recovery,
 * switching DocuSign accounts, after an app-level logout). For routine
 * teardown between consecutive captive signing flows on the same auth,
 * prefer {@link endSigningSession} which keeps the SDK initialized and
 * skips the observer churn.
 *
 * Safe to call when the SDK was never initialized: returns immediately.
 */
export function reset(): Promise<void> {
  return DocuSignModule.reset();
}

export function addSigningCompleteListener(
  listener: (event: SigningCompleteEvent) => void,
): DocuSignSubscription {
  return DocuSignModule.addListener('onSigningComplete', listener);
}

export function addSigningCancelledListener(
  listener: (event: SigningCancelledEvent) => void,
): DocuSignSubscription {
  return DocuSignModule.addListener('onSigningCancelled', listener);
}

export function addSigningErrorListener(
  listener: (event: SigningErrorEvent) => void,
): DocuSignSubscription {
  return DocuSignModule.addListener('onSigningError', listener);
}

export function addLoginAttemptListener(
  listener: (event: LoginAttemptEvent) => void,
): DocuSignSubscription {
  return DocuSignModule.addListener('onLoginAttempt', listener);
}
