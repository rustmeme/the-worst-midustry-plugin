package db

import arc.Core
import arc.util.Time
import cfg.Globals
import db.Driver.Progress
import db.Driver.Users
import discord4j.common.util.Snowflake
import game.commands.Discord
import game.commands.Profile
import game.u.User
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mindustry.gen.Call
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.contracts.contract
import kotlin.reflect.full.declaredMemberProperties

abstract class Quest(val name: String, val permanent: Boolean = true) {
    companion object {
        const val complete = "c"
    }

    fun points(amount: Long, user: Driver.RawUser): String {
        return if(amount > 0) {
            user.translate("quest.notEnough", amount)
        } else {
            complete
        }
    }

    fun long(l: Any): Long? {
        return when(l){
            is Long -> l
            is Double -> l.toLong()
            is Int -> l.toLong()
            else -> null
        }
    }

    abstract fun check(user: Driver.RawUser, value: Any): String

    class Stat(name: String): Quest(name) {
        override fun check(user: Driver.RawUser, value: Any): String {
            val v = long(value) ?: return user.translate("quest.error")
            return try {
                points(v - user.stats::class.declaredMemberProperties.find { it.name == name }!!.getter.call(user.stats) as Long, user)
            } catch (e: Exception) {
                return user.translate("quest.internalError", e.message ?: "no message")
            }
        }
    }

    class Quests(val ranks: Ranks, val driver: Driver): HashMap<String, Quest>() {
        val input = Channel<User?>()
        val nonPermanent = HashSet<String>()
        lateinit var discord: Discord

        fun reg(q: Quest) {
            put(q.name, q)
            if(!q.permanent) nonPermanent.add(q.name)
        }

        init {
            reg(Stat("built"))
            reg(Stat("destroyed"))
            reg(Stat("killed"))
            reg(Stat("deaths"))
            reg(Stat("played"))
            reg(Stat("wins"))
            reg(Stat("messages"))
            reg(Stat("commands"))
            reg(Stat("playTime"))
            reg(Stat("silence"))

            reg(object: Quest("age") {
                override fun check(user: Driver.RawUser, value: Any): String {
                    val v = long(value) ?: return user.translate("quest.error")
                    return points(v - user.age, user)
                }
            })

            reg(object: Quest("ranks") {
                override fun check(user: Driver.RawUser, value: Any): String {
                    if(value !is String) return user.translate("quest.error")
                    val shouldHave = value.split(" ")
                    val missing = StringBuilder()
                    for(s in shouldHave) {
                        if (!user.specials.contains(s)) missing.append(s).append(" ")
                    }

                    return if(missing.isNotEmpty()) {
                        return user.translate("quest.missing", missing.toString())
                    } else {
                        complete
                    }
                }
            })

            reg(object: Quest("rankCount") {
                override fun check(user: Driver.RawUser, value: Any): String {
                    val v = long(value) ?: return user.translate("quest.error")
                    return points(v - user.specials.size, user)
                }
            })

            reg(object: Quest("rankTotalValue") {
                override fun check(user: Driver.RawUser, value: Any): String {
                    val v = long(value) ?: return user.translate("quest.error")
                    return points(v - user.rankValue(ranks), user)
                }
            })

            reg(object: Quest("points") {
                override fun check(user: Driver.RawUser, value: Any): String {
                    val v = long(value) ?: return user.translate("quest.error")
                    return points(v - user.points(ranks, driver.config.multiplier), user)
                }
            })

            reg(object: Quest("roles", false) {
                override fun check(user: Driver.RawUser, value: Any): String {
                    if(user.discord == Users.noDiscord) return user.translate("quest.roles.none")
                    if(discord.handler == null) return user.translate("quest.roles.inactive")
                    if(value !is String) return user.translate("quest.error")

                    val roles = value.split(";")

                    val gid = discord.handler!!.gateway.guilds.blockFirst()?.id ?: return user.translate("quest.roles.missingGuild")
                    val u = discord.handler!!.gateway.getMemberById(gid, Snowflake.of(user.discord)).block() ?: return user.translate("quest.roles.invalid")

                    val roleSet = u.roles.collectList().block()?.map { it.name }?.toSet() ?: return user.translate("quest.roles.noRoles")
                    val sb = StringBuilder()
                    for(r in roles) {
                        if(!roleSet.contains(r)) sb.append(r).append(" ")
                    }

                    if(sb.isNotEmpty()) {
                        return user.translate("quest.roles.missing", sb.toString())
                    }

                    return complete
                }
            })

            runBlocking {
                GlobalScope.launch {
                    while(true) {
                        val user = input.receive() ?: break
                        outer@ for((k, v) in ranks) {
                            if (v.kind != Ranks.Kind.Special) continue
                            val contains = user.data.specials.contains(k)
                            if(contains && v.permanent) continue

                            for ((n, a) in v.quest) {
                                val quest = get(n)
                                if(quest == null) {
                                    Call.sendMessage(n)
                                    continue
                                }
                                if (quest.permanent && contains) continue
                                val message = quest.check(user.data, a)
                                if (message != complete) {
                                    continue@outer
                                }
                            }

                            if (Globals.testing) {
                                user.data.specials.add(k)
                            } else Core.app.post {
                                user.data.specials.add(k)
                                user.send("quest.obtained", v.postfix)
                            }
                        }
                    }
                }
            }
        }
    }
}