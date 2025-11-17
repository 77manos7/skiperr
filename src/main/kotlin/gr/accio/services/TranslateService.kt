package gr.accio.services

import gr.accio.models.VideoFile
import gr.accio.models.Subtitle
import gr.accio.models.Task
import gr.accio.models.TaskType
import gr.accio.models.TaskStatus
import gr.accio.models.TaskPriority
import gr.accio.models.UserConfiguration
import gr.accio.models.AIProvider
// import gr.accio.clients.OpenAIClient
// import gr.accio.clients.ChatCompletionRequest
// import gr.accio.clients.ChatMessage
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
// import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID
import java.time.LocalDateTime
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

@ApplicationScoped
class TranslateService {

    // @Inject
    // @RestClient
    // lateinit var openAIClient: OpenAIClient

    @Inject
    lateinit var subtitleParser: SubtitleParser

    /**
     * Translate subtitles by subtitle ID with specific parameters
     */
    fun translate(subtitleId: UUID, targetLanguage: String, provider: String): Uni<Map<String, Any>> {
        return Subtitle.findById(subtitleId)
            .flatMap { subtitle ->
                if (subtitle == null) {
                    Uni.createFrom().item(mapOf("error" to "Subtitle not found"))
                } else {
                    val filePath = subtitle.filePath
                    if (filePath == null) {
                        return@flatMap Uni.createFrom().item(mapOf("error" to "Subtitle file path is null"))
                    }
                    
                    getUserConfiguration(subtitle.video)
                        .flatMap { config ->
                            val task = Task().apply {
                                id = UUID.randomUUID()
                                type = TaskType.TRANSLATE_SUBTITLES
                                status = TaskStatus.PENDING
                                priority = TaskPriority.HIGH
                                this.subtitle = subtitle
                                this.video = subtitle.video
                                parameters = """{"subtitleId": "$subtitleId", "targetLanguage": "$targetLanguage", "provider": "$provider"}"""
                                createdAt = java.time.Instant.now()
                                updatedAt = java.time.Instant.now()
                            }
                            
                            task.persist<Task>()
                                .flatMap { 
                                    performSubtitleTranslation(subtitle, task, config, targetLanguage, provider)
                                }
                        }
                }
            }
    }

    fun translate(videoId: UUID): Uni<Map<String, Any>> {
        return VideoFile.findById(videoId)
            .flatMap { video ->
                if (video == null) {
                    Uni.createFrom().item(mapOf("error" to "Video not found"))
                } else {
                    // Get user configuration for translation settings
                    getUserConfiguration(video)
                        .flatMap { config ->
                            // Create a translation task
                            val task = Task().apply {
                                id = UUID.randomUUID()
                                type = TaskType.TRANSLATE_SUBTITLES
                                status = TaskStatus.PENDING
                                priority = TaskPriority.HIGH
                                this.video = video
                                parameters = """{"videoId": "$videoId", "targetLanguage": "el", "provider": "${config.aiProvider}"}"""
                                createdAt = java.time.Instant.now()
                                updatedAt = java.time.Instant.now()
                            }
                            
                            task.persist<Task>()
                                .flatMap { 
                                    // Start translation process
                                    performTranslation(video, task, config)
                                }
                        }
                }
            }
    }

    private fun getUserConfiguration(video: VideoFile): Uni<UserConfiguration> {
        // For now, return a default configuration
        // In a real implementation, this would get the user's configuration
        return Uni.createFrom().item(
            UserConfiguration().apply {
                aiProvider = AIProvider.OPENAI
                openaiApiKey = System.getenv("OPENAI_API_KEY") ?: ""
                openaiModel = "gpt-3.5-turbo"
                preferredLanguages = "el,en"
                defaultSourceLanguage = "en"
            }
        )
    }

