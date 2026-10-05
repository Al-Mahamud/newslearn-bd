package com.newslearn.bd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.newslearn.bd.ui.NewsLearnRoot
import com.newslearn.bd.ui.theme.NewsLearnTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NewsLearnTheme {
                NewsLearnRoot()
            }
        }
    }
}
