package com.cczxcsc

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.*
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.ErrorResponse
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

@Configuration
class WebConfig(private val auth: AuthService) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(object : HandlerInterceptor {
            override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
                request.setAttribute("operator", auth.username(request.getHeader("Authorization")))
                return true
            }
        }).addPathPatterns("/api/admin/**").excludePathPatterns("/api/admin/login")
    }
}

@Component
class RequestLimits(private val json: ObjectMapper) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        response.setHeader("X-Content-Type-Options", "nosniff")
        if (request.requestURI.startsWith("/api/")) response.setHeader("Cache-Control", "no-store")
        if (request.method !in setOf("POST", "PATCH", "PUT")) { chain.doFilter(request, response); return }
        val limit = 512 * 1024
        val body = if (request.contentLengthLong > limit) null else request.inputStream.readNBytes(limit + 1)
        if (body == null || body.size > limit) {
            response.status = 413
            response.contentType = "application/json;charset=UTF-8"
            json.writeValue(response.outputStream, mapOf("error" to "提交内容过大"))
            return
        }
        val wrapper = object : HttpServletRequestWrapper(request) {
            override fun getInputStream(): ServletInputStream = object : ServletInputStream() {
                val stream = ByteArrayInputStream(body)
                override fun read(): Int = stream.read()
                override fun read(bytes: ByteArray, off: Int, len: Int): Int = stream.read(bytes, off, len)
                override fun isFinished(): Boolean = stream.available() == 0
                override fun isReady(): Boolean = true
                override fun setReadListener(listener: ReadListener?) { throw UnsupportedOperationException("仅支持同步请求") }
            }
            override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        }
        chain.doFilter(wrapper, response)
    }
}

@Controller
class PageController {
    @GetMapping("/admin") fun admin(): String = "forward:/admin.html"
}

@RestController
@RequestMapping("/api")
class ApiController(private val service: SubmissionService, private val auth: AuthService, private val exports: ExportService,
    private val repo: SubmissionRepository, private val publications: PublicationService, private val server: ServerInfoService,
    private val audit: AuditService) {
    private fun actor(request: HttpServletRequest) = request.getAttribute("operator") as String
    @GetMapping("/health") fun health(): Map<String, Any> {
        repo.jdbc.queryForObject("SELECT 1", Int::class.java)
        return mapOf("ok" to true, "time" to nowIso())
    }
    @GetMapping("/meta") fun meta() = service.meta()
    @GetMapping("/announcements") fun announcements() = publications.list()
    @PostMapping("/submissions") fun submit(@RequestBody input: SubmissionInput) = service.create(input)
    @GetMapping("/submissions/track") fun track(@RequestParam(defaultValue = "") code: String, @RequestParam(defaultValue = "") author: String) = service.track(code, author)
    @PostMapping("/submissions/track") fun trackPost(@RequestBody body: Map<String, String>) = service.track(body["code"].orEmpty(), body["author"].orEmpty())
    @PostMapping("/admin/login") fun login(@RequestBody body: Map<String, String>, request: HttpServletRequest) = auth.login(body["password"].orEmpty(), request.remoteAddr, body["username"].orEmpty())
    @GetMapping("/admin/me") fun me(request: HttpServletRequest) = mapOf("username" to actor(request))
    @GetMapping("/admin/audit") fun history(@RequestParam params: Map<String, String>) = audit.list(params)
    @PostMapping("/admin/logout") fun logout(request: HttpServletRequest): Map<String, Boolean> { auth.logout(request.getHeader("Authorization")); return mapOf("ok" to true) }
    @PostMapping("/admin/password") fun password(@RequestBody body: Map<String, String>, request: HttpServletRequest): Map<String, Boolean> { auth.changePassword(body["old_password"].orEmpty(), body["new_password"].orEmpty(), actor(request)); return mapOf("ok" to true) }
    @GetMapping("/admin/submissions") fun list(@RequestParam params: Map<String, String>) = service.list(params)
    @PostMapping("/admin/submissions") fun register(@RequestBody input: SubmissionInput, request: HttpServletRequest) = service.create(input, admin = true, actor = actor(request))
    @GetMapping("/admin/review-summary") fun reviewSummary() = service.reviewSummary()
    @PostMapping("/admin/submissions/batch") fun batch(@RequestBody body: BatchInput, request: HttpServletRequest) = service.batch(body, actor(request))
    @PostMapping("/admin/submissions/approve-all") fun approveAll(@RequestBody body: BatchInput, request: HttpServletRequest) = service.approveAll(body, actor(request))
    @GetMapping("/admin/schedule") fun schedule() = service.schedule()
    @GetMapping("/admin/server") fun server() = server.info()
    @GetMapping("/admin/announcements") fun messages() = publications.list(public = false)
    @PostMapping("/admin/announcements") fun publish(@RequestBody input: AnnouncementInput, request: HttpServletRequest) = publications.publish(input, actor(request))
    @PatchMapping("/admin/announcements/{id}") fun visibility(@PathVariable id: Long, @RequestBody body: Map<String, Boolean>, request: HttpServletRequest) =
        publications.visibility(id, body["published"] ?: throw ApiError(400, "请指定消息发布状态"), actor(request))
    @DeleteMapping("/admin/announcements/{id}") fun deleteMessage(@PathVariable id: Long, request: HttpServletRequest) = publications.delete(id, actor(request))
    @PatchMapping("/admin/submissions/{id}") fun update(@PathVariable id: Long, @RequestBody body: Map<String, Any?>, request: HttpServletRequest) = service.update(id, body, actor(request))
    @DeleteMapping("/admin/submissions/{id}") fun delete(@PathVariable id: Long, request: HttpServletRequest) = service.delete(id, actor(request))
    @GetMapping("/admin/stats") fun stats() = service.stats()
    @GetMapping("/admin/export") fun export(@RequestParam params: Map<String, String>): ResponseEntity<ByteArray> {
        val file = exports.export(service.exportItems(params), params["format"] ?: "csv")
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment().filename(file.filename, Charsets.UTF_8).build().toString())
            .body(file.bytes)
    }
}

@RestControllerAdvice
class ApiErrors {
    private val log = LoggerFactory.getLogger(ApiErrors::class.java)
    @ExceptionHandler(ApiError::class)
    fun api(error: ApiError) = ResponseEntity.status(error.status).body(mapOf("error" to error.message))
    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun malformed(error: Exception) = ResponseEntity.badRequest().body(mapOf("error" to "请求格式有误"))
    @ExceptionHandler(Exception::class)
    fun unexpected(error: Exception): ResponseEntity<Map<String, String>> {
        if (error is ErrorResponse) return ResponseEntity.status(error.statusCode).body(mapOf("error" to when (error.statusCode.value()) {
            404 -> "接口或页面不存在"
            405 -> "不支持的请求方法"
            else -> "请求格式有误"
        }))
        log.error("接口处理失败", error)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("error" to "服务器内部错误"))
    }
}
