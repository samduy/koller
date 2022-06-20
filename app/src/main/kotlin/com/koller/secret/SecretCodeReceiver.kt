package com.koller.secret

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast


class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        val code = intent.dataString?.substringAfter("://")
        Toast.makeText(context, "$code received", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val SECRET_CODE_ACTION: String = "com.koller.secret.SECRET_CODE"
    }
}
