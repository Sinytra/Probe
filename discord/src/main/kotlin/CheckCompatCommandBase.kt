package org.sinytra

import dev.kord.common.Color
import dev.kord.core.behavior.interaction.response.DeferredPublicMessageInteractionResponseBehavior
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.rest.builder.message.embed
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.toJavaLocalDateTime
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(FormatStringsInDatetimeFormats::class)
abstract class CheckCompatCommandBase {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(CheckCompatCommandBase::class.java)
    }
    
    suspend fun checkCompat(response: DeferredPublicMessageInteractionResponseBehavior, platform: String, slug: String) {
        val result = try {
            TransformRunner.runTransformation(platform, slug)
        } catch (e: ProjectNotFoundException) {
            response.respond { 
                content = ":warning: Project `${slug}` not found!"
            }
            return
        } catch (e: Exception) {
            LOGGER.error("Error transforming {} project {}: {}", platform, slug, e)

            response.respond { 
                content = ":x: Internal server error"
            }
            return
        }

        val green = Color(0, 255, 0)
        val red = Color(255, 0, 0)
        val link = result.projectUrl

        response.respond {
            embed {
                title = "Compatibility check results"
                color = if (result.passing) green else red
                url = link
                thumbnail {
                    url = result.iconUrl
                }

                field {
                    name = "Mod ID"
                    value = "`${result.modid}`"
                }
                field {
                    name = "Compatibility"
                    value = if (result.passing) "\u2705 Compatible" else "\u274C Incompatible"
                }
                field {
                    name = "Project page"
                    value = link
                }
                field {
                    name = "Mod version"
                    value = "(unknown)"
                    inline = true
                }
                field {
                    name = "Game version"
                    value = result.gameVersion
                    inline = true
                }
                field {
                    name = "Last tested at"
                    value = result.createdAt.toJavaLocalDateTime().format(
                        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)
                    )
                }
            }
        }
    }
}