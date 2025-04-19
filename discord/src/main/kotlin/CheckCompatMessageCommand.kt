package org.sinytra

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.entity.application.GlobalMessageCommand
import dev.kord.core.event.interaction.GuildMessageCommandInteractionCreateEvent

private data class ModTestArgs(val platform: String, val slug: String)

class CheckCompatMessageCommand : CheckCompatCommandBase() {
    companion object {
        private val MODRINTH_PATTERN = Regex("https://modrinth\\.com/mod/(.+)")
        
        private val PLATFORMS = mapOf(
            "modrinth" to MODRINTH_PATTERN
        )
    }

    suspend fun register(kord: Kord): GlobalMessageCommand {
        return kord.createGlobalMessageCommand(
            "Check Mod Compatibility"
        )
    }

    suspend fun GuildMessageCommandInteractionCreateEvent.handle() {
        val msgContent = interaction.target.fetchMessage().content

        val args = getModArgs(msgContent)

        if (args != null) {
            val response = interaction.deferPublicResponse()

            checkCompat(response, args.platform, args.slug)
        } else {
            interaction.respondEphemeral {
                content = "Invalid message"
            }
        }
    }

    private fun getModArgs(content: String): ModTestArgs? =
        PLATFORMS.firstNotNullOfOrNull { (key, value) -> 
            value.find(content)
                ?.groups?.lastOrNull()?.value
                ?.let { ModTestArgs(key, it) }
        }
}