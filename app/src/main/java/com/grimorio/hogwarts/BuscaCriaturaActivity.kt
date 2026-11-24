package com.grimorio.hogwarts

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.grimorio.hogwarts.api.HogwartsApi
import com.grimorio.hogwarts.util.Rede
import com.grimorio.hogwarts.util.ValidadorBusca
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BuscaCriaturaActivity : AppCompatActivity() {

    private var buscaJob: Job? = null
    private var geracaoBusca = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_busca_criatura)

        val campoLayout = findViewById<TextInputLayout>(R.id.campoNomeLayout)
        val campo = findViewById<TextInputEditText>(R.id.campoNome)
        val botao = findViewById<MaterialButton>(R.id.botaoBuscar)
        val progress = findViewById<ProgressBar>(R.id.progressBar)
        val mensagem = findViewById<TextView>(R.id.textoMensagem)
        val painel = findViewById<View>(R.id.painelResultado)
        val imagem = findViewById<ImageView>(R.id.imagemCriatura)
        val avisoImagem = findViewById<TextView>(R.id.textoImagemIndisponivel)
        val nome = findViewById<TextView>(R.id.textoNomeCriatura)

        findViewById<View>(R.id.botaoVoltar).setOnClickListener { finish() }
        campo.doAfterTextChanged { campoLayout.error = null }
        campo.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                botao.performClick()
                true
            } else {
                false
            }
        }

        botao.setOnClickListener {
            when (val resultado = ValidadorBusca.validar(campo.text?.toString().orEmpty())) {
                is ValidadorBusca.Resultado.Invalido -> {
                    cancelarBusca()
                    campoLayout.error = textoValidacao(resultado.mensagem)
                    mensagem.visibility = View.GONE
                    painel.visibility = View.GONE
                    esconderCarregamento(progress, botao)
                }

                is ValidadorBusca.Resultado.Ok -> {
                    campoLayout.error = null
                    esconderTeclado(campo)
                    consultar(resultado.texto, progress, botao, mensagem, painel, imagem, avisoImagem, nome)
                }
            }
        }
    }

    private fun consultar(
        termo: String,
        progress: ProgressBar,
        botao: MaterialButton,
        mensagem: TextView,
        painel: View,
        imagem: ImageView,
        avisoImagem: TextView,
        nome: TextView
    ) {
        if (!Rede.disponivel(this)) {
            cancelarBusca()
            painel.visibility = View.GONE
            mostrarMensagem(mensagem, getString(R.string.erro_consulta))
            esconderCarregamento(progress, botao)
            return
        }

        val id = ++geracaoBusca
        buscaJob?.cancel()
        mensagem.visibility = View.GONE
        painel.visibility = View.GONE
        mostrarCarregamento(progress, botao)

        buscaJob = lifecycleScope.launch {
            try {
                val criatura = withContext(Dispatchers.IO) {
                    HogwartsApi.buscarCriaturaPorNomeCompleto(termo)
                }
                if (criatura == null) {
                    mostrarMensagem(mensagem, getString(R.string.nenhuma_criatura))
                    return@launch
                }

                val url = criatura.imagemUrl
                val bitmap = if (url.isNullOrBlank()) {
                    null
                } else {
                    withContext(Dispatchers.IO) { HogwartsApi.baixarImagem(url) }
                }

                nome.text = criatura.nome
                if (bitmap != null) {
                    imagem.setImageBitmap(bitmap)
                    imagem.contentDescription = criatura.nome
                    avisoImagem.visibility = View.GONE
                } else {
                    imagem.setImageResource(R.drawable.ic_imagem_indisponivel)
                    imagem.contentDescription = getString(R.string.imagem_indisponivel)
                    avisoImagem.visibility = View.VISIBLE
                }
                painel.visibility = View.VISIBLE
            } catch (cancelamento: CancellationException) {
                throw cancelamento
            } catch (_: Exception) {
                mostrarMensagem(mensagem, getString(R.string.erro_consulta))
            } finally {
                if (id == geracaoBusca) {
                    esconderCarregamento(progress, botao)
                }
            }
        }
    }

    private fun cancelarBusca() {
        geracaoBusca++
        buscaJob?.cancel()
        buscaJob = null
    }

    private fun textoValidacao(mensagem: ValidadorBusca.Mensagem): String {
        return when (mensagem) {
            ValidadorBusca.Mensagem.VAZIO -> getString(R.string.erro_vazio)
            ValidadorBusca.Mensagem.CURTO -> getString(R.string.erro_curto)
        }
    }

    private fun mostrarMensagem(destino: TextView, texto: String) {
        destino.text = texto
        destino.visibility = View.VISIBLE
    }

    private fun mostrarCarregamento(progress: ProgressBar, botao: MaterialButton) {
        progress.visibility = View.VISIBLE
        botao.isEnabled = false
    }

    private fun esconderCarregamento(progress: ProgressBar, botao: MaterialButton) {
        progress.visibility = View.GONE
        botao.isEnabled = true
    }

    private fun esconderTeclado(campo: View) {
        val gerenciador = getSystemService(InputMethodManager::class.java)
        gerenciador?.hideSoftInputFromWindow(campo.windowToken, 0)
    }
}
