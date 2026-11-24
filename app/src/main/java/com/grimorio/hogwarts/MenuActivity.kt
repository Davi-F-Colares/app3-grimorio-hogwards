package com.grimorio.hogwarts

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class MenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        findViewById<MaterialButton>(R.id.botaoPersonagem).setOnClickListener {
            startActivity(Intent(this, BuscaPersonagemActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.botaoCriatura).setOnClickListener {
            startActivity(Intent(this, BuscaCriaturaActivity::class.java))
        }
    }
}
