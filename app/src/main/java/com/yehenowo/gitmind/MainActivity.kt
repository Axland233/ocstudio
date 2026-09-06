package com.yehenowo.gitmind

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = AppViewModel(filesDir)
        ToastProxy.show = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        setContent { App(vm) }
    }
}