package com.notes.notes.controller.authController;

import com.notes.notes.entity.authEntities.User;
import com.notes.notes.entity.authEntities.VerificationToken;
import com.notes.notes.repository.authRepo.UserRepository;
import com.notes.notes.repository.authRepo.VerificationTokenRepository;
import com.notes.notes.security.request.SignupRequest;
import com.notes.notes.service.authServices.UserService;
import com.notes.notes.service.googleSheetServices.GoogleSheetsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('ROLE_ADMIN')")
public class AdminController {
    @Autowired
    UserRepository userRepository;

    @Autowired
    VerificationTokenRepository verificationTokenRepository;

    @Autowired
    UserService userService;

    @Autowired
    GoogleSheetsService googleSheetsService;

    @GetMapping("/users")
    public String viewAllUsers(Model model) {
        List<User> users = userService.getAllUsers();
        model.addAttribute("users", users);
        return "auth/Users"; // admin-users.html
    }

    @GetMapping("/user/{id}")
    public String viewUserDetails(@PathVariable Long id, Model model) {
        model.addAttribute("user", userService.getUserById(id));
        return "auth/UserDetails"; // admin-user-details.html
    }

    @GetMapping("/update-role")
    public String showUpdateRolePage() {
        return "auth/UpdateRole"; // admin-update-role.html
    }

    @PostMapping("/update-role")
    public String updateUserRole(@RequestParam Long userId,
                                 @RequestParam String roleName,
                                 Model model) {
        userService.updateUserRole(userId, roleName);
        model.addAttribute("success", "User role updated successfully");
        return "auth/UpdateRole";
    }

    @PostMapping("/delete/{userId}")
    public String deleteUser(@PathVariable Long userId, Model model) {
        userService.deleteUser(userId);
        model.addAttribute("success", "User deleted successfully");
        return "redirect:/admin/users";  // redirect to refresh list
    }

    @GetMapping("/verify")
    public String verifyAccount(@RequestParam("token") String token, Model model) {

        // Fetch token from DB
        VerificationToken verificationToken = verificationTokenRepository
                .findByToken(token)
                .orElse(null);

        // Invalid token
        if (verificationToken == null) {
            model.addAttribute("error", "Invalid verification token!");
            return "auth/Verification-failed";
        }

        // Expired token
        if (verificationToken.getExpiryDate().isBefore(LocalDateTime.now())) {
            model.addAttribute("error", "Verification link expired!");
            return "auth/Verification-failed";
        }

        // Token already used
        if (verificationToken.isUsed()) {
            model.addAttribute("error", "This verification link has already been used.");
            return "auth/Verification-failed";
        }

        // Mark user as verified
        User user = verificationToken.getUser();
        user.setVerified(true);

        userRepository.save(user);

        try {
            googleSheetsService.updateUserInSheet(user);
            System.out.println("✅ User verification synced to Google Sheets: " + user.getUserName());
        } catch (Exception e) {
            System.err.println("⚠️ Failed to update verification status in Google Sheets: " + e.getMessage());
        }

        // Mark token as used or delete it
        verificationToken.setUsed(true);
        verificationTokenRepository.save(verificationToken);

        model.addAttribute("message", "Account for user '" + user.getUserName() + "' verified successfully!");
        return "auth/Verification-success";
    }

    @GetMapping("/register-employee")
    public String showEmployeeSignup(Model model) {
        SignupRequest signupRequest = new SignupRequest();
        model.addAttribute("signupRequest", signupRequest);
        model.addAttribute("employeeSignup", true); // identify admin-based signup
        return "auth/signup";
    }
}
