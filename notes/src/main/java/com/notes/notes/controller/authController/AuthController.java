package com.notes.notes.controller.authController;

import com.notes.notes.dto.moduleDTOS.ForgotPasswordRequest;
import com.notes.notes.entity.authEntities.User;
import com.notes.notes.repository.authRepo.UserRepository;
import com.notes.notes.security.request.SignupRequest;
import com.notes.notes.security.services.UserDetailsImpl;
import com.notes.notes.service.emailService.EmailService;
import com.notes.notes.service.authServices.UserService;
import com.notes.notes.service.taskModuleServices.TaskTemplateService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    AuthenticationManager authenticationManager;

    @Autowired
    UserRepository userRepository;

    @Autowired
    EmailService emailService;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    UserService userService;

    private final TaskTemplateService taskTemplateService;

    public AuthController(TaskTemplateService taskTemplateService) {
        this.taskTemplateService = taskTemplateService;
    }

    @GetMapping("/login")
    public String showLoginPage(){
        return "auth/login"; // login.html
    }

    @PostMapping("/login")
    public String processLogin(@RequestParam String username,
                               @RequestParam String password,
                               Model model) {

        Authentication authentication;

        try {
            authentication = authenticationManager
                    .authenticate(new UsernamePasswordAuthenticationToken(username, password));
        } catch (AuthenticationException exception) {
            model.addAttribute("error", "Invalid username or password");
            return "auth/login"; // return directly, no redirect
        }

        SecurityContextHolder.getContext().setAuthentication(authentication);
        return "redirect:/auth/dashboard";
    }

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal UserDetailsImpl user) {
        if (!user.isVerified()) {
            return "redirect:/auth/verification-pending";
        }
        return "auth/Dashboard";
    }


    @GetMapping("/signup")
    public String showSignupPage(Model model) {
        model.addAttribute("signupRequest", new SignupRequest());
        return "auth/signup"; // signup.html
    }

    @PostMapping("/signup")
    public String processSignup(
            @Valid @ModelAttribute("signupRequest") SignupRequest signupRequest,
            @RequestParam(value = "employeeSignup", required = false) Boolean employeeSignup,
            @AuthenticationPrincipal UserDetailsImpl loggedInUser,
            Model model) {

        if (userRepository.existsByUserName(signupRequest.getUsername())) {
            model.addAttribute("error", "Username already taken");
            return "auth/signup";
        }

        if (userRepository.existsByEmail(signupRequest.getEmail())) {
            model.addAttribute("error", "Email already registered");
            return "auth/signup";
        }

        User user = new User(
                signupRequest.getUsername(),
                signupRequest.getEmail(),
                encoder.encode(signupRequest.getPassword())
        );

        user.setEmployeeFullName(signupRequest.getEmployeeFullName());
        user.setEmployeeDepartment(signupRequest.getEmployeeDepartment());
        user.setEmployeePhone(signupRequest.getEmployeePhone());

        // If admin registering employee → auto enable & verify
        if (Boolean.TRUE.equals(employeeSignup) && loggedInUser != null &&
                loggedInUser.getAuthorities().stream()
                        .anyMatch(auth -> auth.getAuthority().equals("ROLE_ADMIN"))) {

            user.setVerified(true);
            user.setEnabled(true);
        }

        user.setEnabled(true);
        userService.saveUser(user);

        model.addAttribute("success", "Employee registered successfully!");
        return "redirect:/auth/login";
    }

    @GetMapping("/verification-pending")
    public String verificationPending() {
        return "auth/Verification-pending";
    }

    @GetMapping("/forgot-password")
    public String showForgotPasswordPage() {
        return "auth/forgot-password"; // HTML page
    }

    @PostMapping("/forgot-password")
    public String processForgotPassword(
            @Valid @ModelAttribute ForgotPasswordRequest request,
            RedirectAttributes redirectAttributes) {

        try {
            // Check if user exists
            User user = userRepository.findByEmail(request.getEmail())
                    .orElseThrow(() -> new RuntimeException("No account found with this email"));

            // Generate random 6-digit password
            String newPassword = userService.generateRandomPassword();

            // Reset password in database
            userService.resetPassword(request.getEmail(), newPassword);

            // Send email with new password
            emailService.sendNewPasswordEmail(request.getEmail(), newPassword);

            redirectAttributes.addFlashAttribute("success",
                    "New password has been sent to your email!");
            return "redirect:/auth/login";

        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error",
                    "Failed to reset password. Please try again.");
            return "redirect:/auth/forgot-password";
        }
    }

    @GetMapping("/change-password")
    public String showChangePasswordPage() {
        return "auth/change-password";
    }

    @PostMapping("/change-password")
    public String processChangePassword(
            @RequestParam String currentPassword,
            @RequestParam String newPassword,
            @AuthenticationPrincipal UserDetailsImpl loggedInUser,
            Model model) {

        // Fetch user from DB using logged-in email
        User user = userRepository.findByEmail(loggedInUser.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Check current password
        if (!encoder.matches(currentPassword, user.getPassword())) {
            model.addAttribute("error", "Current Password is incorrect");
            return "auth/change-password";
        }

        // Update password
        user.setPassword(encoder.encode(newPassword));
        userRepository.save(user);

        // Logout user after password change
        SecurityContextHolder.clearContext();

        return "redirect:/auth/login";
    }


}
