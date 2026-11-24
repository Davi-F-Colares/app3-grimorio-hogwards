package com.grimorio.hogwarts

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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

class BuscaPersonagemActivity : AppCompatActivity() {

    private var buscaJob: Job? = null
    private var geracaoBusca = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_busca_personagem)

        val campoLayout = findViewById<TextInputLayout>(R.id.campoNomeLayout)
        val campo = findViewById<TextInputEditText>(R.id.campoNome)
        val botao = findViewById<MaterialButton>(R.id.botaoBuscar)
        val progress = findViewById<ProgressBar>(R.id.progressBar)
        val mensagem = findViewById<TextView>(R.id.textoMensagem)
        val painel = findViewById<View>(R.id.painelResultado)
        val nome = findViewById<TextView>(R.id.textoNome)
        val especie = findViewById<TextView>(R.id.textoEspecie)
        val casa = findViewById<TextView>(R.id.textoCasa)

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
                    consultar(resultado.texto, progress, botao, mensagem, painel, nome, especie, casa)
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
        nome: TextView,
        especie: TextView,
        casa: TextView
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
                val lista = withContext(Dispatchers.IO) {
                    HogwartsApi.buscarPersonagens(termo)
                }
                if (lista.isEmpty()) {
                    mostrarMensagem(mensagem, getString(R.string.nenhum_personagem))
                } else {
                    val personagem = lista.first()
                    nome.text = personagem.nome
                    especie.text = personagem.especie
                    casa.text = personagem.casa
                    painel.visibility = View.VISIBLE
                }
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
