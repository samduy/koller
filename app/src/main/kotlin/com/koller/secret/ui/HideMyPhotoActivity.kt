package com.koller.secret.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.simplemobiletools.dialer.R

class HideMyPhotoActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(applicationContext, "HideMyPhotoActivity loaded", Toast.LENGTH_SHORT).show()
        setContentView(R.layout.activity_hide_my_photo)
    }
}
