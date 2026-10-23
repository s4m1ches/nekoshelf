package app.meebook.launcher.backup.drive

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

    private var last: AuthorizationResult? = null

    /**
     * Returns an access token without UI, or null when the user must interact first (or Play
     * services fail / time out). **Blocking**: call it only off the main thread (e.g. in a worker).
     */
    fun silentToken(): String? = try {
        val result = Tasks.await(Identity.getAuthorizationClient(context).authorize(request), 20, TimeUnit.SECONDS)
        if (result.hasResolution()) null else result.accessToken.also { last = result }
    } catch (e: Exception) {
        null
    }

    /**
     * The consent/sign-in UI to launch, or null when no UI is needed (or Play services fail).
     * **Blocking** like [silentToken]: call it off the main thread.
     */
    fun signInIntent(activity: Activity): PendingIntent? = try {
        val result = Tasks.await(Identity.getAuthorizationClient(activity).authorize(request), 20, TimeUnit.SECONDS)
        if (result.hasResolution()) result.pendingIntent else null
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

    private companion object {
        const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    }
}
