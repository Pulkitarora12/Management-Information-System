package com.notes.notes.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Controller
public class testingController {
//
//    @GetMapping("/public")
//    public static String app() {
//        return "Welcome to Notes App";
//    }
//
    @GetMapping("/")
    public static String nil() {
        return "auth/login";
    }
//
//    @GetMapping("/public/hello")
//    public static String hello() {
//        return "hello";
//    }
//
//    @GetMapping("/public/hi")
//    public static String hi() {
//        return "hi";
//    }
//
//    @GetMapping("/public/contact")
//    public static String contact() {
//        return "welcome to contact page.";
//    }
//
//    @GetMapping("/admin/users")
//    public static String adminUsers() { return "Welcome to Admin Users"; }
//
//    @GetMapping("/admin/contacts")
//    public static String adminContact() { return "Welcome to Admin contacts"; }
}
