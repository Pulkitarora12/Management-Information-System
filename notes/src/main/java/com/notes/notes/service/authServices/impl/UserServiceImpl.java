package com.notes.notes.service.authServices.impl;

import com.notes.notes.entity.authEntities.AppRole;
import com.notes.notes.entity.authEntities.Role;
import com.notes.notes.entity.authEntities.User;
import com.notes.notes.entity.authEntities.VerificationToken;
import com.notes.notes.repository.authRepo.RoleRepository;
import com.notes.notes.repository.authRepo.UserRepository;
import com.notes.notes.repository.authRepo.VerificationTokenRepository;
import com.notes.notes.service.googleSheetServices.GoogleSheetsService;
import com.notes.notes.service.emailService.EmailService;
import com.notes.notes.service.authServices.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

@Service
@RequiredArgsConstructor
@Transactional
public class UserServiceImpl implements UserService {

    @Autowired
    UserRepository userRepository;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    RoleRepository roleRepository;

    @Autowired
    EmailService emailService;

    @Autowired
    VerificationTokenRepository verificationTokenRepository;

    @Autowired
    GoogleSheetsService googleSheetsService;

    @Override
    public User saveUser(User user) {

        // Assign default role if not set already
        if (user.getRole() == null) {
            Role defaultRole = roleRepository.findByRoleName(AppRole.ROLE_USER)
                    .orElseThrow(() -> new RuntimeException("Default role not found"));
            user.setRole(defaultRole);
        }

        // Save user
        User savedUser = userRepository.save(user);

        // If user already verified, don't send email
        if (savedUser.isVerified()) {
            try {
                googleSheetsService.addUserToSheet(savedUser);
            } catch (Exception e) {
                System.err.println("⚠️ Failed to sync user to Google Sheets: " + e.getMessage());
                // Don't throw exception - we still want the user saved to DB even if Sheets fails
            }
            return savedUser;
        }

        // Generate UUID token
        String tokenValue = java.util.UUID.randomUUID().toString();

        // Create and save verification token
        VerificationToken token = new VerificationToken();
        token.setToken(tokenValue);
        token.setUser(savedUser);
        token.setExpiryDate(LocalDateTime.now().plusHours(24)); // token valid for 24 hours
        token.setUsed(false);

        verificationTokenRepository.save(token);

        try {
            googleSheetsService.addUserToSheet(savedUser);
        } catch (Exception e) {
            System.err.println("⚠️ Failed to sync user to Google Sheets: " + e.getMessage());
            // Don't throw exception - we still want the user saved to DB even if Sheets fails
        }

        // Send email to admin
        emailService.sendVerificationEmailToAdmin(savedUser, tokenValue);

        return savedUser;
    }

    @Override
    public void updateUserRole(Long userId, String roleName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with ID: " + userId));

        // Convert String to Enum (assuming AppRole is your enum)
        AppRole appRole;
        try {
            appRole = AppRole.valueOf(roleName);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid role name: " + roleName);
        }

        // Now check with enum version
        Role role = roleRepository.findByRoleName(AppRole.valueOf(appRole.name()))
                .orElseThrow(() -> new RuntimeException("Role not found: " + roleName));

        user.setRole(role);
        User updatedUser = userRepository.save(user);

        // ✅ NEW: Update in Google Sheets
        try {
            googleSheetsService.updateUserInSheet(updatedUser);
        } catch (Exception e) {
            System.err.println("⚠️ Failed to update user in Google Sheets: " + e.getMessage());
        }
    }


    @Override
    public void deleteUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new RuntimeException("User not found with ID: " + userId);
        }
        userRepository.deleteById(userId);
        try {
            googleSheetsService.deleteUserFromSheet(userId);
        } catch (Exception e) {
            System.err.println("⚠️ Failed to delete user from Google Sheets: " + e.getMessage());
        }
    }

    @Override
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    @Override
    public User getUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found with ID: " + id));
    }

    @Override
    public User findByUsername(String username) {
        return userRepository.findByUserName(username)
                .orElseThrow(() -> new RuntimeException("User not found with username: " + username));
    }

    @Override
    public String generateRandomPassword() {
        Random random = new Random();
        int password = 100000 + random.nextInt(900000); // generates 6-digit number
        return String.valueOf(password);
    }

    @Override
    public void resetPassword(String email, String newPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found with email: " + email));

        user.setPassword(encoder.encode(newPassword));
        userRepository.save(user);
    }
}