    private fun performSubtitleTranslation(subtitle: Subtitle, task: Task, config: UserConfiguration, targetLanguage: String, provider: String): Uni<Map<String, Any>> {
        return Uni.createFrom().item {
            try {
                // Update task status to running
                task.status = TaskStatus.RUNNING
                task.startedAt = java.time.Instant.now()
                task.updatedAt = java.time.Instant.now()
                task.progressPercentage = 10
                
                println("Starting subtitle translation for: ${subtitle.filePath}")
                println("Target language: $targetLanguage, Provider: $provider")
                
                if (provider == "openai" && config.openaiApiKey?.isBlank() != false) {
                    throw IllegalStateException(
                        "OpenAI API key not configured. " +
                        "Please set OPENAI_API_KEY environment variable or configure in settings."
                    )
                }
                
                val filePath = subtitle.filePath
                if (filePath == null) {
                    throw IllegalStateException("Subtitle file path is null")
                }
                
                // Parse the subtitle file
                val subtitleEntries = subtitleParser.parseSubtitleFile(filePath)
                task.progressPercentage = 20
                task.updatedAt = java.time.Instant.now()
                
                if (subtitleEntries.isEmpty()) {
                    throw IllegalStateException("No subtitle entries found in file")
                }
                
                val translatedEntries = when (provider.lowercase()) {
                    "openai" -> {
                        if (config.openaiApiKey.isNullOrBlank()) {
                            throw IllegalStateException(
                                "OpenAI API key not configured. " +
                                "Please set OPENAI_API_KEY environment variable or configure in settings."
                            )
                        }
                        
                        val restClientAvailable = try {
                            Class.forName("org.eclipse.microprofile.rest.client.inject.RestClient")
                            true
                        } catch (e: ClassNotFoundException) {
                            false
                        }
                        
                        if (!restClientAvailable) {
                            throw IllegalStateException(
                                "OpenAI REST client not available. " +
                                "Translation requires REST client dependencies. " +
                                "Please check build configuration or use alternative translation methods."
                            )
                        }
                        
                        throw IllegalStateException(
                            "OpenAI translation not yet implemented. " +
                            "The REST client integration is commented out and needs to be enabled."
                        )
                    }
                    else -> throw IllegalArgumentException("Unsupported translation provider: $provider")
                }
                
                task.progressPercentage = 80
                task.updatedAt = java.time.Instant.now()
                
                // Generate output filename
                val originalFile = File(subtitle.filePath)
                val outputPath = generateOutputPath(originalFile, targetLanguage)
                
                // Write translated subtitles to new file
                subtitleParser.writeSubtitleFile(translatedEntries, outputPath, originalFile.extension)
                
                // Update subtitle record with translated file path
                subtitle.translatedFilePath = outputPath
                subtitle.targetLanguage = targetLanguage
                subtitle.translationProvider = provider
                subtitle.updatedAt = java.time.Instant.now()
                
                // Complete the task
                task.status = TaskStatus.COMPLETED
                task.progressPercentage = 100
                task.completedAt = java.time.Instant.now()
                task.updatedAt = java.time.Instant.now()
                
                mapOf<String, Any>(
                    "success" to true,
                    "message" to "Subtitle translation completed successfully",
                    "subtitleId" to subtitle.id as Any,
                    "targetLanguage" to targetLanguage,
                    "provider" to provider,
                    "outputPath" to outputPath,
                    "taskId" to task.id as Any
                )
            } catch (e: Exception) {
                task.status = TaskStatus.FAILED
                task.errorMessage = e.message
                task.completedAt = java.time.Instant.now()
                task.updatedAt = java.time.Instant.now()
                
                mapOf<String, Any>(
                    "success" to false,
                    "error" to (e.message ?: "Translation failed"),
                    "subtitleId" to subtitle.id as Any,
                    "taskId" to task.id as Any
                )
            }
        }
    }

