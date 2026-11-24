package com.grimorio.hogwarts.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.grimorio.hogwarts.model.Criatura
import com.grimorio.hogwarts.model.Personagem
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class FalhaConsultaException : IOException()

object HogwartsApi {

    private const val BASE = "https://hogwarts-api.com/api"
    private const val TIMEOUT_MS = 20_000
    private const val MAX_IMAGEM_BYTES = 12 * 1024 * 1024
    private const val NAO_INFORMADO = "Não informado"
    private const val TAG = "Grimorio"
    private val REDIRECIONAMENTOS = setOf(301, 302, 303, 307, 308)
    private val SERVIDORES_DNS = listOf("1.1.1.1", "8.8.8.8")
    private val clienteImagem: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = resolverNome(hostname)
            })
            .build()
    }
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    fun buscarPersonagens(termo: String): List<Personagem> {
        val corpo = getJson("$BASE/characters?search=${codificar(termo)}&page_size=25")
        val dados = JSONObject(corpo).optJSONArray("data") ?: return emptyList()
        val lista = ArrayList<Personagem>(dados.length())
        for (indice in 0 until dados.length()) {
            val item = dados.optJSONObject(indice) ?: continue
            val nome = item.optString("name").trim()
            if (nome.isEmpty()) continue
            lista.add(
                Personagem(
                    nome = nome,
                    especie = textoOuNaoInformado(item, "species"),
                    casa = textoOuNaoInformado(item, "house")
                )
            )
        }
        return lista
    }

    fun buscarCriaturaPorNomeCompleto(nomeCompleto: String): Criatura? {
        val corpo = getJson("$BASE/creatures?search=${codificar(nomeCompleto)}&page_size=100")
        val dados = JSONObject(corpo).optJSONArray("data") ?: return null
        for (indice in 0 until dados.length()) {
            val item = dados.optJSONObject(indice) ?: continue
            val nome = item.optString("name").trim()
            if (!nome.equals(nomeCompleto, ignoreCase = true)) continue
            val imagem = if (item.isNull("image")) {
                null
            } else {
                item.optString("image").trim().ifEmpty { null }
            }
            return Criatura(nome = nome, imagemUrl = imagem)
        }
        return null
    }

    fun baixarImagem(urlImagem: String): Bitmap? {
        val estatica = montarUrlEstatica(urlImagem)
        if (!estatica.isNullOrBlank()) {
            baixarBitmap(estatica)?.let { return it }
        }
        val pelaApi = resolverUrlPelaApi(urlImagem)?.let { urlCompativel(it) }
        if (!pelaApi.isNullOrBlank() && pelaApi != estatica) {
            baixarBitmap(pelaApi)?.let { return it }
        }
        val original = urlCompativel(urlImagem)
        if (original != estatica && original != pelaApi) {
            baixarBitmap(original)?.let { return it }
        }
        Log.w(TAG, "Imagem indisponivel para $urlImagem")
        return null
    }

    private fun baixarBitmap(urlImagem: String): Bitmap? {
        val endereco = urlCompativel(urlImagem)
        return try {
            val pedido = Request.Builder()
                .url(endereco)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "image/png,image/jpeg")
                .header("Referer", "https://harrypotter.fandom.com/")
                .build()
            clienteImagem.newCall(pedido).execute().use { resposta ->
                if (!resposta.isSuccessful) {
                    Log.w(TAG, "Imagem HTTP ${resposta.code} em $endereco")
                    return null
                }
                val tipo = resposta.header("Content-Type")?.lowercase().orEmpty()
                if (tipo.contains("text/html") || tipo.contains("json") || tipo.contains("avif") || tipo.contains("webp")) {
                    Log.w(TAG, "Imagem com tipo $tipo em $endereco")
                    return null
                }
                val bytes = resposta.body?.bytes()
                if (bytes == null || bytes.isEmpty() || bytes.size > MAX_IMAGEM_BYTES) return null
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    Log.w(TAG, "Nao decodificou ${bytes.size} bytes ($tipo) de $endereco")
                }
                bitmap
            }
        } catch (erro: IOException) {
            Log.w(TAG, "Falha ao baixar $endereco: ${detalhe(erro)}")
            null
        }
    }

    private fun resolverNome(hostname: String): List<InetAddress> {
        val peloSistema = try {
            Dns.SYSTEM.lookup(hostname).filterIsInstance<Inet4Address>()
        } catch (_: UnknownHostException) {
            emptyList()
        }
        if (peloSistema.isNotEmpty()) return peloSistema
        val direto = consultarDnsDireto(hostname)
        if (direto.isNotEmpty()) {
            Log.i(TAG, "DNS direto $hostname -> ${direto.joinToString { it.hostAddress ?: "" }}")
            return direto
        }
        throw UnknownHostException(hostname)
    }

    private fun consultarDnsDireto(hostname: String): List<InetAddress> {
        for (servidor in SERVIDORES_DNS) {
            try {
                val encontrados = perguntarDns(hostname, servidor)
                if (encontrados.isNotEmpty()) return encontrados
            } catch (erro: Exception) {
                Log.w(TAG, "DNS $servidor falhou para $hostname: ${detalhe(erro)}")
            }
        }
        return emptyList()
    }

    private fun perguntarDns(hostname: String, servidor: String): List<InetAddress> {
        val consulta = montarConsultaDns(hostname)
        DatagramSocket().use { socket ->
            socket.soTimeout = 4_000
            val destino = InetAddress.getByName(servidor)
            socket.send(DatagramPacket(consulta, consulta.size, destino, 53))
            val buffer = ByteArray(512)
            val recebido = DatagramPacket(buffer, buffer.size)
            socket.receive(recebido)
            return lerRespostaDns(buffer, recebido.length, hostname)
        }
    }

    private fun montarConsultaDns(hostname: String): ByteArray {
        val saida = ByteArrayOutputStream()
        saida.write(byteArrayOf(0x13, 0x37, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (parte in hostname.split('.')) {
            val texto = parte.toByteArray(StandardCharsets.US_ASCII)
            saida.write(texto.size)
            saida.write(texto)
        }
        saida.write(0)
        saida.write(byteArrayOf(0x00, 0x01, 0x00, 0x01))
        return saida.toByteArray()
    }

    private fun lerRespostaDns(dados: ByteArray, tamanho: Int, hostname: String): List<InetAddress> {
        if (tamanho < 12) return emptyList()
        val perguntas = u16(dados, 4)
        val respostas = u16(dados, 6)
        var posicao = 12
        repeat(perguntas) {
            posicao = pularNomeDns(dados, tamanho, posicao) + 4
        }
        val enderecos = ArrayList<InetAddress>()
        repeat(respostas) {
            posicao = pularNomeDns(dados, tamanho, posicao)
            if (posicao + 10 > tamanho) return enderecos
            val tipo = u16(dados, posicao)
            val classe = u16(dados, posicao + 2)
            val comprimento = u16(dados, posicao + 8)
            val inicio = posicao + 10
            if (tipo == 1 && classe == 1 && comprimento == 4 && inicio + 4 <= tamanho) {
                enderecos.add(InetAddress.getByAddress(hostname, dados.copyOfRange(inicio, inicio + 4)))
            }
            posicao = inicio + comprimento
        }
        return enderecos
    }

    private fun pularNomeDns(dados: ByteArray, tamanho: Int, inicio: Int): Int {
        var posicao = inicio
        while (posicao < tamanho) {
            val tamanhoRotulo = dados[posicao].toInt() and 0xff
            if (tamanhoRotulo == 0) return posicao + 1
            if (tamanhoRotulo and 0xC0 == 0xC0) return posicao + 2
            posicao += tamanhoRotulo + 1
        }
        return posicao
    }

    private fun u16(dados: ByteArray, inicio: Int): Int {
        return ((dados[inicio].toInt() and 0xff) shl 8) or (dados[inicio + 1].toInt() and 0xff)
    }

    private fun montarUrlEstatica(urlImagem: String): String? {
        val bruto = extrairNomeArquivo(urlImagem) ?: extrairNomeDeUrlEstatica(urlImagem) ?: return null
        val arquivo = normalizarArquivo(bruto)
        val wiki = extrairWiki(urlImagem) ?: "harrypotter"
        val hash = md5(arquivo)
        val nome = codificarCaminho(arquivo)
        return "https://static.wikia.nocookie.net/$wiki/images/${hash[0]}/${hash.take(2)}/$nome/revision/latest?format=original"
    }

    private fun normalizarArquivo(nome: String): String {
        return nome.trim().replace(' ', '_')
    }

    private fun codificarCaminho(arquivo: String): String {
        return URLEncoder.encode(arquivo, StandardCharsets.UTF_8.name()).replace("+", "_")
    }

    private fun urlCompativel(urlImagem: String): String {
        if (!urlImagem.contains("nocookie.net")) return urlImagem
        if (urlImagem.contains("format=original")) return urlImagem
        val semConsulta = urlImagem.substringBefore('?').substringBefore('#')
        val latest = if (semConsulta.contains("/revision/")) {
            semConsulta.replace(Regex("/revision/.*$"), "/revision/latest")
        } else {
            "$semConsulta/revision/latest"
        }
        return "$latest?format=original"
    }

    private fun extrairNomeDeUrlEstatica(urlImagem: String): String? {
        val marca = "/images/"
        val indice = urlImagem.indexOf(marca)
        if (indice < 0) return null
        val partes = urlImagem.substring(indice + marca.length).split('/')
        if (partes.size < 3) return null
        val nome = partes[2].substringBefore('?').substringBefore('#')
        if (nome.isBlank() || nome.equals("revision", ignoreCase = true)) return null
        return URLDecoder.decode(nome, StandardCharsets.UTF_8.name()).trim().ifBlank { null }
    }

    private fun resolverUrlPelaApi(urlImagem: String): String? {
        val bruto = extrairNomeArquivo(urlImagem) ?: extrairNomeDeUrlEstatica(urlImagem) ?: return null
        val arquivo = normalizarArquivo(bruto)
        val wiki = extrairWiki(urlImagem) ?: return null
        return try {
            val endereco = "https://$wiki.fandom.com/api.php?action=query&format=json" +
                "&prop=imageinfo&iiprop=url&iilimit=1&titles=File:${codificar(arquivo)}"
            val paginas = JSONObject(getJson(endereco))
                .getJSONObject("query")
                .getJSONObject("pages")
            val chaves = paginas.keys()
            while (chaves.hasNext()) {
                val info = paginas.getJSONObject(chaves.next()).optJSONArray("imageinfo") ?: continue
                if (info.length() == 0) continue
                val url = info.getJSONObject(0).optString("url").trim()
                if (url.startsWith("http")) return url
            }
            null
        } catch (erro: Exception) {
            Log.w(TAG, "Fandom API nao resolveu $arquivo: ${detalhe(erro)}")
            null
        }
    }

    private fun extrairWiki(urlImagem: String): String? {
        val host = try {
            URL(urlImagem).host
        } catch (_: Exception) {
            return null
        }
        if (!host.endsWith(".fandom.com")) return null
        return host.removeSuffix(".fandom.com").ifBlank { null }
    }

    private fun md5(texto: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(texto.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun extrairNomeArquivo(urlImagem: String): String? {
        val marcadores = listOf("/Special:FilePath/", "/Special:Redirect/file/", "/wiki/File:")
        for (marcador in marcadores) {
            val indice = urlImagem.indexOf(marcador, ignoreCase = true)
            if (indice < 0) continue
            var nome = urlImagem.substring(indice + marcador.length)
            val corte = nome.indexOfAny(charArrayOf('?', '#'))
            if (corte >= 0) nome = nome.substring(0, corte)
            nome = URLDecoder.decode(nome, StandardCharsets.UTF_8.name()).trim()
            if (nome.isNotEmpty()) return nome
        }
        return null
    }

    private fun textoOuNaoInformado(item: JSONObject, campo: String): String {
        if (!item.has(campo) || item.isNull(campo)) return NAO_INFORMADO
        val valor = item.optString(campo).trim()
        return if (valor.isEmpty()) NAO_INFORMADO else valor
    }

    private fun getJson(endereco: String): String {
        val conexao = conectar(endereco, aceitarImagem = false)
        try {
            val codigo = conexao.responseCode
            val fluxo = if (codigo in 200..299) conexao.inputStream else conexao.errorStream
            val corpo = fluxo?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (codigo !in 200..299 || corpo.isBlank()) throw FalhaConsultaException()
            return corpo
        } catch (erro: FalhaConsultaException) {
            throw erro
        } catch (_: IOException) {
            throw FalhaConsultaException()
        } finally {
            conexao.disconnect()
        }
    }

    private fun detalhe(erro: Throwable): String {
        return generateSequence(erro) { it.cause }
            .joinToString(" | ") { causa -> "${causa.javaClass.simpleName}: ${causa.message}" }
    }

    private fun conectar(enderecoInicial: String, aceitarImagem: Boolean): HttpURLConnection {
        var endereco = if (enderecoInicial.startsWith("http://")) {
            "https://" + enderecoInicial.removePrefix("http://")
        } else {
            enderecoInicial
        }
        var ultima: HttpURLConnection? = null
        repeat(5) {
            ultima?.disconnect()
            val conexao = abrir(endereco, aceitarImagem)
            ultima = conexao
            val codigo = conexao.responseCode
            if (codigo !in REDIRECIONAMENTOS) return conexao
            val destino = conexao.getHeaderField("Location")
            if (destino.isNullOrBlank()) return conexao
            endereco = URL(URL(endereco), destino).toString()
            if (endereco.startsWith("http://")) {
                endereco = "https://" + endereco.removePrefix("http://")
            }
        }
        return ultima ?: throw IOException("Sem conexao para $enderecoInicial")
    }

    private fun abrir(endereco: String, aceitarImagem: Boolean): HttpURLConnection {
        return (URL(endereco).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty(
                "Accept",
                if (aceitarImagem) "image/png,image/jpeg" else "application/json"
            )
            setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            if (aceitarImagem) {
                setRequestProperty("Referer", "https://harrypotter.fandom.com/")
            }
        }
    }

    private fun codificar(valor: String): String {
        return URLEncoder.encode(valor, StandardCharsets.UTF_8.name())
    }

    private fun lerAte(entrada: InputStream, limite: Int): ByteArray? {
        entrada.use { fluxo ->
            val buffer = ByteArrayOutputStream()
            val bloco = ByteArray(8192)
            var total = 0
            while (true) {
                val lido = fluxo.read(bloco)
                if (lido < 0) break
                if (total + lido > limite) return null
                buffer.write(bloco, 0, lido)
                total += lido
            }
            return buffer.toByteArray()
        }
    }
}
