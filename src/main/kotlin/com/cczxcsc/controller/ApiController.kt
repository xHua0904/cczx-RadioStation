package com.cczxcsc.controller

import com.cczxcsc.common.ApiError
import com.cczxcsc.model.AnnouncementInput
import com.cczxcsc.model.BatchInput
import com.cczxcsc.model.ProgressInput
import com.cczxcsc.model.SubmissionInput
import com.cczxcsc.service.AuditService
import com.cczxcsc.service.AuthService
import com.cczxcsc.service.PublicationService
import com.cczxcsc.service.ServerInfoService
import com.cczxcsc.service.ServerLogService
import com.cczxcsc.service.SubmissionService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class ApiController(private val service: SubmissionService, private val auth: AuthService,
    private val publications: PublicationService, private val server: ServerInfoService,
    private val audit: AuditService, private val logs: ServerLogService) {
    private fun actor(request: HttpServletRequest) = request.getAttribute("operator") as String
    @GetMapping("/health") fun health() = server.health()
    @GetMapping("/meta") fun meta() = service.meta()
    @GetMapping("/announcements") fun announcements() = publications.list()
    @PostMapping("/submissions") fun submit(@RequestBody input: SubmissionInput) = service.create(input)
    @PostMapping("/submissions/progress") fun progress(@RequestBody input: ProgressInput) = service.progress(input)
    @GetMapping("/submissions/song-exists") fun songExists(@RequestParam(name = "song_name", defaultValue = "") name: String) = service.songExists(name)
    @GetMapping("/schedule") fun publicSchedule(@RequestParam(defaultValue = "1") page: Int) = service.publicSchedule(page)
    @GetMapping("/submissions/track") fun track(@RequestParam(defaultValue = "") code: String, @RequestParam(defaultValue = "") author: String) = service.track(code, author)
    @PostMapping("/submissions/track") fun trackPost(@RequestBody body: Map<String, String>) = service.track(body["code"].orEmpty(), body["author"].orEmpty())
    @PostMapping("/admin/login") fun login(@RequestBody body: Map<String, String>, request: HttpServletRequest) = auth.login(body["password"].orEmpty(), request.remoteAddr, body["username"].orEmpty())
    @GetMapping("/admin/me") fun me(request: HttpServletRequest) = mapOf("username" to actor(request), "role" to AuthService.ADMIN_ROLE)
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
    @GetMapping("/admin/logs") fun logs(@RequestParam(defaultValue = "200") lines: Int) = logs.tail(lines)
    @GetMapping("/admin/announcements") fun messages() = publications.list(public = false)
    @PostMapping("/admin/announcements") fun publish(@RequestBody input: AnnouncementInput, request: HttpServletRequest) = publications.publish(input, actor(request))
    @PatchMapping("/admin/announcements/{id}") fun visibility(@PathVariable id: Long, @RequestBody body: Map<String, Boolean>, request: HttpServletRequest) =
        publications.visibility(id, body["published"] ?: throw ApiError(400, "请指定消息发布状态"), actor(request))
    @DeleteMapping("/admin/announcements/{id}") fun deleteMessage(@PathVariable id: Long, request: HttpServletRequest) = publications.delete(id, actor(request))
    @PatchMapping("/admin/submissions/{id}") fun update(@PathVariable id: Long, @RequestBody body: Map<String, Any?>, request: HttpServletRequest) = service.update(id, body, actor(request))
    @DeleteMapping("/admin/submissions/{id}") fun delete(@PathVariable id: Long, request: HttpServletRequest) = service.delete(id, actor(request))
    @GetMapping("/admin/stats") fun stats() = service.stats()
    @GetMapping("/admin/export") fun export(@RequestParam params: Map<String, String>, request: HttpServletRequest): ResponseEntity<ByteArray> {
        val file = service.export(params, actor(request))
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment().filename(file.filename, Charsets.UTF_8).build().toString())
            .body(file.bytes)
    }
}