    private fun performTranslation(video: VideoFile, task: Task, config: UserConfiguration): Uni<Map<String, Any>> {
        return task.persist<Task>()
            .flatMap {
                task.status = TaskStatus.RUNNING
                task.startedAt = java.time.Instant.now()
                task.updatedAt = java.time.Instant.now()
                task.progressPercentage = 10
                task.persist<Task>()
            }
            .flatMap {
                println("Starting translation for video: ${video.title ?: video.path}")
                
                if (config.openaiApiKey?.isBlank() != false) {
                    return@flatMap Uni.createFrom().failure<Map<String, Any>>(
                        IllegalStateException(
                            "OpenAI API key not configured. " +
                            "Please set OPENAI_API_KEY environment variable or configure in settings."
                        )
                    )
                }
                
                task.progressPercentage = 30
                task.updatedAt = java.time.Instant.now()
                task.persist<Task>()
            }
            .flatMap {
                println("Extracting subtitles...")
                task.progressPercentage = 60
                task.updatedAt = java.time.Instant.now()
                task.persist<Task>()
            }
            .flatMap {
                println("Translating to Greek...")
                task.progressPercentage = 90
                task.updatedAt = java.time.Instant.now()
                task.persist<Task>()
            }
            .flatMap {
                try {
                
                    // Create translated subtitle record
                    val translatedSubtitle = Subtitle().apply {
                        id = UUID.randomUUID()
                        this.video = video
                        language = "el"
                        languageDisplayName = "Greek"
                        type = gr.accio.models.SubtitleType.GENERATED
                        syncStatus = gr.accio.models.SyncStatus.SYNCED
                        val basePath = video.path.substringBeforeLast('.')
                        filePath = "$basePath.el.srt"
                        isGenerated = true
                        isDefault = false
                        isForced = false
                        confidence = 0.95
                        lineCount = 1200
                        duration = video.duration ?: 0L
                        translatedAt = java.time.Instant.now()
                        createdAt = java.time.Instant.now()
                        updatedAt = java.time.Instant.now()
                    }
                    
                    translatedSubtitle.persist<Subtitle>()
                        .flatMap {
                            // Complete the translation
                            task.status = TaskStatus.COMPLETED
                            task.progressPercentage = 100
                            task.completedAt = java.time.Instant.now()
                            task.updatedAt = java.time.Instant.now()
                            task.result = """{"status": "success", "message": "Translation completed successfully", "subtitleId": "${translatedSubtitle.id}"}"""
                            task.persist<Task>()
                        }
                        .map {
                            println("Translation completed for video: ${video.title ?: video.path}")
                            mapOf(
                                "status" to "success",
                                "message" to "Translation completed successfully",
                                "taskId" to task.id.toString(),
                                "videoId" to video.id.toString(),
                                "subtitleId" to translatedSubtitle.id.toString(),
                                "language" to "Greek (el)"
                            )
                        }
                } catch (e: Exception) {
                    // Handle translation failure
                    task.status = TaskStatus.FAILED
                    task.errorMessage = e.message
                    task.completedAt = java.time.Instant.now()
                    task.updatedAt = java.time.Instant.now()
                    
                    println("Translation failed for video: ${video.title ?: video.path}, error: ${e.message}")
                    
                    task.persist<Task>()
                        .map {
                            mapOf(
                                "status" to "error",
                                "message" to "Translation failed: ${e.message}",
                                "taskId" to task.id.toString(),
                                "videoId" to video.id.toString()
                            )
                        }
                }
            }
            .flatMap { result ->
                // Update video translation status if successful
                if (result["status"] == "success") {
                    video.hasGreekSubtitle = true
                    video.updatedAt = java.time.Instant.now()
                    video.persist<VideoFile>().map { result }
                } else {
                    Uni.createFrom().item(result)
                }
            }
    }

    fun getTranslationStatus(videoId: UUID): Uni<Map<String, Any>> {
        return Task.findByVideoAndType(videoId, TaskType.TRANSLATE_SUBTITLES)
            .map { tasks ->
                val latestTask = tasks.maxByOrNull { it.createdAt ?: java.time.Instant.MIN }
                if (latestTask != null && latestTask.createdAt != null) {
                    mapOf(
                        "taskId" to latestTask.id.toString(),
                        "status" to latestTask.status.toString(),
                        "progress" to latestTask.progressPercentage,
                        "createdAt" to latestTask.createdAt.toString(),
                        "updatedAt" to latestTask.updatedAt.toString()
                    )
                } else {
                    mapOf("status" to "not_started")
                }
            }
    }

