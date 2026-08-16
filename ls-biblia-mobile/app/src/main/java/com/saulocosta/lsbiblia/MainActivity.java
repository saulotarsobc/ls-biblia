package com.saulocosta.lsbiblia;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

/** Entry point for the clean LS Bíblia mobile application. */
public final class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new View(this));
    }
}
