package com.koller.secret

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import androidx.core.content.ContextCompat.startActivity


class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        val code = intent.dataString?.substringAfter("://")
        val feature: String

        if (code in secretCodes) {
            feature = secretFeatures[secretCodes.indexOf(code)]

            val newIntent = Intent("$SECRET_CODE_ACTION.$feature")
            newIntent.addFlags(FLAG_ACTIVITY_NEW_TASK)
            startActivity(context, newIntent, null)
        }
    }

    companion object {
        const val SECRET_CODE_ACTION: String = "com.koller.secret.SECRET_CODE"
    }
}