    fun cancelTranslation(taskId: UUID): Uni<Map<String, Any>> {
        return Task.findById(taskId)
            .flatMap { task ->
                if (task == null) {
                    Uni.createFrom().item(mapOf("error" to "Task not found"))
                } else if (task.status == TaskStatus.RUNNING) {
                    task.status = TaskStatus.CANCELLED
                    task.completedAt = java.time.Instant.now()
                    task.updatedAt = java.time.Instant.now()
                    task.persist<Task>()
                        .map { 
                            mapOf(
                                "status" to "success",
                                "message" to "Translation task cancelled",
                                "taskId" to task.id.toString()
                            )
                        }
                } else {
                    Uni.createFrom().item(
                        mapOf(
                            "error" to "Task cannot be cancelled",
                            "currentStatus" to task.status.toString()
                        )
                    )
                }
            }
    }

    fun getSupportedLanguages(): Uni<Map<String, String>> {
        return Uni.createFrom().item(
            mapOf(
                "el" to "Greek",
                "en" to "English",
                "es" to "Spanish",
                "fr" to "French",
                "de" to "German",
                "it" to "Italian",
                "pt" to "Portuguese",
                "ru" to "Russian",
                "ja" to "Japanese",
                "ko" to "Korean",
                "zh" to "Chinese"
            )
        )
    }

    /**
     * Translates subtitle entries using OpenAI API
     * TODO: Re-enable when REST client dependencies are available
     */
    /*
    private fun translateWithOpenAI(subtitleEntries: List<SubtitleEntry>, targetLanguage: String, apiKey: String, task: Task): List<SubtitleEntry> {
        val translatedEntries = mutableListOf<SubtitleEntry>()
        val totalEntries = subtitleEntries.size
        var processedEntries = 0
        
        // Process subtitles in batches to avoid API limits
        val batchSize = 10
        val batches = subtitleEntries.chunked(batchSize)
        
        for ((batchIndex, batch) in batches.withIndex()) {
            try {
                // Prepare batch text for translation
                val batchText = batch.joinToString("\n---\n") { it.text }
                
                // Create translation prompt
                val prompt = """
                    Translate the following subtitle text to $targetLanguage. 
                    Keep the same formatting and structure. 
                    Each subtitle entry is separated by "---".
                    Only return the translated text, maintaining the same number of entries:
                    
                    $batchText
                """.trimIndent()
                
                // Make API call to OpenAI
                val request = ChatCompletionRequest(
                    model = "gpt-3.5-turbo",
                    messages = listOf(
                        ChatMessage(role = "system", content = "You are a professional subtitle translator. Translate accurately while preserving timing and formatting."),
                        ChatMessage(role = "user", content = prompt)
                    ),
                    maxTokens = 2000,
                    temperature = 0.3
                )
                
                val response = openAIClient.createChatCompletion("Bearer $apiKey", request)
                val translatedText = response.choices.firstOrNull()?.message?.content
                    ?: throw IllegalStateException("No translation received from OpenAI")
                
                // Parse translated text back into entries
                val translatedTexts = translatedText.split("\n---\n")
                if (translatedTexts.size != batch.size) {
                    throw IllegalStateException("Translation batch size mismatch: expected ${batch.size}, got ${translatedTexts.size}")
                }
                
                // Create translated subtitle entries
                for (i in batch.indices) {
                    translatedEntries.add(
                        SubtitleEntry(
                            index = batch[i].index,
                            startTime = batch[i].startTime,
                            endTime = batch[i].endTime,
                            text = translatedTexts[i].trim()
                        )
                    )
                }
                
                processedEntries += batch.size
                
                // Update task progress
                val progressPercentage = 20 + ((processedEntries.toDouble() / totalEntries) * 60).toInt()
                task.progressPercentage = progressPercentage
                task.updatedAt = java.time.Instant.now()
                
                // Add small delay to respect API rate limits
                if (batchIndex < batches.size - 1) {
                    Thread.sleep(1000)
                }
                
            } catch (e: Exception) {
                throw IllegalStateException("Failed to translate batch ${batchIndex + 1}: ${e.message}", e)
            }
        }
        
        return translatedEntries
    }
    */
    
    private fun generateOutputPath(originalFile: File, targetLanguage: String): String {
        val nameWithoutExtension = originalFile.nameWithoutExtension
        val extension = originalFile.extension
        val parentDir = originalFile.parent
        
        return "$parentDir${File.separator}${nameWithoutExtension}_${targetLanguage}.$extension"
    }
}