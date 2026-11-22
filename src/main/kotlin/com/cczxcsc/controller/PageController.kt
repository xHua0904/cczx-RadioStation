package com.cczxcsc.controller

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.*

@Controller
class PageController {
    @GetMapping("/admin") fun admin(): String = "forward:/admin.html"
}
