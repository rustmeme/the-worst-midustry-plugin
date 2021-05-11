package db

import java.io.IOException
import com.beust.klaxon.Klaxon
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.update
import java.net.URL
import db.Driver.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope

// Outlook handles user localization
class Outlook() {
    val input = Channel<Request?>()

    // Spawns an outlook worker
    init {
        runBlocking {
            GlobalScope.launch {
                while (true) {
                    val inp = input.receive() ?: break
                    val data = localize(inp.ip)
                    User.update({ User.id eq inp.id }) {
                        it[country] = data.country
                        it[locale] = data.locale
                    }
                }
            }
        }
    }

    // localize tries to get locale by ip address
    fun localize(ip: String): Data {
        return try {
            val text = URL("http://ipapi.co/$ip/json").readText()
            val resp = Klaxon().parse<Response>(text)!!

            val parts = resp.languages.split(",")
            val locale =
                if(parts.isNotEmpty()) parts[0].replace('-', '_')
                else resp.languages

            Data(resp.country_name, locale)
        } catch (e: IOException) {
            Data(User.defaultCountry, User.defaultLocale)
        }
    }

    class Data(val country: String, val locale: String)
    class Response(val country_name: String = User.defaultCountry, val languages: String = User.defaultLocale)
    class Request(val ip: String, val id: Long)
}