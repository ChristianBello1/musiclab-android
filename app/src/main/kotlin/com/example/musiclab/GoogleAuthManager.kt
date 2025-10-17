package com.example.musiclab

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

class GoogleAuthManager private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: GoogleAuthManager? = null

        fun getInstance(): GoogleAuthManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GoogleAuthManager().also { INSTANCE = it }
            }
        }

        private const val TAG = "GoogleAuthManager"
    }

    private var googleSignInClient: GoogleSignInClient? = null
    private var currentUser: GoogleSignInAccount? = null
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()

    private val authStateListeners = mutableListOf<(Boolean, GoogleSignInAccount?) -> Unit>()

    fun initialize(context: Context) {
        Log.d(TAG, "=== INITIALIZING WITH FIREBASE AUTH ===")

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .requestProfile()
            .build()

        googleSignInClient = GoogleSignIn.getClient(context, gso)

        // ✅ CRITICO: Aggiungi Firebase Auth State Listener
        // Questo si attiva OGNI VOLTA che lo stato di Firebase cambia
        firebaseAuth.addAuthStateListener { auth ->
            val firebaseUser = auth.currentUser

            Log.d(TAG, "🔥 Firebase Auth State Changed")

            if (firebaseUser != null) {
                // Firebase ha un utente loggato
                Log.d(TAG, "✅ Firebase user logged in: ${firebaseUser.email}")

                // Recupera anche l'account Google
                if (currentUser == null) {
                    currentUser = GoogleSignIn.getLastSignedInAccount(context)
                    Log.d(TAG, "Retrieved Google account: ${currentUser?.email}")
                }
            } else {
                // Nessun utente Firebase
                Log.d(TAG, "❌ No Firebase user")
                currentUser = null
            }

            // ✅ Notifica SEMPRE dopo ogni cambio di stato
            notifyAuthStateChanged()
        }

        // Recupera account Google attuale (se esiste)
        currentUser = GoogleSignIn.getLastSignedInAccount(context)

        Log.d(TAG, "Initial Google user: ${currentUser?.email}")
        Log.d(TAG, "Initial Firebase user: ${firebaseAuth.currentUser?.email}")
    }

    fun addAuthStateListener(listener: (Boolean, GoogleSignInAccount?) -> Unit) {
        authStateListeners.add(listener)
        listener.invoke(isLoggedIn(), currentUser)
    }

    fun removeAuthStateListener(listener: (Boolean, GoogleSignInAccount?) -> Unit) {
        authStateListeners.remove(listener)
    }

    private fun notifyAuthStateChanged() {
        val isLoggedIn = isLoggedIn()
        authStateListeners.forEach { listener ->
            listener.invoke(isLoggedIn, currentUser)
        }

        Log.d(TAG, "=== AUTH STATE ===")
        Log.d(TAG, "Logged in: $isLoggedIn")
        Log.d(TAG, "Google user: ${currentUser?.email}")
        Log.d(TAG, "Firebase UID: ${firebaseAuth.currentUser?.uid}")
    }

    fun signIn(): Intent? {
        Log.d(TAG, "Starting sign-in...")
        return googleSignInClient?.signInIntent
    }

    fun handleSignInResult(data: Intent?): Boolean {
        Log.d(TAG, "=== HANDLING SIGN IN ===")

        try {
            val task: Task<GoogleSignInAccount> = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)

            Log.d(TAG, "✅ Google Sign-In successful")
            Log.d(TAG, "Email: ${account.email}")
            Log.d(TAG, "ID Token: ${if (account.idToken != null) "Present" else "NULL"}")

            if (account.idToken == null) {
                Log.e(TAG, "❌ ID Token is NULL!")
                currentUser = null
                notifyAuthStateChanged()
                return false
            }

            currentUser = account
            firebaseAuthWithGoogle(account.idToken!!)
            return true

        } catch (e: ApiException) {
            Log.e(TAG, "❌ Google Sign-In failed: ${e.statusCode}")
            currentUser = null
            notifyAuthStateChanged()
            return false
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        Log.d(TAG, "=== FIREBASE AUTH START ===")

        val credential = GoogleAuthProvider.getCredential(idToken, null)

        firebaseAuth.signInWithCredential(credential)
            .addOnSuccessListener { authResult ->
                Log.d(TAG, "✅ Firebase Auth SUCCESS!")
                Log.d(TAG, "Firebase UID: ${authResult.user?.uid}")
                Log.d(TAG, "Email: ${authResult.user?.email}")
                notifyAuthStateChanged()
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "❌ Firebase Auth FAILED", exception)
                Log.e(TAG, "Error: ${exception.message}")
                currentUser = null
                notifyAuthStateChanged()
            }
    }

    fun signOut(context: Context, onComplete: (Boolean) -> Unit) {
        Log.d(TAG, "=== SIGNING OUT ===")

        googleSignInClient?.signOut()?.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                firebaseAuth.signOut()
                currentUser = null
                Log.d(TAG, "✅ Sign out successful")
            } else {
                Log.e(TAG, "❌ Sign out failed", task.exception)
            }

            notifyAuthStateChanged()
            onComplete(task.isSuccessful)
        }
    }

    fun getCurrentUser(): GoogleSignInAccount? = currentUser

    fun isLoggedIn(): Boolean {
        // ✅ SEMPLICE: Basta controllare Firebase
        val firebaseUser = firebaseAuth.currentUser
        val isLogged = firebaseUser != null

        Log.d(TAG, "isLoggedIn: $isLogged (Firebase user: ${firebaseUser?.email})")

        return isLogged
    }

    fun getUserEmail(): String? {
        // ✅ Prova prima Firebase, poi Google
        val email = firebaseAuth.currentUser?.email ?: currentUser?.email
        Log.d(TAG, "getUserEmail: $email")
        return email
    }

    fun getUserName(): String? {
        // ✅ Prova prima Firebase, poi Google
        val name = firebaseAuth.currentUser?.displayName ?: currentUser?.displayName
        Log.d(TAG, "getUserName: $name")
        return name
    }

    fun getUserId(): String? {
        // ✅ Usa sempre Firebase UID (più affidabile)
        val uid = firebaseAuth.currentUser?.uid
        Log.d(TAG, "Getting Firebase UID: $uid")
        return uid
    }

    fun getAuthStatus(): String {
        return if (isLoggedIn()) {
            """
            Logged in
            Name: ${getUserName()}
            Email: ${getUserEmail()}
            Firebase UID: ${getUserId()}
            """.trimIndent()
        } else {
            "Not logged in"
        }
    }
}