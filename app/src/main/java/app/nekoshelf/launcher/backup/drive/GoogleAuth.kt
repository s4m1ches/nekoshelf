package app.nekoshelf.launcher.backup.drive

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Drive authorization through the Play-services Authorization API (scope `drive.file`).
 * An Android OAuth client is matched by package name + signing SHA-1, so no client id is
 * configured in code.
 */
class GoogleAuth(private val context: Context) {

    private val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_FILE)))
        .build()

    private val readonlyRequest: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_FILE), Scope(READONLY_SCOPE)))
        .build()

    @Volatile private var last: AuthorizationResult? = null

    /**
     * Returns an access token without UI, or null **only** when the owner must interact first
     * (consent / sign-in needed: the backup reports that as revoked access and stops automatic
     * runs). Anything else — Play services failing or timing out, no network, an interrupted wait,
     * a result without a token — throws [TokenUnavailableException], which the backup treats as
     * «нет сети» and retries at the next wake. **Blocking**: call it only off the main thread.
     */
    fun silentToken(withReadonly: Boolean = false): String? {
        val result = try {
            Tasks.await(Identity.getAuthorizationClient(context).authorize(if (withReadonly) readonlyRequest else request), 20, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TokenUnavailableException("interrupted while waiting for Google Play services", e)
        } catch (e: Exception) {
            throw TokenUnavailableException("Google Play services gave no token", e)
        }
        if (result.hasResolution()) return null
        val token = result.accessToken ?: throw TokenUnavailableException("authorization result without a token", null)
        last = result
        return token
    }

    /**
     * The consent/sign-in UI to launch, or null when no UI is needed (or Play services fail).
     * **Blocking** like [silentToken]: call it off the main thread.
     */
    fun signInIntent(activity: Activity): PendingIntent? = try {
        val result = Tasks.await(Identity.getAuthorizationClient(activity).authorize(request), 20, TimeUnit.SECONDS)
        if (result.hasResolution()) result.pendingIntent else null
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (e: Exception) {
        null
    }

    /**
     * Best effort, no UI, **blocking** (off the main thread). True when a silent authorization asking
     * for drive.file + drive.readonly returns a token without needing consent, or when the last
     * authorization result (from [silentToken] or [tokenFromResult]) already granted the readonly
     * scope. Any failure counts as «not granted».
     */
    fun hasReadonly(): Boolean {
        if (last?.grantedScopes?.contains(READONLY_SCOPE) == true) return true
        return try {
            val r = Tasks.await(Identity.getAuthorizationClient(context).authorize(readonlyRequest), 20, TimeUnit.SECONDS)
            if (r.hasResolution() || r.accessToken == null) {
                false
            } else {
                last = r
                r.grantedScopes.contains(READONLY_SCOPE)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The consent UI that adds drive.readonly (requested together with drive.file), or null when no UI
     * is needed (already granted) or Play services fail. **Blocking**: call it off the main thread.
     */
    fun readonlyIntent(activity: Activity): PendingIntent? = try {
        val result = Tasks.await(Identity.getAuthorizationClient(activity).authorize(readonlyRequest), 20, TimeUnit.SECONDS)
        if (result.hasResolution()) result.pendingIntent else null
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (e: Exception) {
        null
    }

    /** Extracts the access token from the result Intent returned by the UI started with [signInIntent]. */
    fun tokenFromResult(data: Intent): String? = try {
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        last = result
        result.accessToken
    } catch (e: Exception) {
        null
    }

    /** Best-effort account e-mail from the last authorization; null when unknown. */
    fun account(): String? = try {
        last?.toGoogleSignInAccount()?.email
    } catch (e: Exception) {
        null
    }

    companion object {
        const val READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        private const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    }
}
