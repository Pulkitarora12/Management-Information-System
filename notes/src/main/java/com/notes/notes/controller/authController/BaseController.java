package com.notes.notes.controller.authController;

import com.notes.notes.entity.authEntities.User;
import com.notes.notes.service.authServices.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class BaseController {

    @Autowired
    private UserService userService;

    @ModelAttribute("loggedInUser")
    public User addLoggedInUserToModel(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails != null) {
            return userService.findByUsername(userDetails.getUsername());
        }
        return null;
    }
}
