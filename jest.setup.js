// Stub the native module so tests never reach requireNativeModule.
jest.mock('./src/DocuSignModule', () => ({
  __esModule: true,
  default: {
    presentCaptiveSigningWithUrl: jest.fn(),
  },
}));
