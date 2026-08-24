package expo.modules.docusign

import android.app.Activity
import android.content.Context
import com.docusign.androidsdk.DSEnvironment
import com.docusign.androidsdk.DocuSign
import com.docusign.androidsdk.dsmodels.DSUser
import com.docusign.androidsdk.exceptions.DSAuthenticationException
import com.docusign.androidsdk.exceptions.DSSigningException
import com.docusign.androidsdk.listeners.DSAuthenticationListener
import com.docusign.androidsdk.listeners.DSCaptiveSigningListener
import com.docusign.androidsdk.listeners.DSLogoutListener
import com.docusign.androidsdk.util.DSMode
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import androidx.core.net.toUri

internal enum class DocuSignEnvironment(val value: String) {
  DEMO("demo"),
  PRODUCTION("production");

  companion object {
    fun fromString(value: String): DocuSignEnvironment =
      values().firstOrNull { it.value == value } ?: DEMO
  }
}

internal data class SigningOutcome(
  val status: String,
  val envelopeId: String,
  val errorCode: String? = null,
  val errorMessage: String? = null
)

internal data class DocuSignAccountInfo(
  val accountId: String,
  val userId: String,
  val userName: String,
  val email: String
)

internal object DocuSignManager {
  @Volatile private var isInitialized = false
  @Volatile private var hasLoggedIn = false
  @Volatile private var module: DocuSignModule? = null
  @Volatile private var appContext: Context? = null
  @Volatile private var integratorKey: String = ""
  @Volatile private var environment: DocuSignEnvironment = DocuSignEnvironment.DEMO
  @Volatile private var currentEnvelopeId: String? = null
  private val pendingCompletion = AtomicReference<((Result<SigningOutcome>) -> Unit)?>(null)

  private enum class UserInfoProbe { OK, UNAUTHORIZED, NETWORK }

  fun setModule(module: DocuSignModule) {
    this.module = module
  }

  fun initialize(
    context: Context,
    integratorKey: String,
    environment: DocuSignEnvironment
  ) {
    if (isInitialized) {
      return
    }

    val dsEnvironment = when (environment) {
      DocuSignEnvironment.DEMO -> DSEnvironment.DEMO_ENVIRONMENT
      DocuSignEnvironment.PRODUCTION -> DSEnvironment.PRODUCTION_ENVIRONMENT
    }

    val mode = if (BuildConfig.DEBUG) DSMode.DEBUG else DSMode.PRODUCTION
    DocuSign.init(context.applicationContext, integratorKey, "", "", mode)
      .setEnvironment(dsEnvironment)

    appContext = context.applicationContext
    this.integratorKey = integratorKey
    this.environment = environment
    isInitialized = true
  }

  fun loginWithAccessToken(
    accessToken: String,
    accountId: String,
    userId: String,
    userName: String,
    email: String,
    host: String,
    expiresIn: Int,
    completion: (Result<DocuSignAccountInfo>) -> Unit
  ) {
    val ctx = appContext
    if (!isInitialized || ctx == null) {
      completion(Result.failure(NotInitializedException()))
      return
    }

    try {
      DocuSign.getInstance().getAuthenticationDelegate().login(
        accessToken,
        null,
        expiresIn,
        ctx,
        object : DSAuthenticationListener {
          override fun onSuccess(user: DSUser) {
            hasLoggedIn = true
            val info = DocuSignAccountInfo(
              accountId = user.accountId.ifEmpty { accountId },
              userId = user.userId.ifEmpty { userId },
              userName = (user.name ?: "").ifEmpty { userName },
              email = user.email.ifEmpty { email }
            )
            completion(Result.success(info))
          }

          override fun onError(exception: DSAuthenticationException) {
            val sdkMsg = exception.message ?: "Unknown error"
            classifyLoginFailure(accessToken, sdkMsg) { enrichedMsg ->
              completion(Result.failure(LoginFailedException(enrichedMsg)))
            }
          }
        }
      )
    } catch (e: Exception) {
      completion(Result.failure(LoginFailedException(e.message ?: "Unknown error")))
    }
  }

  private fun classifyLoginFailure(
    accessToken: String,
    sdkMsg: String,
    completion: (String) -> Unit
  ) {
    probeUserInfoStatus(accessToken) { probe ->
      val diagnostic = "integratorKey=$integratorKey environment=${environment.value}"
      val enriched = when (probe) {
        UserInfoProbe.OK ->
          "SDK rejected a valid token. Likely causes: Mobile SDK not enabled for integration key $integratorKey, or Android package name not whitelisted in DocuSign admin. Contact DocuSign support. (SDK: $sdkMsg) | $diagnostic"
        UserInfoProbe.UNAUTHORIZED ->
          "Access token rejected by DocuSign /oauth/userinfo. Re-mint via JWT Bearer Grant with scope=signature impersonation. (SDK: $sdkMsg) | $diagnostic"
        UserInfoProbe.NETWORK ->
          "$sdkMsg | $diagnostic"
      }
      completion(enriched)
    }
  }

