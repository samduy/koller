package com.koller.secret.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.add
import androidx.fragment.app.commit
import com.simplemobiletools.dialer.R

class HideMyPhotoActivity : AppCompatActivity(R.layout.activity_hide_my_photo) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                add<HiddenPhotoFragment>(R.id.container)
            }
        }

    }
}
