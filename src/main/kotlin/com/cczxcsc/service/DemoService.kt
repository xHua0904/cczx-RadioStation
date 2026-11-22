package com.cczxcsc.service

import com.cczxcsc.common.statuses
import com.cczxcsc.model.SubmissionInput
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DemoService(private val submissions: SubmissionService) {
    @Transactional
    fun generate() {
        repeat(20) { i ->
            submissions.create(SubmissionInput(author = "演示同学${i + 1}", className = "高一${i % 5 + 1}班",
                songName = "演示点播${i + 1}", artist = "演示歌手", message = "愿大家在学习与生活中收获成长。", reason = "把温暖分享给身边的人。", status = statuses[i % statuses.size], note = "演示数据"), admin = true, actor = AuditService.commandActor)
        }
        println("已添加 20 条演示投稿（审核备注标记为「演示数据」）。")
    }
}