  private fun probeUserInfoStatus(accessToken: String, completion: (UserInfoProbe) -> Unit) {
    val base = when (environment) {
      DocuSignEnvironment.DEMO -> "https://account-d.docusign.com"
      DocuSignEnvironment.PRODUCTION -> "https://account.docusign.com"
    }
    thread(start = true, isDaemon = true) {
      var connection: HttpURLConnection? = null
      val result = try {
        connection = (URL("$base/oauth/userinfo").openConnection() as HttpURLConnection).apply {
          requestMethod = "GET"
          connectTimeout = 10_000
          readTimeout = 10_000
          setRequestProperty("Authorization", "Bearer $accessToken")
          setRequestProperty("Accept", "application/json")
        }
        when (val code = connection.responseCode) {
          in 200..299 -> UserInfoProbe.OK
          401, 403 -> UserInfoProbe.UNAUTHORIZED
          else -> {
            android.util.Log.w("DocuSign", "userinfo probe HTTP $code")
            UserInfoProbe.NETWORK
          }
        }
      } catch (e: Exception) {
        android.util.Log.w("DocuSign", "userinfo probe error: ${e.message}")
        UserInfoProbe.NETWORK
      } finally {
        connection?.disconnect()
      }
      completion(result)
    }
  }

  fun logout() {
    val ctx = appContext
    if (!isInitialized || ctx == null) return
    hasLoggedIn = false
    try {
      DocuSign.getInstance().getAuthenticationDelegate().logout(
        ctx,
        true,
        object : DSLogoutListener {
          override fun onSuccess() {}
          override fun onError(exception: DSAuthenticationException) {}
        }
      )
    } catch (_: Exception) {
    }
  }

  fun isLoggedIn(): Boolean = hasLoggedIn

  /**
   * Tears down any in-flight signing session and the underlying SDK auth
   * state so the next `loginWithAccessToken` + `presentCaptiveSigning` pair
   * starts from a clean slate. Safe to call when no session is active.
   *
   * iOS parity: see `DocuSignManager.endSigningSession` in
   * ios/DocuSignManager.swift. The hang this guards against is iOS-only,
   * but exposing the method on both platforms keeps the JS surface
   * symmetric.
   */
  fun endSigningSession() {
    val envelopeId = currentEnvelopeId ?: ""
    currentEnvelopeId = null
    pendingCompletion.getAndSet(null)?.invoke(
      Result.success(
        SigningOutcome(
          status = "cancelled",
          envelopeId = envelopeId,
          errorMessage = "session_ended"
        )
      )
    )
    logout()
  }

  /**
   * Full SDK teardown. Resolves any in-flight signing promise as cancelled,
   * calls `logout()`, and flips `isInitialized` back to `false` so the next
   * `initialize()` call runs `DocuSign.init(...)` against a fresh state.
   *
   * Use this when you want a hard reset between flows (error recovery,
   * switching DocuSign accounts, app-level logout). For routine teardown
   * between consecutive captive signing flows on the same auth, prefer
   * `endSigningSession`.
   *
   * Safe to call when the SDK was never initialized.
   */
  fun reset() {
    val envelopeId = currentEnvelopeId ?: ""
    currentEnvelopeId = null
    pendingCompletion.getAndSet(null)?.invoke(
      Result.success(
        SigningOutcome(
          status = "cancelled",
          envelopeId = envelopeId,
          errorMessage = "reset"
        )
      )
    )
    logout()
    isInitialized = false
  }

