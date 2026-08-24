import { expect, it, jest } from '@jest/globals';
import DocuSignModule from './DocuSignModule';
import { presentCaptiveSigningWithUrl } from './api';

it('delegates presentCaptiveSigningWithUrl to the native module', () => {
  const mockPresentCaptiveSigningWithUrl = jest.mocked(
    DocuSignModule.presentCaptiveSigningWithUrl,
  );
  const params = {
    signingUrl: 'https://demo.docusign.net/signing/example',
    envelopeId: 'envelope-id',
    recipientId: 'recipient-id',
  };
  const nativeResult = Promise.resolve({
    status: 'completed' as const,
    envelopeId: params.envelopeId,
  });
  mockPresentCaptiveSigningWithUrl.mockReturnValue(nativeResult);

  expect(presentCaptiveSigningWithUrl(params)).toBe(nativeResult);
  expect(mockPresentCaptiveSigningWithUrl).toHaveBeenCalledWith(params);
});