  fun presentCaptiveSigning(
    activity: Activity,
    envelopeId: String,
    recipientUserName: String,
    recipientEmail: String,
    recipientClientUserId: String,
    completion: (Result<SigningOutcome>) -> Unit
  ) {
    if (!isInitialized) {
      completion(Result.failure(NotInitializedException()))
      return
    }

    if (!isLoggedIn()) {
      completion(Result.failure(NotLoggedInException()))
      return
    }

    if (!pendingCompletion.compareAndSet(null, completion)) {
      completion(Result.failure(SigningFailedException("A signing session is already in progress")))
      return
    }
    currentEnvelopeId = envelopeId

    try {
      DocuSign.getInstance().getCustomSettingsDelegate()
        .disableNativeComponentsInOnlineSigning(activity, true)

      DocuSign.getInstance().getSigningDelegate().launchCaptiveSigning(
        activity,
        envelopeId,
        recipientClientUserId,
        object : DSCaptiveSigningListener {
          override fun onStart(envelopeId: String) {}

          override fun onSuccess(envelopeId: String) {
            handleSigningCompleted(envelopeId)
          }

          override fun onCancel(envelopeId: String, recipientId: String) {
            handleSigningCancelled(envelopeId, null)
          }

          override fun onError(envelopeId: String?, exception: DSSigningException) {
            handleSigningError(
              envelopeId,
              "signing_failed",
              exception.message ?: "Unknown error"
            )
          }

          override fun onRecipientSigningSuccess(envelopeId: String, recipientId: String) {}

          override fun onRecipientSigningError(
            envelopeId: String,
            recipientId: String,
            exception: DSSigningException
          ) {
            handleSigningError(
              envelopeId,
              "recipient_signing_failed",
              exception.message ?: "Unknown error"
            )
          }
        }
      )
    } catch (e: Exception) {
      val pending = pendingCompletion.getAndSet(null)
      pending?.invoke(Result.failure(SigningFailedException(e.message ?: "Unknown error")))
    }
  }

  /**
   * Presents captive signing from a pre-minted DocuSign recipient-view URL.
   * The URL is the signing credential, so SDK initialization is required but
   * a prior `loginWithAccessToken` call is not.
   */
  fun presentCaptiveSigningWithUrl(
    activity: Activity,
    signingUrl: String,
    envelopeId: String,
    recipientId: String?,
    completion: (Result<SigningOutcome>) -> Unit
  ) {
    if (!isInitialized) {
      completion(Result.failure(NotInitializedException()))
      return
    }

    val signingUri = signingUrl.toUri()
    if (
      !signingUri.scheme.equals("https", ignoreCase = true) ||
      signingUri.host.isNullOrBlank()
    ) {
      completion(Result.failure(SigningFailedException("Signing URL must be a valid HTTPS URL")))
      return
    }

    if (!pendingCompletion.compareAndSet(null, completion)) {
      completion(Result.failure(SigningFailedException("A signing session is already in progress")))
      return
    }
    currentEnvelopeId = envelopeId

    try {
      DocuSign.getInstance().getCustomSettingsDelegate()
        .disableNativeComponentsInOnlineSigning(activity, true)

      DocuSign.getInstance().getSigningDelegate().launchCaptiveSigning(
        activity,
        signingUrl,
        envelopeId,
        recipientId,
        object : DSCaptiveSigningListener {
          override fun onStart(envelopeId: String) {}

          override fun onSuccess(envelopeId: String) {
            handleSigningCompleted(envelopeId)
          }

          override fun onCancel(envelopeId: String, recipientId: String) {
            handleSigningCancelled(envelopeId, null)
          }

          override fun onError(envelopeId: String?, exception: DSSigningException) {
            handleSigningError(
              envelopeId,
              "signing_failed",
              exception.message ?: "Unknown error"
            )
          }

          override fun onRecipientSigningSuccess(envelopeId: String, recipientId: String) {}

          override fun onRecipientSigningError(
            envelopeId: String,
            recipientId: String,
            exception: DSSigningException
          ) {
            handleSigningError(
              envelopeId,
              "recipient_signing_failed",
              exception.message ?: "Unknown error"
            )
          }
        }
      )
    } catch (e: Exception) {
      currentEnvelopeId = null
      val pending = pendingCompletion.getAndSet(null)
      pending?.invoke(Result.failure(SigningFailedException(e.message ?: "Unknown error")))
    }
  }

  fun handleSigningCompleted(envelopeId: String) {
    val outcome = SigningOutcome(status = "completed", envelopeId = envelopeId)
    module?.emitSigningComplete(envelopeId)
    currentEnvelopeId = null
    pendingCompletion.getAndSet(null)?.invoke(Result.success(outcome))
  }

  fun handleSigningCancelled(envelopeId: String, reason: String?) {
    val outcome = SigningOutcome(
      status = "cancelled",
      envelopeId = envelopeId,
      errorMessage = reason
    )
    module?.emitSigningCancelled(envelopeId, reason)
    currentEnvelopeId = null
    pendingCompletion.getAndSet(null)?.invoke(Result.success(outcome))
  }

  fun handleSigningError(envelopeId: String?, errorCode: String, errorMessage: String) {
    module?.emitSigningError(envelopeId, errorCode, errorMessage)
    currentEnvelopeId = null
    pendingCompletion.getAndSet(null)?.invoke(Result.failure(SigningFailedException(errorMessage)))
  }
}
